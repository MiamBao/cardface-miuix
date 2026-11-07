package com.dsh.miuix.cardface.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 高质量缩放。
 *
 * 原版卡面工坊直接把 12MP 图 `Bitmap.createScaledBitmap(..., true)`（双线性、2x2 采样）
 * 压到 960x606：缩小 4 倍时每个输出像素只看了源图的 4 个像素，高频细节全部折叠成
 * 锯齿/摩尔纹。这里的 [lanczos3] 是**缩放感知**的：缩小 N 倍时 Lanczos3 的核半径
 * 也放大 N 倍，等效于先对整块区域做带限低通再采样，所以不会走样。
 *
 * 流程（与规格一致）：
 * 1. 若缩小倍数 ≥ 2，先用整数倍 [boxAverage] 预缩小到「目标尺寸的 2 倍」附近，
 *    既省内存也把后续 Lanczos 的核半径限制在 3~6 像素，兼顾质量与速度；
 * 2. 可分离 Lanczos3（先水平再垂直）缩到精确目标尺寸；
 * 3. 默认在线性光空间加权（[Srgb] 解码/编码），避免 sRGB 直接平均导致的变暗/发灰。
 *
 * 所有方法对 alpha 的处理都是预乘 alpha 后加权，输出时再还原直通 alpha，
 * 透明边缘不会产生黑边。
 */
object Resampler {

    private const val INV255 = 1f / 255f

    // ---------------------------------------------------------------------
    // 公开 API
    // ---------------------------------------------------------------------

    /**
     * 缩放感知 Lanczos3 缩放。**默认在线性光下重采样**，这是卡面质量的核心。
     *
     * 4000x3000（12MP）→ 960x606 在 JVM 上约 100~180ms（见 docs/imaging/report.md），
     * 内存峰值约 90MB；Android 端配合 `inSampleSize` 先解码到约 3MP，耗时可降到
     * 40~80ms。
     *
     * @param linearLight 线性光重采样（默认 true）；关闭只影响亮度过渡，不改变尺寸。
     */
    fun lanczos3(
        src: PixelImage,
        dstW: Int,
        dstH: Int,
        linearLight: Boolean = true,
    ): PixelImage {
        require(dstW > 0 && dstH > 0) { "目标尺寸必须为正：${dstW}x$dstH" }
        if (dstW == src.width && dstH == src.height) return src.copy()

        // 1. 整数倍 box 预缩小，保证预缩小后仍 ≥ 2x 目标尺寸
        val factor = max(1, min(src.width / (2 * dstW), src.height / (2 * dstH)))
        val pre = if (factor > 1) {
            val preW = max(dstW, src.width / factor)
            val preH = max(dstH, src.height / factor)
            boxAverage(src, preW, preH, linearLight)
        } else {
            src
        }
        return lanczosCore(pre, dstW, dstH, linearLight)
    }

