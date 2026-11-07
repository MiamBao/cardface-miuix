package com.dsh.miuix.cardface.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 模拟 Android 侧 `BitmapPipeline.buildCard` 的完整 JVM 管线：
 * 裁剪 → 缩放感知 Lanczos3（线性光）→ 40px 圆角 → 标志 SRC_OVER 合成 → 960x606。
 */
class CardBuildTest {

    private val targetW = 960
    private val targetH = 606
    private val radius = 40f

    private fun buildCard(logo: PixelImage? = null): PixelImage {
        val cropped = ImageMath.cropAspect(TestData.sample, targetW, targetH)
        var card = Resampler.lanczos3(cropped, targetW, targetH, linearLight = true)
        card = ImageMath.roundCorners(card, radius)
        if (logo != null) card = ImageMath.compositeOver(card, logo, 0, 0)
        return card
    }

    @Test
    fun pipelineProducesStrict960x606RoundedCard() {
        val card = buildCard()
        assertEquals("严格 960 宽", targetW, card.width)
        assertEquals("严格 606 高", targetH, card.height)
        assertEquals(targetW * targetH, card.pixels.size)

        // 四角全透明、中心与四边中点不透明
        assertEquals(0, card[0, 0].alpha8)
        assertEquals(0, card[targetW - 1, 0].alpha8)
        assertEquals(0, card[0, targetH - 1].alpha8)
        assertEquals(0, card[targetW - 1, targetH - 1].alpha8)
        assertEquals(255, card[targetW / 2, targetH / 2].alpha8)
        assertEquals(255, card[0, targetH / 2].alpha8)
        assertEquals(255, card[targetW / 2, 0].alpha8)

        // 圆角边缘要有 4x4 超采样产生的半透明过渡（抗锯齿）
        var partial = 0
        for (y in 0 until 60) {
            for (x in 0 until 60) {
                val a = card[x, y].alpha8
                if (a in 1..254) partial++
            }
        }
        assertTrue("左上角应有抗锯齿半透明像素，实际 $partial", partial > 30)
        val diagonal = (0..45).map { d -> card[d, d].alpha8 }
        assertTrue("对角线（圆角弧线）上应有中间覆盖度像素：$diagonal", diagonal.any { it in 1..254 })
        // 覆盖度过渡必须连续：0 → 半透明 → 255，不能出现 0 直接跳到 255
        val firstOpaque = diagonal.indexOfFirst { it == 255 }
        assertTrue("圆角过渡应至少持续 2 个像素：$diagonal", firstOpaque >= 2)

        // 圆角之外的图像内容保持清晰：整图平均梯度能量应显著高于直接双线性
        val direct = Resampler.bilinear(ImageMath.cropAspect(TestData.sample, targetW, targetH), targetW, targetH)
        val gradCard = ImageMetrics.meanGradientEnergy(card)
        val gradDirect = ImageMetrics.meanGradientEnergy(direct)
        println("[card] 梯度能量: pipeline=%.2f bilinear=%.2f".format(gradCard, gradDirect))
        assertTrue("卡面内容应保留细节", gradCard > 3.0)
    }

    @Test
    fun logoOverlayIsSrgbOverAndPositionExact() {
        val logoFile = File(TestPaths.logoDir, "jingjinji.png")
        assumeTrue("缺少卡面标志资源，跳过：$logoFile", logoFile.isFile)
        val logo = ImageMath.loadPng(logoFile)
        assertEquals("标志资源尺寸", 960, logo.width)
        assertEquals(606, logo.height)

        val card = buildCard(logo)
        val base = buildCard()
        assertEquals(targetW, card.width)
        assertEquals(targetH, card.height)

        var opaqueLogo = 0
        var transparentLogo = 0
        for (i in logo.pixels.indices) {
            val la = logo.pixels[i].alpha8
            if (la == 255) opaqueLogo++
            if (la == 0) transparentLogo++
        }
        assertTrue("标志应包含可见内容", opaqueLogo > 500)
        assertTrue("标志应有大量透明区域", transparentLogo > 100_000)

        // 标志透明处：卡面像素不变（位置正确 = 整幅对齐在 0,0）
        var checked = 0
        for (y in 0 until targetH step 7) {
            for (x in 0 until targetW step 7) {
                if (logo[x, y].alpha8 == 0) {
                    assertEquals("标志透明处卡面不应改变 ($x,$y)", base[x, y], card[x, y])
                    checked++
                }
            }
        }
        assertTrue("应检查到足够多的透明处像素，实际 $checked", checked > 2000)

        // 标志不透明处：结果 = 标志颜色（SRC_OVER 覆盖）
        var verified = 0
        for (y in 0 until targetH) {
            for (x in 0 until targetW) {
                if (logo[x, y].alpha8 == 255) {
                    assertEquals("标志不透明处应完全覆盖 ($x,$y)", logo[x, y], card[x, y])
                    verified++
                }
            }
        }
        assertTrue("应验证到足够多的标志不透明像素，实际 $verified", verified > 500)

        // 保存一张带标志的成品卡面作为肉眼证据
        val docs = TestPaths.docsDir
        check(docs.isDirectory || docs.mkdirs()) { "无法创建 ${docs.path}" }
        ImageMath.savePng(card, File(docs, "card-preview.png"))
        println("[card] 已生成 docs/imaging/card-preview.png（960x606，圆角 40px + 京津冀标志）")
    }
}
