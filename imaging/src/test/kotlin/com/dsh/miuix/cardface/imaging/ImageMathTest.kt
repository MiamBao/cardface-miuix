package com.dsh.miuix.cardface.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImageMathTest {

    // ------------------------------------------------------------------
    // 裁剪
    // ------------------------------------------------------------------

    @Test
    fun cropAspectMatchesTargetRatio() {
        // 源图比目标“高”：按宽度铺满，裁上下
        val tall = TestImages.gradient(1000, 800)
        val a = ImageMath.cropAspect(tall, 960, 606)
        assertEquals(1000, a.width)
        assertEquals(631, a.height)
        assertEquals(960.0 / 606.0, a.width.toDouble() / a.height, 0.01)

        // 源图比目标“宽”：按高度铺满，裁左右
        val wide = TestImages.gradient(1600, 600)
        val b = ImageMath.cropAspect(wide, 960, 606)
        assertEquals(600, b.height)
        assertEquals(950, b.width)
        assertTrue(b.width < wide.width)

        // 已经是目标比例的图不裁
        val exact = TestImages.gradient(960, 606)
        val c = ImageMath.cropAspect(exact, 960, 606)
        assertEquals(960, c.width)
        assertEquals(606, c.height)
    }

    @Test
    fun cropAspectZoomAndCenter() {
        val src = TestImages.gradient(1600, 600)
        val zoomed = ImageMath.cropAspect(src, 960, 606, zoom = 2f)
        assertEquals(475, zoomed.width)
        assertEquals(300, zoomed.height)

        // centerX=0 靠左、centerX=1 靠右：渐变图左侧偏暗、右侧偏亮
        val left = ImageMath.cropAspect(src, 960, 606, centerX = 0f, centerY = 0.5f)
        val right = ImageMath.cropAspect(src, 960, 606, centerX = 1f, centerY = 0.5f)
        assertTrue("centerX=0 应取到更暗的左侧", left[0, 0].red8 < right[0, 0].red8)
        assertEquals("centerX=0 从左边缘开始", 0, left[0, 0].red8)
    }

    @Test
    fun cropRectClampsToImage() {
        val src = TestImages.gradient(100, 80)
        val out = ImageMath.cropRect(src, -10, -10, 50, 40)
        assertEquals(50, out.width)
        assertEquals(40, out.height)
        val clamped = ImageMath.cropRect(src, 90, 70, 999, 999)
        assertEquals(10, clamped.width)
        assertEquals(10, clamped.height)
    }

    // ------------------------------------------------------------------
    // 圆角
    // ------------------------------------------------------------------

    @Test
    fun roundCornersAntiAliasedWithoutBlackFringe() {
        val gray = TestImages.gray(200, 120, 200)
        val out = ImageMath.roundCorners(gray, 24f)
        assertEquals(200, out.width)
        assertEquals(120, out.height)

        // 四角完全透明
        assertEquals(0, out[0, 0].alpha8)
        assertEquals(0, out[out.width - 1, 0].alpha8)
        assertEquals(0, out[0, out.height - 1].alpha8)
        assertEquals(0, out[out.width - 1, out.height - 1].alpha8)
        // 中心与四边中点不透明
        assertEquals(255, out[100, 60].alpha8)
        assertEquals(255, out[0, 60].alpha8)
        assertEquals(255, out[100, 0].alpha8)

        // 圆角边缘要有半透明像素（4x4 超采样抗锯齿）
        var partial = 0
        for (p in out.pixels) {
            val a = p.alpha8
            if (a in 1..254) {
                partial++
                // 半透明像素的 RGB 必须保持原色（直通 alpha，不会出现黑边）
                assertEquals("半透明像素 red 不应改变", 200, p.red8)
                assertEquals("半透明像素 green 不应改变", 200, p.green8)
                assertEquals("半透明像素 blue 不应改变", 200, p.blue8)
            }
        }
        assertTrue("应有抗锯齿半透明像素，实际 $partial 个", partial > 40)
        assertTrue("半透明像素不应过多（只出现在圆角处），实际 $partial", partial < 1200)

        // 半径过大时自动夹紧，不崩
        val clamped = ImageMath.roundCorners(TestImages.gray(60, 40, 100), 999f)
        assertEquals(0, clamped[0, 0].alpha8)
    }

    // ------------------------------------------------------------------
    // 合成
    // ------------------------------------------------------------------

    @Test
    fun compositeOverUsesSrcOver() {
        val base = TestImages.gray(100, 80, 255)
        // 20x20 的覆盖层：中间 10x10 纯红不透明，其余全透明
        val overlay = PixelImage.filled(20, 20, 0)
        for (y in 5 until 15) {
            for (x in 5 until 15) overlay[x, y] = argb(255, 255, 0, 0)
        }
        overlay[5, 5] = argb(128, 0, 0, 255) // 50% 蓝
        val out = ImageMath.compositeOver(base, overlay, dx = 10, dy = 10)

        assertEquals("覆盖层外保持原样", 255, out[5, 5].red8)
        assertEquals("不透明覆盖", 255, out[16, 16].red8)
        assertEquals("不透明覆盖不残留底色", 0, out[16, 16].green8)
        // 50% 蓝叠在“不透明白底”上：结果仍不透明，颜色 = 蓝与白各半 (≈127,127,255)
        val mixed = out[15, 15]
        assertEquals("叠在不透明底上 alpha 仍为 255", 255, mixed.alpha8)
        assertTrue("蓝分量应较高：${mixed.blue8}", mixed.blue8 >= 250)
        assertTrue("红分量应约为白底与蓝的平均：${mixed.red8}", mixed.red8 in 120..136)
        assertTrue("绿分量应约为白底与蓝的平均：${mixed.green8}", mixed.green8 in 120..136)

        // 全透明覆盖层不应改变任何像素（验证标志位置/透明区叠加）
        val transparent = PixelImage.filled(20, 20, 0)
        val unchanged = ImageMath.compositeOver(base, transparent, 10, 10)
        assertArrayEquals(base.pixels, unchanged.pixels)

        // 越界不崩
        val shifted = ImageMath.compositeOver(base, overlay, dx = 95, dy = -5)
        assertEquals(100, shifted.width)
    }

    // ------------------------------------------------------------------
    // PNG 读写
    // ------------------------------------------------------------------

    @Test
    fun pngRoundTripLosslessWithAlpha() {
        val img = TestImages.halfTransparent(64, 48)
        val file = File.createTempFile("imaging-png-", ".png")
        try {
            ImageMath.savePng(img, file)
            assertTrue(file.length() > 0)
            val back = ImageMath.loadPng(file)
            assertEquals(img.width, back.width)
            assertEquals(img.height, back.height)
            assertArrayEquals("PNG 必须无损保留 alpha", img.pixels, back.pixels)
        } finally {
            file.delete()
        }
    }

    // ------------------------------------------------------------------
    // 真实样张
    // ------------------------------------------------------------------

    @Test
    fun realSampleLoadsAndCropsToCardAspect() {
        val sample = TestData.sample
        println("[sample] ${sample.width}x${sample.height}")
        assertTrue("样张应来自真实相机照片（宽度 ≥2000）", sample.width >= 2000)
        assertTrue("样张应有足够高度", sample.height >= 1200)
        assertTrue("样张应为不透明照片", sample.opaque)

        val card = ImageMath.cropAspect(sample, 960, 606)
        assertEquals(960.0 / 606.0, card.width.toDouble() / card.height, 0.01)
        assertTrue(card.width <= sample.width)
    }
}
