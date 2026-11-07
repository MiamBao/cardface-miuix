package com.dsh.miuix.cardface.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.dsh.miuix.cardface.imaging.ImageMath
import com.dsh.miuix.cardface.imaging.PixelImage
import com.dsh.miuix.cardface.imaging.Resampler
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Android 包装层：Bitmap/EXIF/Asset ⇄ 纯 JVM 的 [PixelImage] 管线。
 *
 * 质量路线（替代原版 `Bitmap.createScaledBitmap(..., true)`）：
 * EXIF 转正 → `inSampleSize` 解码到约 2x 目标 → 裁剪 → [Resampler.lanczos3]
 * （缩放感知 + 线性光）→ 40px 圆角（4x4 超采样）→ 标志 PNG SRC_OVER → 960x606 Bitmap。
 *
 * **禁止**用 `Bitmap.createScaledBitmap`：所有缩放都必须走 [Resampler]。
 * 旋转用 `Matrix` 做（只旋转不缩放，不算缩放）。
 *
 * 耗时（JVM，ARM64，12MP 4000x3000 → 960x606 纯算法；见 docs/imaging/report.md）：
 * 预缩小 box ≈ 25~40ms，Lanczos3 ≈ 110~170ms，合计 ≈ 140~210ms。
 * Android 端先用 `inSampleSize` 解码到约 3MP（12MP 图的 inSampleSize=2），
 * buildCard 纯算法耗时可降到约 40~80ms；再加 PNG 编码约 30~60ms。
 * `thumbnail` 走 boxAverage，1MP → 480px 约 3~6ms（裁剪界面实时预览够用）。
 */
object BitmapPipeline {

    /** 卡面输出参数。默认 960x606、圆角 40px、线性光、无标志。 */
    data class Options(
        val targetW: Int = 960,
        val targetH: Int = 606,
        val roundRadiusPx: Float = 40f,
        val linearLight: Boolean = true,
        /** 卡面标志资源名：`jingjinji` / `card_logo/jingjinji.png` 两种写法都接受。 */
        val logoAsset: String? = null,
    )

    private const val DEFAULT_MAX_DIM = 4096

    /** 解码时至少保留 2 倍目标边长（Lanczos 需要余量）。 */
    private const val MIN_DECODE_LONG_SIDE = 2 * 960

    // ---------------------------------------------------------------------
    // 解码
    // ---------------------------------------------------------------------

