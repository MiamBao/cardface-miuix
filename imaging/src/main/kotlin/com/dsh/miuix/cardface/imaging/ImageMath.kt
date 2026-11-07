package com.dsh.miuix.cardface.imaging

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 卡面合成用的几何/合成/读写工具。
 *
 * 纯 JVM 实现：像素数据用 [PixelImage]，PNG 读写用 `javax.imageio`。
 * （`javax.imageio` 只允许出现在本纯 JVM 模块里，Android 侧用 Bitmap 自己的编解码。）
 */
object ImageMath {

    /** 圆角边缘的每像素超采样数：4x4 = 16 个采样点。 */
    private const val COVERAGE_SAMPLES = 4

    // ---------------------------------------------------------------------
    // 裁剪
    // ---------------------------------------------------------------------

    /**
     * 按目标宽高比在源图内部裁一块最大的区域（可平移、可缩放）。
     *
     * @param ax, ay   目标宽高比，例如卡面 960:606。
     * @param centerX  裁剪中心在源图中的归一化横坐标（0=最左，0.5=居中，1=最右）。
     * @param centerY  裁剪中心在源图中的归一化纵坐标。
     * @param zoom     放大倍数：1 = 铺满（裁出最大的等比区域），2 = 再放大 2 倍（裁一半边长）。
     */
    fun cropAspect(
        src: PixelImage,
        ax: Int,
        ay: Int,
        centerX: Float = 0.5f,
        centerY: Float = 0.5f,
        zoom: Float = 1f,
    ): PixelImage {
        require(ax > 0 && ay > 0) { "宽高比必须为正：$ax:$ay" }
        val targetAspect = ax.toDouble() / ay
        val srcAspect = src.width.toDouble() / src.height
        val baseW: Int
        val baseH: Int
        if (srcAspect > targetAspect) {
            baseH = src.height
            baseW = maxOf(1, (src.height * targetAspect).roundToInt())
        } else {
            baseW = src.width
            baseH = maxOf(1, (src.width / targetAspect).roundToInt())
        }
        val z = if (zoom.isNaN() || zoom <= 0f) 1f else zoom
        val cropW = (baseW / z).roundToInt().coerceIn(1, src.width)
        val cropH = (baseH / z).roundToInt().coerceIn(1, src.height)
        val cx = centerX.coerceIn(0f, 1f) * src.width
        val cy = centerY.coerceIn(0f, 1f) * src.height
        val left = (cx - cropW / 2.0).roundToInt().coerceIn(0, src.width - cropW)
        val top = (cy - cropH / 2.0).roundToInt().coerceIn(0, src.height - cropH)
        return src.subImage(left, top, cropW, cropH)
    }

    /** 按源图像素坐标裁剪矩形（自动夹紧到图内，结果至少 1x1）。 */
    fun cropRect(src: PixelImage, left: Int, top: Int, right: Int, bottom: Int): PixelImage {
        val l = left.coerceIn(0, src.width - 1)
        val t = top.coerceIn(0, src.height - 1)
        val r = right.coerceIn(l + 1, src.width)
        val b = bottom.coerceIn(t + 1, src.height)
        return src.subImage(l, t, r - l, b - t)
    }

    // ---------------------------------------------------------------------
    // 圆角 / 合成
    // ---------------------------------------------------------------------

    /**
     * 圆角裁切。边缘用 4x4 超采样覆盖度抗锯齿，alpha 按覆盖度缩放，
     * RGB 通道保持不变（直通 alpha），所以不会出现黑边/杂色。
     *
     * @param radiusPx 半径（像素）。960 宽卡面固定用 40px；超过短边一半时自动夹紧。
     */
    fun roundCorners(src: PixelImage, radiusPx: Float): PixelImage {
        if (radiusPx <= 0f) return src.copy()
        val w = src.width
        val h = src.height
        val r = radiusPx.coerceAtMost(min(w, h) / 2f)
        if (r <= 0.5f) return src.copy()

        val out = src.copy()
        val px = out.pixels
        val n = COVERAGE_SAMPLES
        val cells = n * n
        val r2 = r.toDouble() * r.toDouble()
        val zone = ceil(r).toInt() + 1

        fun coverage(cx: Double, cy: Double, x: Int, y: Int): Int {
            var inside = 0
            val step = 1.0 / n
            var j = 0
            while (j < n) {
                val py = y + (j + 0.5) * step
                val dy = py - cy
                var i = 0
                while (i < n) {
                    val pxx = x + (i + 0.5) * step
                    val dx = pxx - cx
                    if (dx * dx + dy * dy <= r2) inside++
                    i++
                }
                j++
            }
            return inside
        }

        for (y in 0 until h) {
            val inTop = y < zone
            val inBottom = y >= h - zone
            if (!inTop && !inBottom) continue
            for (x in 0 until w) {
                val inLeft = x < zone
                val inRight = x >= w - zone
                if (!inLeft && !inRight) continue
                var inside = cells
                if (inTop && inLeft) inside = min(inside, coverage(r.toDouble(), r.toDouble(), x, y))
                if (inTop && inRight) inside = min(inside, coverage((w - r).toDouble(), r.toDouble(), x, y))
                if (inBottom && inLeft) inside = min(inside, coverage(r.toDouble(), (h - r).toDouble(), x, y))
                if (inBottom && inRight) inside = min(inside, coverage((w - r).toDouble(), (h - r).toDouble(), x, y))
                if (inside >= cells) continue
                val idx = y * w + x
                val a = (px[idx] ushr 24) and 0xFF
                if (a == 0) continue
                val na = (a * inside + cells / 2) / cells
                px[idx] = (na shl 24) or (px[idx] and 0x00FFFFFF)
            }
        }
        return out
    }

