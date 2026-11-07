package com.dsh.miuix.cardface.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 缩放核心的质量/尺寸/alpha 测试。
 *
 * 对照关系：
 * - [Resampler.bilinear] = 原版 `Bitmap.createScaledBitmap(..., true)` 的等效行为（2x2 采样）；
 * - [Resampler.nearest]  = 最差的点采样；
 * - [Resampler.lanczos3] = 新版：缩放感知 + 线性光。
 */
class ResamplerTest {

    // ------------------------------------------------------------------
    // 尺寸 / alpha
    // ------------------------------------------------------------------

    @Test
    fun downscaleProducesExactSize() {
        val src = TestImages.detailRich(801, 601)
        for ((w, h) in listOf(320 to 240, 160 to 120, 1 to 1)) {
            val out = Resampler.lanczos3(src, w, h)
            assertEquals("width", w, out.width)
            assertEquals("height", h, out.height)
            assertEquals("pixels", w * h, out.pixels.size)
        }
        // 三种算法缩小后尺寸都必须精确
        for (w in listOf(1, 7, 64)) {
            assertEquals(w, Resampler.bilinear(src, w, 13).width)
            assertEquals(w, Resampler.nearest(src, w, 13).width)
            assertEquals(w, Resampler.boxAverage(src, w, 13).width)
        }
    }

    @Test
    fun alphaChannelPreservedAndNoBlackFringe() {
        val src = TestImages.halfTransparent(400, 300)
        val out = Resampler.lanczos3(src, 100, 75)
        assertEquals("左半不透明", 255, out[2, 2].alpha8)
        assertEquals("右半全透明", 0, out[97, 72].alpha8)

        // 交界处必须有半透明像素（抗锯齿），且颜色仍是红、不能发黑
        val edge = out[50, 37]
        assertTrue("交界 alpha=${edge.alpha8} 应在 (0,255) 之间", edge.alpha8 in 1..254)
        assertTrue("半透明边缘不能带黑边，red=${edge.red8}", edge.red8 >= 250)
        assertEquals("透明区域 green/blue 应为 0", 0, edge.green8)

        // 均匀 alpha=128 的纯色图，缩放后 alpha 不变
        val uniform = Resampler.lanczos3(TestImages.translucentRed(200, 150), 50, 37)
        for (p in uniform.pixels) {
            assertTrue("alpha=${p.alpha8} 应保持 128±2", abs(p.alpha8 - 128) <= 2)
            assertTrue("red=${p.red8} 应保持纯红", p.red8 >= 250)
        }

        // 双线性路径也要保留 alpha
        val bil = Resampler.bilinear(src, 100, 75)
        assertEquals(255, bil[2, 2].alpha8)
        assertEquals(0, bil[97, 72].alpha8)
    }

    @Test
    fun upscaleCreatesSmoothTransitionsNotNearestBlocks() {
        val step = TestImages.stepEdge(120, 90)
        val smooth = Resampler.lanczos3(step, 480, 360)
        val blocky = Resampler.nearest(step, 480, 360)

        // 台阶边缘放大 4x：Lanczos3 在边缘处产生多种过渡值，最近邻只有 0/255 两档
        val smoothValues = distinctLuma(smooth)
        val blockyValues = distinctLuma(blocky)
        assertTrue("Lanczos3 放大应产生平滑过渡，实际只有 $smoothValues 种亮度", smoothValues > 6)
        assertTrue("最近邻放大只有块状，实际有 $blockyValues 种亮度", blockyValues <= 2)
        // 边缘横向过渡宽度：Lanczos3 覆盖多个输出像素，最近邻为 0
        val smoothEdgeCols = intermediateColumns(smooth)
        val blockyEdgeCols = intermediateColumns(blocky)
        println("[upscale] 过渡列数: lanczos=$smoothEdgeCols nearest=$blockyEdgeCols")
        assertTrue("Lanczos3 边缘过渡应有多个像素，实际 $smoothEdgeCols", smoothEdgeCols >= 5)
        assertTrue("最近邻不应有中间过渡像素，实际 $blockyEdgeCols", blockyEdgeCols == 0)

        // 平滑输出的相邻差远小于最近邻的硬跳变
        val smoothMaxDelta = maxAdjacentDelta(smooth)
        val blockyMaxDelta = maxAdjacentDelta(blocky)
        assertTrue(
            "平滑最大相邻差=$smoothMaxDelta，最近邻=$blockyMaxDelta",
            smoothMaxDelta * 2 < blockyMaxDelta,
        )
    }

    // ------------------------------------------------------------------
    // 抗锯齿：细线 / 棋盘 / 文字边缘
    // ------------------------------------------------------------------

