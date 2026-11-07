package com.dsh.miuix.cardface.imaging

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 测试/报告用的客观指标（不参与产品运行时代码）。
 */
object ImageMetrics {

    /** Rec.709 亮度（0..255）。 */
    fun luma(c: Int): Double =
        0.2126 * ((c ushr 16) and 0xFF) + 0.7152 * ((c ushr 8) and 0xFF) + 0.0722 * (c and 0xFF)

    fun meanLuma(img: PixelImage): Double {
        var sum = 0.0
        for (p in img.pixels) sum += luma(p)
        return sum / img.pixels.size
    }

    /**
     * 平均梯度能量：内部像素的中心差分梯度幅值均值。
     * 对「本该均匀的细节图案」来说，这个值越低说明走样/摩尔纹越少。
     */
    fun meanGradientEnergy(img: PixelImage, inset: Int = 2): Double {
        var sum = 0.0
        var n = 0
        val w = img.width
        val h = img.height
        for (y in inset until h - inset) {
            for (x in inset until w - inset) {
                val gx = luma(img[x + 1, y]) - luma(img[x - 1, y])
                val gy = luma(img[x, y + 1]) - luma(img[x, y - 1])
                sum += sqrt(gx * gx + gy * gy)
                n++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    /** 全图/内部区域亮度的标准差：图案本应均匀时，越小越干净。 */
    fun stdDevLuma(img: PixelImage, inset: Int = 8): Double {
        var sum = 0.0
        var sum2 = 0.0
        var n = 0
        for (y in inset until img.height - inset) {
            for (x in inset until img.width - inset) {
                val v = luma(img[x, y])
                sum += v
                sum2 += v * v
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sqrt((sum2 / n - mean * mean).coerceAtLeast(0.0))
    }

    /**
     * 块内亮度均值的标准差（默认 8x8 块）。
     * 对棋盘/细线图案，近邻/双线性的摩尔纹表现为大尺度的明暗条纹，这个值会明显偏高。
     */
    fun blockMeanStd(img: PixelImage, block: Int = 8, inset: Int = 8): Double {
        val means = ArrayList<Double>()
        val w = img.width
        val h = img.height
        var by = inset
        while (by + block <= h - inset) {
            var bx = inset
            while (bx + block <= w - inset) {
                var sum = 0.0
                for (y in by until by + block) {
                    for (x in bx until bx + block) sum += luma(img[x, y])
                }
                means.add(sum / (block * block))
                bx += block
            }
            by += block
        }
        if (means.isEmpty()) return 0.0
        val mean = means.average()
        return sqrt(means.sumOf { (it - mean) * (it - mean) } / means.size)
    }

    /**
     * 平均亮度偏差：meanLuma(a) - meanLuma(b)。
     * 直方图/亮度是否整体偏移，用来检查线性光开关造成的「变暗」。
     */
    fun meanLumaDelta(a: PixelImage, b: PixelImage): Double = meanLuma(a) - meanLuma(b)

    /**
     * 灰度 SSIM（结构相似度，8x8 窗口、步长 4，非重叠近似标准实现）。
     * 同时惩罚模糊与走样，用来比较缩放结果与理想低通参考的感知接近程度。
     */
    fun ssim(a: PixelImage, b: PixelImage, window: Int = 8, step: Int = 4): Double {
        require(a.width == b.width && a.height == b.height) { "尺寸不同无法比较 SSIM" }
        val c1 = (0.01 * 255.0) * (0.01 * 255.0)
        val c2 = (0.03 * 255.0) * (0.03 * 255.0)
        var sum = 0.0
        var count = 0
        var y = 0
        while (y + window <= a.height) {
            var x = 0
            while (x + window <= a.width) {
                var sa = 0.0
                var sb = 0.0
                var saa = 0.0
                var sbb = 0.0
                var sab = 0.0
                var k = 0
                for (yy in y until y + window) {
                    for (xx in x until x + window) {
                        val va = luma(a[xx, yy])
                        val vb = luma(b[xx, yy])
                        sa += va
                        sb += vb
                        saa += va * va
                        sbb += vb * vb
                        sab += va * vb
                        k++
                    }
                }
                val n = k.toDouble()
                val ma = sa / n
                val mb = sb / n
                val va = saa / n - ma * ma
                val vb = sbb / n - mb * mb
                val cov = sab / n - ma * mb
                sum += ((2 * ma * mb + c1) * (2 * cov + c2)) /
                    ((ma * ma + mb * mb + c1) * (va + vb + c2))
                count++
                x += step
            }
            y += step
        }
        return if (count == 0) 1.0 else sum / count
    }

    /** 两张同尺寸图的 PSNR（RGB 通道，255 满量程）。 */
    fun psnr(a: PixelImage, b: PixelImage): Double {
        require(a.width == b.width && a.height == b.height) { "尺寸不同无法比较 PSNR" }
        var sse = 0.0
        val n = a.pixels.size
        for (i in 0 until n) {
            val pa = a.pixels[i]
            val pb = b.pixels[i]
            val dr = ((pa ushr 16) and 0xFF) - ((pb ushr 16) and 0xFF)
            val dg = ((pa ushr 8) and 0xFF) - ((pb ushr 8) and 0xFF)
            val db = (pa and 0xFF) - (pb and 0xFF)
            sse += (dr * dr + dg * dg + db * db).toDouble()
        }
        val mse = sse / (n * 3.0)
        return if (mse <= 1e-12) 99.0 else 10.0 * log10(255.0 * 255.0 / mse)
    }
}