    /**
     * SRC_OVER 合成：把 [overlay] 叠到 [base] 的 (dx, dy) 位置，返回新图（不修改入参）。
     * 卡面标志是 960x606 的整幅透明 PNG，dx = dy = 0 即整幅叠加。
     */
    fun compositeOver(
        base: PixelImage,
        overlay: PixelImage,
        dx: Int = 0,
        dy: Int = 0,
    ): PixelImage {
        val out = base.copy()
        val w = out.width
        val h = out.height
        val px = out.pixels
        val op = overlay.pixels
        for (oy in 0 until overlay.height) {
            val by = oy + dy
            if (by < 0 || by >= h) continue
            var ob = oy * overlay.width
            for (ox in 0 until overlay.width) {
                val bx = ox + dx
                val ov = op[ob++]
                val oa = (ov ushr 24) and 0xFF
                if (oa == 0) continue
                if (bx < 0 || bx >= w) continue
                val idx = by * w + bx
                if (oa == 255) {
                    px[idx] = ov
                    continue
                }
                val oaf = oa / 255f
                val bp = px[idx]
                val baf = ((bp ushr 24) and 0xFF) / 255f
                val outA = oaf + baf * (1f - oaf)
                val inv = if (outA <= 0f) 0f else 1f / outA
                val r = (((ov ushr 16) and 0xFF) * oaf + ((bp ushr 16) and 0xFF) * baf * (1f - oaf)) * inv
                val g = (((ov ushr 8) and 0xFF) * oaf + ((bp ushr 8) and 0xFF) * baf * (1f - oaf)) * inv
                val b = ((ov and 0xFF) * oaf + (bp and 0xFF) * baf * (1f - oaf)) * inv
                px[idx] = argb(
                    (outA * 255f).roundToInt(),
                    r.roundToInt(),
                    g.roundToInt(),
                    b.roundToInt(),
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------------
    // PNG 读写（纯 JVM；Android 侧不要引用这些方法）
    // ---------------------------------------------------------------------

    /** 读取 PNG/JPEG 等常见格式为 ARGB 像素图。读取失败抛 [IllegalArgumentException]。 */
    fun loadPng(path: String): PixelImage = loadPng(File(path))

    fun loadPng(file: File): PixelImage {
        require(file.isFile) { "文件不存在：$file" }
        val img = ImageIO.read(file) ?: throw IllegalArgumentException("无法解码图片：$file")
        return fromBufferedImage(img)
    }

    /** 写 PNG（无损，保留 alpha）。会自动创建父目录。 */
    fun savePng(img: PixelImage, path: String) = savePng(img, File(path))

    fun savePng(img: PixelImage, file: File) {
        file.absoluteFile.parentFile?.mkdirs()
        val bi = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
        bi.setRGB(0, 0, img.width, img.height, img.pixels, 0, img.width)
        check(ImageIO.write(bi, "png", file)) { "没有可用的 PNG 编码器：$file" }
    }

    private fun fromBufferedImage(img: BufferedImage): PixelImage {
        val w = img.width
        val h = img.height
        val argb = if (img.type == BufferedImage.TYPE_INT_ARGB) {
            img.getRGB(0, 0, w, h, null, 0, w)
        } else {
            val normalized = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = normalized.createGraphics()
            g.drawImage(img, 0, 0, null)
            g.dispose()
            normalized.getRGB(0, 0, w, h, null, 0, w)
        }
        return PixelImage(w, h, argb)
    }
}