    @Test
    fun thinLinesAliasingMuchLowerThanBilinear() {
        val src = TestImages.stripes(1600, 600)
        val dstW = 477
        val dstH = 179
        val reference = Resampler.boxAverage(src, dstW, dstH, linearLight = true)
        val lanczos = Resampler.lanczos3(src, dstW, dstH, linearLight = true)
        val bilinear = Resampler.bilinear(src, dstW, dstH)
        val nearest = Resampler.nearest(src, dstW, dstH)

        val stdL = ImageMetrics.stdDevLuma(lanczos)
        val stdB = ImageMetrics.stdDevLuma(bilinear)
        val stdN = ImageMetrics.stdDevLuma(nearest)
        val gradL = ImageMetrics.meanGradientEnergy(lanczos)
        val gradB = ImageMetrics.meanGradientEnergy(bilinear)
        val psnrL = ImageMetrics.psnr(lanczos, reference)
        val psnrB = ImageMetrics.psnr(bilinear, reference)

        println(
            "[thin-lines] std: lanczos=%.2f bilinear=%.2f nearest=%.2f | grad: %.2f / %.2f | PSNR: %.1f / %.1f dB"
                .format(stdL, stdB, stdN, gradL, gradB, psnrL, psnrB),
        )

        assertTrue("细线缩小后 Lanczos std=$stdL 应接近均匀（< 8）", stdL < 8.0)
        assertTrue("Lanczos std=$stdL 应远小于 bilinear=$stdB", stdL < stdB * 0.25)
        assertTrue("Lanczos std=$stdL 应远小于 nearest=$stdN", stdL < stdN * 0.25)
        assertTrue("Lanczos 梯度能量=$gradL 应远小于 bilinear=$gradB", gradL < gradB * 0.25)
        assertTrue("Lanczos PSNR=$psnrL 应明显高于 bilinear=$psnrB", psnrL > psnrB + 6.0)
    }

    @Test
    fun checkerboardMoireMuchLowerThanBilinear() {
        val src = TestImages.checker(1600, 600)
        val dstW = 477
        val dstH = 179
        val lanczos = Resampler.lanczos3(src, dstW, dstH, linearLight = true)
        val bilinear = Resampler.bilinear(src, dstW, dstH)
        val nearest = Resampler.nearest(src, dstW, dstH)

        val stdL = ImageMetrics.stdDevLuma(lanczos)
        val stdB = ImageMetrics.stdDevLuma(bilinear)
        val stdN = ImageMetrics.stdDevLuma(nearest)
        val blockL = ImageMetrics.blockMeanStd(lanczos)
        val blockB = ImageMetrics.blockMeanStd(bilinear)

        println(
            "[checkerboard] std: lanczos=%.2f bilinear=%.2f nearest=%.2f | 8x8块均值std: %.2f / %.2f"
                .format(stdL, stdB, stdN, blockL, blockB),
        )

        assertTrue("棋盘缩小后 Lanczos std=$stdL 应接近均匀（< 8）", stdL < 8.0)
        assertTrue("Lanczos std=$stdL 应远小于 bilinear=$stdB", stdL < stdB * 0.25)
        assertTrue("Lanczos std=$stdL 应远小于 nearest=$stdN", stdL < stdN * 0.25)
        assertTrue("Lanczos 块状摩尔纹=$blockL 应远小于 bilinear=$blockB", blockL < blockB * 0.25)
    }

    @Test
    fun textEdgesStayClean() {
        val src = TestImages.textLike(1200, 480)
        val lanczos = Resampler.lanczos3(src, 360, 144, linearLight = true)
        val bilinear = Resampler.bilinear(src, 360, 144)
        // 文字图案缩小后，Lanczos 的梯度能量（走样造成的假高频）应更低
        val gradL = ImageMetrics.meanGradientEnergy(lanczos)
        val gradB = ImageMetrics.meanGradientEnergy(bilinear)
        println("[text-edges] 梯度能量: lanczos=%.2f bilinear=%.2f".format(gradL, gradB))
        assertTrue("文字边缘：Lanczos 梯度能量=$gradL 应低于 bilinear=$gradB", gradL < gradB)
    }

    // ------------------------------------------------------------------
    // 线性光
    // ------------------------------------------------------------------

    @Test
    fun linearLightOnlyChangesBrightnessTransition() {
        val src = TestImages.stripes(800, 300)
        val linear = Resampler.lanczos3(src, 200, 75, linearLight = true)
        val gamma = Resampler.lanczos3(src, 200, 75, linearLight = false)
        assertEquals(linear.width, gamma.width)
        assertEquals(linear.height, gamma.height)

        val meanLinear = ImageMetrics.meanLuma(linear)
        val meanGamma = ImageMetrics.meanLuma(gamma)
        println("[linear-light] 黑白细线平均亮度: linear=%.1f gamma=%.1f".format(meanLinear, meanGamma))
        // 黑白各半，线性光平均的正确 sRGB 亮度 ≈ 188；在 sRGB 空间直接平均 ≈ 127
        assertTrue("线性光平均亮度 $meanLinear 应明显高于 gamma $meanGamma", meanLinear > meanGamma + 25.0)

        // 纯色区域两种模式结果一致（只影响过渡，不该整体偏色）
        val graySrc = TestImages.gray(400, 300, 128)
        val gLinear = Resampler.lanczos3(graySrc, 100, 75, linearLight = true)
        val gGamma = Resampler.lanczos3(graySrc, 100, 75, linearLight = false)
        for (i in gLinear.pixels.indices) {
            assertTrue(
                "纯色不应受线性光开关影响：${gLinear.pixels[i]} vs ${gGamma.pixels[i]}",
                abs(gLinear.pixels[i].red8 - gGamma.pixels[i].red8) <= 1,
            )
        }
    }

