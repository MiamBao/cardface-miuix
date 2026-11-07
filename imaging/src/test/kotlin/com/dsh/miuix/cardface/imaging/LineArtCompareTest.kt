package com.dsh.miuix.cardface.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.sqrt

/**
 * 线稿/文字类卡面的 A/B 对比（用户卡面大量是二次元线稿，细线最容易暴露点采样锯齿）。
 *
 * 生成 3000x1894 的黑白线稿测试图（细线网 / 斜线 / 1px 棋盘 / 圆点阵 / 大小字号中文），
 * 用 nearest / bilinear / lanczos3 缩到 960x606，再把同一区域 4x 最近邻放大拼成
 * `docs/imaging/compare-lineart.png`，并把指标写进 `docs/imaging/metrics-lineart.md`。
 */
class LineArtCompareTest {

    private val srcW = 3000
    private val srcH = 1894
    private val targetW = 960
    private val targetH = 606

    /**
     * 局部对比区域（960x606 输出坐标，4x 最近邻放大）：
     * - 第 1 行：1px 细线网格——看「线宽是否均匀、有没有断线/黑斑」；
     * - 第 2 行：密集斜排线——看斜线边缘的阶梯/断线（线稿最典型）。
     */
    private val cropRegions = listOf(
        intArrayOf(30, 388, 200, 120),
        intArrayOf(385, 15, 200, 120),
    )
    private val zoom = 4

    @Test
    fun generateLineArtComparison() {
        val pattern = buildLineArt()
        val reference = Resampler.boxAverage(pattern, targetW, targetH, linearLight = true)

        val methods = listOf(
            "nearest" to Resampler.nearest(pattern, targetW, targetH),
            "bilinear" to Resampler.bilinear(pattern, targetW, targetH),
            "lanczos3" to Resampler.lanczos3(pattern, targetW, targetH, linearLight = true),
        )
        for ((_, img) in methods) {
            assertEquals(targetW, img.width)
            assertEquals(targetH, img.height)
        }

        val psnr = LinkedHashMap<String, Double>()
        for ((name, img) in methods) {
            val p = ImageMetrics.psnr(img, reference)
            psnr[name] = p
            println("[lineart] %-9s PSNR=%.2f dB".format(name, p))
        }
        assertTrue(
            "线稿：Lanczos PSNR=${psnr["lanczos3"]} 应明显高于 bilinear=${psnr["bilinear"]}",
            psnr.getValue("lanczos3") > psnr.getValue("bilinear") + 2.0,
        )
        assertTrue(
            "线稿：Lanczos PSNR 应明显高于 nearest",
            psnr.getValue("lanczos3") > psnr.getValue("nearest") + 4.0,
        )

        // 1px 棋盘块（缩放到输出后应接近均匀灰，走样会变成斑点噪声）
        val checkerOut = Rect(13, 525, 83, 64)
        val checkerStd = methods.associate { (name, img) ->
            name to ImageMetrics.stdDevLuma(ImageMath.cropRect(img, checkerOut.x, checkerOut.y, checkerOut.right, checkerOut.bottom))
        }
        println("[lineart] 1px 棋盘块 std: " + checkerStd.entries.joinToString { "${it.key}=%.1f".format(it.value) })
        assertTrue(
            "线稿棋盘块：Lanczos std=${checkerStd["lanczos3"]} 应远小于 bilinear=${checkerStd["bilinear"]}",
            checkerStd.getValue("lanczos3") < checkerStd.getValue("bilinear") * 0.5,
        )

        // 每个区域裁同一块，4x 最近邻放大后三列并排；多行 = 多种线稿内容
        val rows = cropRegions.map { (x, y, w, h) ->
            val panels = methods.map { (_, img) ->
                val crop = ImageMath.cropRect(img, x, y, x + w, y + h)
                Resampler.nearest(crop, w * zoom, h * zoom)
            }
            sideBySide(panels)
        }
        val sheet = stackVertically(rows)
        val docs = TestPaths.docsDir
        check(docs.isDirectory || docs.mkdirs()) { "无法创建 ${docs.path}" }
        // 完整线稿对比（三张 960x606 并排），方便定位局部区域
        ImageMath.savePng(sideBySide(methods.map { it.second }), File(docs, "compare-lineart-full.png"))
        val outFile = File(docs, "compare-lineart.png")
        ImageMath.savePng(sheet, outFile)
        println("[lineart] 已生成 docs/imaging/compare-lineart.png (${sheet.width}x${sheet.height})，局部 4x")

        // 指标文件
        val md = """
            # 线稿/文字类卡面指标（自动生成，勿手改）

            由 `:imaging:test` 的 `LineArtCompareTest` 生成，源图是合成的 3000x1894 黑白线稿测试图
            （细线网 / 斜线 / 1px 棋盘 / 圆点阵 / 大小字号中文），3.125x 缩小到 960x606。

            | 方法 | PSNR vs 理想低通 (dB) | 1px 棋盘块亮度 std | 说明 |
            | --- | ---: | ---: | --- |
            | nearest | %.2f | %.2f | 点采样，细线直接断成虚线 |
            | bilinear | %.2f | %.2f | 原版等效 2x2 采样，锯齿/摩尔纹明显 |
            | lanczos3 | %.2f | %.2f | 新版：缩放感知 + 线性光 |

            - 对比图：`compare-lineart.png`（两行 x 三列，每格 800x480，同一区域 4x 最近邻放大，左→右 = nearest | bilinear | lanczos3）
              - 第 1 行：1px 细线网格，输出坐标 (x=30, y=388) 200x120
              - 第 2 行：密集斜排线，输出坐标 (x=385, y=15) 200x120
            - 另有 `compare-lineart-full.png`：三张完整 960x606 并排
        """.trimIndent().format(
            psnr["nearest"]!!, checkerStd["nearest"]!!,
            psnr["bilinear"]!!, checkerStd["bilinear"]!!,
            psnr["lanczos3"]!!, checkerStd["lanczos3"]!!,
        )
        File(docs, "metrics-lineart.md").writeText(md)
        assertTrue(outFile.isFile)
    }