    /**
     * 普通双线性（2x2 采样，等价于 Android `Bitmap.createScaledBitmap(..., filter = true)`）。
     * 只作为对照组的「原版」实现，**不要**用于卡面输出。
     */
    fun bilinear(src: PixelImage, dstW: Int, dstH: Int): PixelImage {
        require(dstW > 0 && dstH > 0) { "目标尺寸必须为正：${dstW}x$dstH" }
        if (dstW == src.width && dstH == src.height) return src.copy()
        val sw = src.width
        val sh = src.height
        val opaque = src.opaque
        val scaleX = sw.toDouble() / dstW
        val scaleY = sh.toDouble() / dstH
        val px = src.pixels
        val out = IntArray(dstW * dstH)

        var oi = 0
        for (y in 0 until dstH) {
            val syRaw = (y + 0.5) * scaleY - 0.5
            val sy = syRaw.coerceIn(0.0, (sh - 1).toDouble())
            val y0 = sy.toInt().coerceIn(0, sh - 1)
            val y1 = min(y0 + 1, sh - 1)
            val fy = (sy - y0).toFloat()
            for (x in 0 until dstW) {
                val sxRaw = (x + 0.5) * scaleX - 0.5
                val sx = sxRaw.coerceIn(0.0, (sw - 1).toDouble())
                val x0 = sx.toInt().coerceIn(0, sw - 1)
                val x1 = min(x0 + 1, sw - 1)
                val fx = (sx - x0).toFloat()
                val p00 = px[y0 * sw + x0]
                val p10 = px[y0 * sw + x1]
                val p01 = px[y1 * sw + x0]
                val p11 = px[y1 * sw + x1]
                val w00 = (1f - fx) * (1f - fy)
                val w10 = fx * (1f - fy)
                val w01 = (1f - fx) * fy
                val w11 = fx * fy
                if (opaque) {
                    val r = ((p00 ushr 16 and 0xFF) * w00 + (p10 ushr 16 and 0xFF) * w10 +
                        (p01 ushr 16 and 0xFF) * w01 + (p11 ushr 16 and 0xFF) * w11) * INV255
                    val g = ((p00 ushr 8 and 0xFF) * w00 + (p10 ushr 8 and 0xFF) * w10 +
                        (p01 ushr 8 and 0xFF) * w01 + (p11 ushr 8 and 0xFF) * w11) * INV255
                    val b = ((p00 and 0xFF) * w00 + (p10 and 0xFF) * w10 +
                        (p01 and 0xFF) * w01 + (p11 and 0xFF) * w11) * INV255
                    out[oi++] = packOpaque(r, g, b, linearLight = false)
                } else {
                    // 预乘 alpha 插值，避免透明边缘发黑
                    val a00 = (p00 ushr 24 and 0xFF) * INV255
                    val a10 = (p10 ushr 24 and 0xFF) * INV255
                    val a01 = (p01 ushr 24 and 0xFF) * INV255
                    val a11 = (p11 ushr 24 and 0xFF) * INV255
                    val r = (p00 ushr 16 and 0xFF) * INV255 * a00 * w00 +
                        (p10 ushr 16 and 0xFF) * INV255 * a10 * w10 +
                        (p01 ushr 16 and 0xFF) * INV255 * a01 * w01 +
                        (p11 ushr 16 and 0xFF) * INV255 * a11 * w11
                    val g = (p00 ushr 8 and 0xFF) * INV255 * a00 * w00 +
                        (p10 ushr 8 and 0xFF) * INV255 * a10 * w10 +
                        (p01 ushr 8 and 0xFF) * INV255 * a01 * w01 +
                        (p11 ushr 8 and 0xFF) * INV255 * a11 * w11
                    val b = (p00 and 0xFF) * INV255 * a00 * w00 +
                        (p10 and 0xFF) * INV255 * a10 * w10 +
                        (p01 and 0xFF) * INV255 * a01 * w01 +
                        (p11 and 0xFF) * INV255 * a11 * w11
                    val a = a00 * w00 + a10 * w10 + a01 * w01 + a11 * w11
                    out[oi++] = packPremultiplied(r, g, b, a, linearLight = false)
                }
            }
        }
        return PixelImage(dstW, dstH, out)
    }

    /**
     * 最近邻（点采样）。仅作对照组，模拟最糟糕的「直接抽点」缩小。
     */
    fun nearest(src: PixelImage, dstW: Int, dstH: Int): PixelImage {
        require(dstW > 0 && dstH > 0) { "目标尺寸必须为正：${dstW}x$dstH" }
        if (dstW == src.width && dstH == src.height) return src.copy()
        val sw = src.width
        val sh = src.height
        val scaleX = sw.toDouble() / dstW
        val scaleY = sh.toDouble() / dstH
        val out = IntArray(dstW * dstH)
        var oi = 0
        for (y in 0 until dstH) {
            val sy = ((y + 0.5) * scaleY).toInt().coerceIn(0, sh - 1)
            for (x in 0 until dstW) {
                val sx = ((x + 0.5) * scaleX).toInt().coerceIn(0, sw - 1)
                out[oi++] = src.pixels[sy * sw + sx]
            }
        }
        return PixelImage(dstW, dstH, out)
    }