    // ------------------------------------------------------------------
    // box 预缩小 / 大倍率
    // ------------------------------------------------------------------

    @Test
    fun boxAverageEqualsManualAreaAverage() {
        val w = 6
        val h = 4
        val px = IntArray(w * h) { i -> argb(255, (i * 10).coerceAtMost(255), (i * 10).coerceAtMost(255), (i * 10).coerceAtMost(255)) }
        val src = PixelImage(w, h, px)
        val out = Resampler.boxAverage(src, 2, 2)
        val expected = ArrayList<Double>()
        for (by in 0 until 2) {
            for (bx in 0 until 2) {
                var sum = 0.0
                for (y in by * 2 until by * 2 + 2) {
                    for (x in bx * 3 until bx * 3 + 3) sum += (y * w + x) * 10.0
                }
                expected.add(sum / 6.0)
            }
        }
        for (i in expected.indices) {
            val e = (expected[i] + 0.5).toInt()
            assertEquals("box 第 $i 块", e, out.pixels[i].red8)
        }
    }

    @Test
    fun eightTimesDownscaleViaPreShrinkStaysClean() {
        val src = TestImages.checker(1920, 1212)
        val out = Resampler.lanczos3(src, 240, 151, linearLight = true)
        val nearest = Resampler.nearest(src, 240, 151)
        val stdL = ImageMetrics.stdDevLuma(out)
        val stdN = ImageMetrics.stdDevLuma(nearest)
        println("[8x-checker] std: lanczos=%.2f nearest=%.2f".format(stdL, stdN))
        assertTrue("8 倍缩小（box 预缩小 + Lanczos）应接近均匀，实际 std=$stdL", stdL < 6.0)
        assertTrue("应远小于点采样 std=$stdN", stdL < stdN * 0.1)
    }

    // ------------------------------------------------------------------
    // 性能 / 大图保护
    // ------------------------------------------------------------------

    @Test
    fun twelveMegapixelLanczosPerformance() {
        val src = TestImages.detailRich(4000, 3000)
        // 预热 JIT
        Resampler.lanczos3(src, 240, 151)
        var best = Long.MAX_VALUE
        var out: PixelImage? = null
        repeat(3) {
            val t0 = System.nanoTime()
            out = Resampler.lanczos3(src, 960, 606)
            val ms = (System.nanoTime() - t0) / 1_000_000
            best = minOf(best, ms)
        }
        assertEquals(960, out!!.width)
        assertEquals(606, out.height)
        val maxHeapMb = Runtime.getRuntime().maxMemory() / 1024 / 1024
        println("[perf] 12MP 4000x3000 -> 960x606 lanczos3(linearLight=true): best=${best}ms, maxHeap=${maxHeapMb}MB")
        assertTrue("12MP 缩放耗时 ${best}ms 超出 3000ms 上限", best < 3000)

        val t1 = System.nanoTime()
        val bilinear = Resampler.bilinear(src, 960, 606)
        val bilinearMs = (System.nanoTime() - t1) / 1_000_000
        assertEquals(960, bilinear.width)
        println("[perf] 同尺寸 bilinear(原版等效) 单次=${bilinearMs}ms")
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun distinctLuma(img: PixelImage): Int {
        val set = HashSet<Int>()
        for (y in img.height / 3 until img.height * 2 / 3) {
            for (x in 2 until img.width - 2) set.add(ImageMetrics.luma(img[x, y]).roundToInt())
        }
        return set.size
    }

    /** 中间行里亮度既不接近 0 也不接近 255 的列数（边缘过渡宽度）。 */
    private fun intermediateColumns(img: PixelImage): Int {
        val y = img.height / 2
        var count = 0
        for (x in 0 until img.width) {
            val l = ImageMetrics.luma(img[x, y])
            if (l > 6.0 && l < 249.0) count++
        }
        return count
    }

    private fun maxAdjacentDelta(img: PixelImage): Int {
        var max = 0
        for (y in img.height / 3 until img.height * 2 / 3) {
            var prev = ImageMetrics.luma(img[0, y])
            for (x in 1 until img.width) {
                val v = ImageMetrics.luma(img[x, y])
                max = maxOf(max, abs((v - prev).roundToInt()))
                prev = v
            }
        }
        return max
    }
}