    // ------------------------------------------------------------------
    // 合成线稿测试图
    // ------------------------------------------------------------------

    private fun buildLineArt(): PixelImage {
        val img = BufferedImage(srcW, srcH, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = Color.WHITE
        g.fillRect(0, 0, srcW, srcH)
        g.color = Color.BLACK

        // 1) 顶部左侧：不同间距的 1px 竖线带（网点/排线）
        var x = 40
        for (pitch in intArrayOf(3, 4, 5, 6, 8, 12)) {
            val bandW = 210
            var i = 0
            while (i < bandW) {
                g.fillRect(x + i, 60, 1, 520)
                i += pitch
            }
            x += bandW + 18
        }

        // 2) 左下：横线 + 网格纸（1px）
        var yy = 640
        for (pitch in intArrayOf(4, 6, 9)) {
            var xx = 40
            while (xx < 1060) {
                g.fillRect(xx, yy, 1, 300)
                xx += pitch
            }
            var y = yy
            while (y < yy + 300) {
                g.fillRect(40, y, 1020, 1)
                y += pitch
            }
            yy += 330
        }

        // 3) 斜线：2px 细斜线（15°/30°/45°/60°/75°）+ 1px 密集斜排线
        g.stroke = BasicStroke(2f)
        val diagTop = 60
        val diagBottom = 560
        for (deg in intArrayOf(15, 30, 45, 60, 75)) {
            val rad = Math.toRadians(deg.toDouble())
            val cx = 1650.0
            val cy = (diagTop + diagBottom) / 2.0
            val half = 300.0
            val dx = Math.cos(rad) * half
            val dy = Math.sin(rad) * half
            val line = Path2D.Double()
            line.moveTo(cx - dx, cy - dy)
            line.lineTo(cx + dx, cy + dy)
            g.draw(line)
        }
        g.stroke = BasicStroke(1f)
        var k = 0
        while (k < 40) {
            val off = 1180 + k * 9
            g.drawLine(off, diagTop, off + 420, diagBottom)
            k++
        }

        // 4) 右上：大小字号中文（细笔画最容易被点采样打碎）
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 112)
        g.drawString("卡面工坊", 1180, 780)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 64)
        g.drawString("960x606 高清降采样", 1180, 900)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 58)
        g.drawString("原版锯齿 vs Lanczos3", 1180, 1010)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 40)
        g.drawString("细线 0.5px 也会被点采样打断", 1180, 1100)

        // 5) 右下：同心圆 + 内部斜排线（接近二次元线稿）
        g.stroke = BasicStroke(2f)
        val ccx = 2420
        val ccy = 1330
        for (r in intArrayOf(50, 110, 170, 230, 290)) {
            g.drawOval(ccx - r, ccy - r, r * 2, r * 2)
        }
        g.stroke = BasicStroke(1f)
        var h = -280
        while (h <= 280) {
            g.drawLine(ccx - 240, ccy + h, ccx + 240, ccy + h + 320)
            h += 9
        }

        // 6) 中下：正弦曲线束（细曲线边缘）
        val wave = Path2D.Double()
        var wx = 700
        wave.moveTo(610.0, 1450.0 + Math.sin(0.0) * 60)
        while (wx <= 1600) {
            wave.lineTo(wx.toDouble(), 1450.0 + Math.sin(wx / 55.0) * 60)
            wx += 6
        }
        g.stroke = BasicStroke(1.5f)
        g.draw(wave)
        val wave2 = Path2D.Double()
        wave2.moveTo(610.0, 1560.0)
        wx = 600
        while (wx <= 1600) {
            wave2.lineTo(wx.toDouble(), 1560.0 + Math.sin(wx / 30.0) * 40)
            wx += 6
        }
        g.draw(wave2)

        // 7) 底部左侧：1px 棋盘 + 1px 圆点阵
        for (y in 1640 until 1840) {
            for (xx in 40 until 300) {
                if ((xx + y) % 2 == 0) g.fillRect(xx, y, 1, 1)
            }
        }
        for (y in 1640 until 1840 step 9) {
            for (xx in 340 until 700 step 9) {
                g.fillOval(xx, y, 3, 3)
            }
        }

        g.dispose()
        val px = IntArray(srcW * srcH)
        img.getRGB(0, 0, srcW, srcH, px, 0, srcW)
        // 白底黑线，直接设为完全不透明
        for (i in px.indices) px[i] = px[i] or 0xFF000000.toInt()
        return PixelImage(srcW, srcH, px)
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        val right: Int get() = x + w
        val bottom: Int get() = y + h
    }

    private fun stackVertically(rows: List<PixelImage>): PixelImage {
        val gap = 4
        val w = rows.maxOf { it.width }
        val h = rows.sumOf { it.height } + gap * (rows.size - 1)
        val out = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        var yOff = 0
        for (row in rows) {
            for (y in 0 until row.height) {
                System.arraycopy(row.pixels, y * row.width, out, (yOff + y) * w, row.width)
            }
            yOff += row.height + gap
        }
        return PixelImage(w, h, out)
    }

    private fun sideBySide(images: List<PixelImage>): PixelImage {
        val gap = 4
        val w = images.sumOf { it.width } + gap * (images.size - 1)
        val h = images.maxOf { it.height }
        val out = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        var xOff = 0
        for (img in images) {
            for (y in 0 until img.height) {
                System.arraycopy(img.pixels, y * img.width, out, y * w + xOff, img.width)
            }
            xOff += img.width + gap
        }
        return PixelImage(w, h, out)
    }
}