    /**
     * 面积平均（box）缩放。整数倍缩小时就是该区域所有源像素的精确平均，
     * 是「理想低通」的很好近似：无振铃、无走样，用来做预缩小与对比参考图。
     *
     * @param linearLight 在线性光下平均（默认 false，保持 box 的直观语义；
     *   [lanczos3] 内部预缩小时会传入与缩放一致的开关）。
     */
    fun boxAverage(
        src: PixelImage,
        dstW: Int,
        dstH: Int,
        linearLight: Boolean = false,
    ): PixelImage {
        require(dstW > 0 && dstH > 0) { "目标尺寸必须为正：${dstW}x$dstH" }
        if (dstW == src.width && dstH == src.height) return src.copy()
        val sw = src.width
        val sh = src.height
        if (sw % dstW == 0 && sh % dstH == 0) {
            return integerBox(src, dstW, dstH, sw / dstW, sh / dstH, linearLight)
        }
        // 非整数倍：按覆盖面积精确加权
        val opaque = src.opaque
        val scaleX = sw.toDouble() / dstW
        val scaleY = sh.toDouble() / dstH
        val out = IntArray(dstW * dstH)
        var oi = 0
        for (y in 0 until dstH) {
            val sy0 = y * scaleY
            val sy1 = (y + 1) * scaleY
            val iy0 = floor(sy0).toInt().coerceIn(0, sh - 1)
            val iy1 = max(iy0 + 1, ceil(sy1).toInt()).coerceAtMost(sh)
            for (x in 0 until dstW) {
                val sx0 = x * scaleX
                val sx1 = (x + 1) * scaleX
                val ix0 = floor(sx0).toInt().coerceIn(0, sw - 1)
                val ix1 = max(ix0 + 1, ceil(sx1).toInt()).coerceAtMost(sw)
                var r = 0.0
                var g = 0.0
                var b = 0.0
                var a = 0.0
                var wsum = 0.0
                for (jy in iy0 until iy1) {
                    val wy = min(sy1, jy + 1.0) - max(sy0, jy.toDouble())
                    if (wy <= 0.0) continue
                    for (jx in ix0 until ix1) {
                        val wx = min(sx1, jx + 1.0) - max(sx0, jx.toDouble())
                        if (wx <= 0.0) continue
                        val w = wx * wy
                        val p = src.pixels[jy * sw + jx]
                        val av = if (opaque) 1f else (p ushr 24 and 0xFF) * INV255
                        if (linearLight) {
                            r += Srgb.decode(p ushr 16 and 0xFF) * av * w
                            g += Srgb.decode(p ushr 8 and 0xFF) * av * w
                            b += Srgb.decode(p and 0xFF) * av * w
                        } else {
                            r += (p ushr 16 and 0xFF) * INV255 * av * w
                            g += (p ushr 8 and 0xFF) * INV255 * av * w
                            b += (p and 0xFF) * INV255 * av * w
                        }
                        if (!opaque) a += av * w
                        wsum += w
                    }
                }
                val inv = if (wsum > 0.0) 1.0 / wsum else 0.0
                val rr = (r * inv).toFloat()
                val gg = (g * inv).toFloat()
                val bb = (b * inv).toFloat()
                out[oi++] = if (opaque) {
                    packOpaque(rr, gg, bb, linearLight)
                } else {
                    packPremultiplied(rr, gg, bb, (a * inv).toFloat(), linearLight)
                }
            }
        }
        return PixelImage(dstW, dstH, out)
    }

    // ---------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------

    /** 整数倍面积平均（预缩小快路径）。 */
    private fun integerBox(
        src: PixelImage,
        dstW: Int,
        dstH: Int,
        fx: Int,
        fy: Int,
        linearLight: Boolean,
    ): PixelImage {
        val sw = src.width
        val opaque = src.opaque
        val px = src.pixels
        val out = IntArray(dstW * dstH)
        val invN = 1f / (fx * fy).toFloat()
        var oi = 0
        for (y in 0 until dstH) {
            val srcY0 = y * fy
            for (x in 0 until dstW) {
                val srcX0 = x * fx
                var r = 0f
                var g = 0f
                var b = 0f
                var a = 0f
                for (jy in 0 until fy) {
                    var idx = (srcY0 + jy) * sw + srcX0
                    for (jx in 0 until fx) {
                        val p = px[idx++]
                        val av = if (opaque) 1f else (p ushr 24 and 0xFF) * INV255
                        if (linearLight) {
                            r += Srgb.decode(p ushr 16 and 0xFF) * av
                            g += Srgb.decode(p ushr 8 and 0xFF) * av
                            b += Srgb.decode(p and 0xFF) * av
                        } else {
                            r += (p ushr 16 and 0xFF) * INV255 * av
                            g += (p ushr 8 and 0xFF) * INV255 * av
                            b += (p and 0xFF) * INV255 * av
                        }
                        if (!opaque) a += av
                    }
                }
                out[oi++] = if (opaque) {
                    packOpaque(r * invN, g * invN, b * invN, linearLight)
                } else {
                    packPremultiplied(r * invN, g * invN, b * invN, a * invN, linearLight)
                }
            }
        }
        return PixelImage(dstW, dstH, out)
    }