    /**
     * 从 Uri 解码：先 `inJustDecodeBounds` 读尺寸，再用 2 的幂 `inSampleSize`
     * 解到「长边 ≥ 1920、且 ≤ maxDim」的尺寸（避免解全图爆内存），最后按 EXIF 转正。
     *
     * 12MP JPEG（inSampleSize=2 → 约 3MP）在 Android 上解码约 30~60ms。
     *
     * @param maxDim 解码后最长边上限（默认 4096）。内部还会按 2x 目标边长进一步限制。
     * @return 失败（无权限、坏文件、解码 OOM）时返回 null，不抛异常。
     */
    fun decode(context: Context, uri: Uri, maxDim: Int = DEFAULT_MAX_DIM): Bitmap? {
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sample = inSampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null
            val orientation = exifOrientation(context, uri)
            applyExifOrientation(decoded, orientation)
        } catch (_: Exception) {
            null
        }
    }

    /** 从文件路径解码（图库预览用），规则同 [decode]。 */
    fun decodeFile(path: String, maxDim: Int = DEFAULT_MAX_DIM): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = inSampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(path, opts) ?: return null
            val orientation = try {
                ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            applyExifOrientation(decoded, orientation)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 计算 2 的幂 `inSampleSize`：解码后长边尽量接近 2x 目标边长（1920），
     * 且不超过 [maxDim]。12MP 图得到 2（约 3MP），108MP 图得到 8。
     */
    fun inSampleSizeFor(width: Int, height: Int, maxDim: Int = DEFAULT_MAX_DIM): Int {
        val limit = max(1, maxDim)
        val longSide = max(width, height)
        var sample = 1
        while (longSide / (sample * 2) >= MIN_DECODE_LONG_SIDE) sample *= 2
        while (longSide / sample > limit) sample *= 2
        return sample
    }

    private fun exifOrientation(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** 按 EXIF 方向把图转正（只用 Matrix 旋转/翻转，不做缩放）。 */
    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (_: Exception) {
            bitmap
        }
    }

    // ---------------------------------------------------------------------
    // 卡面合成
    // ---------------------------------------------------------------------

    /**
     * 合成卡面：严格 [Options.targetW] x [Options.targetH]（默认 960x606）。
     *
     * 步骤：裁剪（[crop] 为源图像素坐标；null = 居中按目标比例裁剪）→ Lanczos3
     * 缩放感知 + 线性光 → 圆角 40px → 卡面标志 SRC_OVER。
     *
     * 耗时（JVM 纯算法，12MP→960x606）：约 140~210ms；Android 端解码到 3MP 后
     * 约 40~80ms。
     *
     * @param crop 源图上的裁剪矩形（像素坐标，left/top/right/bottom），会先夹紧到图内；
     *             传 null 时自动居中裁成目标宽高比。
     */
    fun buildCard(
        context: Context,
        src: Bitmap,
        crop: RectF? = null,
        opts: Options = Options(),
    ): Bitmap {
        require(opts.targetW > 0 && opts.targetH > 0) { "目标尺寸必须为正" }
        var px = src.toPixelImage()
        px = if (crop == null || crop.width() < 1f || crop.height() < 1f) {
            ImageMath.cropAspect(px, opts.targetW, opts.targetH, 0.5f, 0.5f, 1f)
        } else {
            ImageMath.cropRect(
                px,
                floor(crop.left.toDouble()).toInt(),
                floor(crop.top.toDouble()).toInt(),
                ceil(crop.right.toDouble()).toInt(),
                ceil(crop.bottom.toDouble()).toInt(),
            )
        }
        var out = Resampler.lanczos3(px, opts.targetW, opts.targetH, opts.linearLight)
        if (opts.roundRadiusPx > 0f) {
            out = ImageMath.roundCorners(out, opts.roundRadiusPx)
        }
        val logo = opts.logoAsset
        if (!logo.isNullOrBlank()) {
            out = compositeLogo(context, out, logo, opts)
        }
        return out.toBitmap()
    }

    private fun compositeLogo(context: Context, card: PixelImage, asset: String, opts: Options): PixelImage {
        val logo = try {
            val path = normalizeLogoAsset(asset)
            context.assets.open(path).use { stream ->
                val bmp = BitmapFactory.decodeStream(stream) ?: return card
                val px = bmp.toPixelImage()
                bmp.recycle()
                px
            }
        } catch (_: Exception) {
            return card
        }
        // 标志资源是 960x606 的整幅透明 PNG；尺寸不同时同样走 Lanczos 缩放，不用 createScaledBitmap
        val fitted = if (logo.width == opts.targetW && logo.height == opts.targetH) {
            logo
        } else {
            Resampler.lanczos3(logo, opts.targetW, opts.targetH, linearLight = false)
        }
        return ImageMath.compositeOver(card, fitted, 0, 0)
    }

    private fun normalizeLogoAsset(name: String): String {
        var path = name.trim()
        if (!path.startsWith("card_logo/")) path = "card_logo/$path"
        if (!path.endsWith(".png", ignoreCase = true)) path = "$path.png"
        return path
    }

    // ---------------------------------------------------------------------
    // 缩略图 / 保存
    // ---------------------------------------------------------------------

    /**
     * 快速缩略图：boxAverage（面积平均）缩小，不跑 Lanczos，适合裁剪/图库界面实时预览。
     * 1MP → 480px 约 3~6ms。
     */
    fun thumbnail(
        src: Bitmap,
        maxDim: Int = 480,
        linearLight: Boolean = true,
    ): Bitmap {
        val longSide = max(src.width, src.height)
        if (longSide <= maxDim) return src.copy(Bitmap.Config.ARGB_8888, false)
        val scale = maxDim.toDouble() / longSide
        val w = max(1, (src.width * scale).roundToInt())
        val h = max(1, (src.height * scale).roundToInt())
        val out = Resampler.boxAverage(src.toPixelImage(), w, h, linearLight)
        return out.toBitmap()
    }

    /** 解码 + 缩略图（图库网格用），失败返回 null。 */
    fun thumbnail(
        context: Context,
        uri: Uri,
        maxDim: Int = 480,
        linearLight: Boolean = true,
    ): Bitmap? {
        val decoded = decode(context, uri, maxDim = max(maxDim, MIN_DECODE_LONG_SIDE)) ?: return null
        val thumb = thumbnail(decoded, maxDim, linearLight)
        if (thumb !== decoded) decoded.recycle()
        return thumb
    }

    /** 保存为 PNG（无损、带 alpha）。卡面输出固定 960x606。 */
    fun savePng(bitmap: Bitmap, file: File) {
        file.absoluteFile.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG 编码失败：$file" }
        }
    }
}

/** Bitmap → [PixelImage]（ARGB_8888）。 */
fun Bitmap.toPixelImage(): PixelImage {
    val w = width
    val h = height
    val px = IntArray(w * h)
    getPixels(px, 0, w, 0, 0, w, h)
    return PixelImage(w, h, px)
}

/** [PixelImage] → ARGB_8888 Bitmap。 */
fun PixelImage.toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
