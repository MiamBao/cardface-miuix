package com.dsh.miuix.cardface.imaging

import java.io.File

/** 仓库内路径定位（Gradle test 的工作目录是 imaging/ 模块目录）。 */
object TestPaths {
    val repoRoot: File = run {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        dir
    }

    /** 证据输出目录：docs/imaging（对比图与报告）。 */
    val docsDir: File get() = File(repoRoot, "docs/imaging")

    /** 真实样张：从 /sdcard/DCIM 挑的风景照原分辨率裁剪。 */
    val sampleFile: File get() = File(repoRoot, "imaging/src/test/resources/sample.png")

    /** 卡面标志资源目录（Android 模块 assets）。 */
    val logoDir: File get() = File(repoRoot, "app/src/main/assets/card_logo")
}

/** 共享测试数据（PNG 解码较慢，同一个 JVM 内只解一次）。 */
object TestData {
    val sample: PixelImage by lazy { ImageMath.loadPng(TestPaths.sampleFile) }
}

/** 合成测试图案。 */
object TestImages {

    /** 竖向 1px 细线 / 条纹（黑白各 1px），最容易被点采样折叠成锯齿。 */
    fun stripes(w: Int, h: Int, period: Int = 2): PixelImage {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                px[y * w + x] = if ((x % period) * 2 < period) BLACK else WHITE
            }
        }
        return PixelImage(w, h, px)
    }

    /** 1px 棋盘：二维最高频，缩小时最容易出现摩尔纹。 */
    fun checker(w: Int, h: Int): PixelImage {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                px[y * w + x] = if ((x + y) % 2 == 0) BLACK else WHITE
            }
        }
        return PixelImage(w, h, px)
    }

    /** 近似小字号文字边缘：1~3px 的横竖笔画。 */
    fun textLike(w: Int, h: Int): PixelImage {
        val px = IntArray(w * h) { WHITE }
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(h)) {
                for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(w)) {
                    px[y * w + x] = BLACK
                }
            }
        }
        var x = 2
        while (x < w - 4) {
            val strokeW = 1 + (x * 7 % 3)
            fill(x, 4, x + strokeW, h / 2)
            x += strokeW + 2
        }
        var y = h / 2 + 2
        while (y < h - 3) {
            fill(3, y, w / 2, y + 1 + (y % 2))
            y += 4
        }
        return PixelImage(w, h, px)
    }

    /** 左黑右白的竖直台阶边缘。 */
    fun stepEdge(w: Int, h: Int): PixelImage {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                px[y * w + x] = if (x < w / 2) BLACK else WHITE
            }
        }
        return PixelImage(w, h, px)
    }

    /** 均匀灰度图。 */
    fun gray(w: Int, h: Int, value: Int): PixelImage {
        val c = argb(255, value, value, value)
        return PixelImage(w, h, IntArray(w * h) { c })
    }

    /** 左半不透明红色、右半全透明的图，用于验证 alpha 处理。 */
    fun halfTransparent(w: Int, h: Int): PixelImage {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                px[y * w + x] = if (x < w / 2) argb(255, 255, 0, 0) else argb(0, 0, 0, 0)
            }
        }
        return PixelImage(w, h, px)
    }

    /** 半透明红色（alpha=128），验证均匀 alpha 区域缩放后 alpha 不变。 */
    fun translucentRed(w: Int, h: Int): PixelImage {
        val c = argb(128, 255, 0, 0)
        return PixelImage(w, h, IntArray(w * h) { c })
    }

    /** 平滑的 RGB 渐变，验证放大不出现块状。 */
    fun gradient(w: Int, h: Int): PixelImage {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = (x * 255 / (w - 1))
                val g = (y * 255 / (h - 1))
                val b = 128
                px[y * w + x] = argb(255, r, g, b)
            }
        }
        return PixelImage(w, h, px)
    }

    /** 高细节合成图（渐变 + 高频噪声），用于性能与预缩小路径。 */
    fun detailRich(w: Int, h: Int, seed: Long = 0x5DEECE66DL): PixelImage {
        val px = IntArray(w * h)
        var s = seed
        for (y in 0 until h) {
            for (x in 0 until w) {
                s = s * 6364136223846793005L + 1442695040888963407L
                val n = ((s ushr 33).toInt() and 0xFF) shr 2
                val r = ((x * 255 / w) + n).coerceIn(0, 255)
                val g = ((y * 255 / h) + n).coerceIn(0, 255)
                val b = (((x + y) * 127 / (w + h)) + n).coerceIn(0, 255)
                px[y * w + x] = argb(255, r, g, b)
            }
        }
        return PixelImage(w, h, px)
    }

    const val BLACK: Int = 0xFF000000.toInt()
    const val WHITE: Int = 0xFFFFFFFF.toInt()
}