    /** 可分离 Lanczos3 主体：带宽限制的分块处理，避免大图一次性分配中间缓冲。 */
    private fun lanczosCore(
        src: PixelImage,
        dstW: Int,
        dstH: Int,
        linearLight: Boolean,
    ): PixelImage {
        val sw = src.width
        val sh = src.height
        val ch = if (src.opaque) 3 else 4
        val tapsX = buildLanczosTaps(sw, dstW, sw.toDouble() / dstW)
        val tapsY = buildLanczosTaps(sh, dstH, sh.toDouble() / dstH)
        val outPixels = IntArray(dstW * dstH)
        val maxBufferFloats = 12L * 1024 * 1024 // ≈48MB 浮点缓冲上限

        var yOut = 0
        while (yOut < dstH) {
            var yEnd = yOut + 1
            var srcRowStart = tapsY[yOut].start
            var srcRowEnd = tapsY[yOut].end
            while (yEnd < dstH) {
                val s = min(srcRowStart, tapsY[yEnd].start)
                val e = max(srcRowEnd, tapsY[yEnd].end)
                val rows = e - s + 1
                if (dstW.toLong() * rows * ch > maxBufferFloats) break
                srcRowStart = s
                srcRowEnd = e
                yEnd++
            }
            val rows = srcRowEnd - srcRowStart + 1
            val buf = FloatArray(dstW * rows * ch)

            // 水平 pass：先把整行解码成线性（或 gamma）浮点，再做横向卷积
            val line = FloatArray(sw * ch)
            val rowStride = dstW * ch
            for (r in 0 until rows) {
                decodeRow(src, (srcRowStart + r) * sw, sw, ch, linearLight, line)
                val bufBase = r * rowStride
                for (x in 0 until dstW) {
                    val t = tapsX[x]
                    val ws = t.weights
                    var acc0 = 0f
                    var acc1 = 0f
                    var acc2 = 0f
                    var acc3 = 0f
                    var li = t.start * ch
                    for (k in ws.indices) {
                        val w = ws[k]
                        acc0 += line[li] * w
                        acc1 += line[li + 1] * w
                        acc2 += line[li + 2] * w
                        if (ch == 4) acc3 += line[li + 3] * w
                        li += ch
                    }
                    val base = bufBase + x * ch
                    buf[base] = acc0
                    buf[base + 1] = acc1
                    buf[base + 2] = acc2
                    if (ch == 4) buf[base + 3] = acc3
                }
            }

            // 垂直 pass
            val vStep = rowStride
            for (y in yOut until yEnd) {
                val t = tapsY[y]
                val ws = t.weights
                val localStart = t.start - srcRowStart
                val outBase = y * dstW
                for (x in 0 until dstW) {
                    var acc0 = 0f
                    var acc1 = 0f
                    var acc2 = 0f
                    var acc3 = 0f
                    var bi = (localStart * dstW + x) * ch
                    for (k in ws.indices) {
                        val w = ws[k]
                        acc0 += buf[bi] * w
                        acc1 += buf[bi + 1] * w
                        acc2 += buf[bi + 2] * w
                        if (ch == 4) acc3 += buf[bi + 3] * w
                        bi += vStep
                    }
                    outPixels[outBase + x] = if (ch == 3) {
                        packOpaque(acc0, acc1, acc2, linearLight)
                    } else {
                        packPremultiplied(acc0, acc1, acc2, acc3, linearLight)
                    }
                }
            }
            yOut = yEnd
        }
        return PixelImage(dstW, dstH, outPixels)
    }

    /** 一行源像素 → 浮点行（gamma 或线性；有 alpha 时预乘）。 */
    private fun decodeRow(
        src: PixelImage,
        offset: Int,
        count: Int,
        ch: Int,
        linearLight: Boolean,
        out: FloatArray,
    ) {
        val px = src.pixels
        var o = 0
        if (ch == 3) {
            for (i in 0 until count) {
                val p = px[offset + i]
                if (linearLight) {
                    out[o++] = Srgb.decode(p ushr 16 and 0xFF)
                    out[o++] = Srgb.decode(p ushr 8 and 0xFF)
                    out[o++] = Srgb.decode(p and 0xFF)
                } else {
                    out[o++] = (p ushr 16 and 0xFF) * INV255
                    out[o++] = (p ushr 8 and 0xFF) * INV255
                    out[o++] = (p and 0xFF) * INV255
                }
            }
        } else {
            for (i in 0 until count) {
                val p = px[offset + i]
                val a = (p ushr 24 and 0xFF) * INV255
                if (linearLight) {
                    out[o++] = Srgb.decode(p ushr 16 and 0xFF) * a
                    out[o++] = Srgb.decode(p ushr 8 and 0xFF) * a
                    out[o++] = Srgb.decode(p and 0xFF) * a
                } else {
                    out[o++] = (p ushr 16 and 0xFF) * INV255 * a
                    out[o++] = (p ushr 8 and 0xFF) * INV255 * a
                    out[o++] = (p and 0xFF) * INV255 * a
                }
                out[o++] = a
            }
        }
    }

    private class Taps(val start: Int, val weights: FloatArray) {
        val end: Int get() = start + weights.size - 1
    }

    /**
     * 构建缩放感知 Lanczos3 权重表。
     *
     * 关键：filterScale = max(1, 缩放倍数)，核支撑半径 = 3 * filterScale。
     * 缩小 3 倍时每个输出像素要汇总约 19 个源像素（±9），这就是抗锯齿的来源；
     * 若不做缩放感知（固定 ±3），缩小 3 倍时核根本覆盖不到一个输出像素对应的源区域，
     * 结果和点采样几乎一样差。
     */
    private fun buildLanczosTaps(srcLen: Int, dstLen: Int, scale: Double): Array<Taps> {
        val filterScale = max(1.0, scale)
        val support = 3.0 * filterScale
        return Array(dstLen) { i ->
            val center = (i + 0.5) * scale - 0.5
            var lo = ceil(center - support).toInt()
            var hi = floor(center + support).toInt()
            if (lo < 0) lo = 0
            if (hi > srcLen - 1) hi = srcLen - 1
            if (hi < lo) {
                val n = center.roundToInt().coerceIn(0, srcLen - 1)
                Taps(n, floatArrayOf(1f))
            } else {
                val w = FloatArray(hi - lo + 1)
                var sum = 0.0
                for (j in lo..hi) {
                    val wt = lanczos3Kernel((j - center) / filterScale)
                    w[j - lo] = wt.toFloat()
                    sum += wt
                }
                if (abs(sum) < 1e-9) {
                    val n = center.roundToInt().coerceIn(0, srcLen - 1)
                    Taps(n, floatArrayOf(1f))
                } else {
                    val inv = (1.0 / sum).toFloat()
                    for (k in w.indices) w[k] *= inv
                    Taps(lo, w)
                }
            }
        }
    }

    /** 标准 Lanczos3 核：sinc(x) * sinc(x/3)，|x| < 3。 */
    private fun lanczos3Kernel(x: Double): Double {
        val ax = abs(x)
        if (ax < 1e-9) return 1.0
        if (ax >= 3.0) return 0.0
        val px = PI * x
        return 3.0 * sin(px) * sin(px / 3.0) / (px * px)
    }

    // ---------------------------------------------------------------------
    // 打包/编码
    // ---------------------------------------------------------------------

    private fun encodeChannel(v: Float, linearLight: Boolean): Int =
        if (linearLight) Srgb.encode8(v) else clamp255(v * 255f)

    /** 不透明像素打包（rgb 为 0..1 的直通颜色）。 */
    private fun packOpaque(r: Float, g: Float, b: Float, linearLight: Boolean): Int =
        (0xFF shl 24) or
            (encodeChannel(r, linearLight) shl 16) or
            (encodeChannel(g, linearLight) shl 8) or
            encodeChannel(b, linearLight)

    /** 带 alpha 像素打包：rgb 为 0..1 的预乘颜色，输出直通 alpha ARGB。 */
    private fun packPremultiplied(
        r: Float,
        g: Float,
        b: Float,
        aRaw: Float,
        linearLight: Boolean,
    ): Int {
        val a = if (aRaw.isNaN()) 0f else aRaw.coerceIn(0f, 1f)
        if (a <= 1e-5f) return 0
        val inv = 1f / a
        return (clamp255(a * 255f) shl 24) or
            (encodeChannel(r * inv, linearLight) shl 16) or
            (encodeChannel(g * inv, linearLight) shl 8) or
            encodeChannel(b * inv, linearLight)
    }

    private fun clamp255(v: Float): Int {
        val x = v + 0.5f
        return if (x <= 0f) 0 else if (x >= 255f) 255 else x.toInt()
    }
}
