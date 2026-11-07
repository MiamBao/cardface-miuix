package com.dsh.miuix.cardface.imaging

/**
 * ARGB_8888 像素图，纯 JVM 实现（不依赖 Android，也不依赖 AWT）。
 *
 * 内存约定：一个像素 4 字节，一张 12MP 图约 48MB。大图请先用
 * [Resampler.boxAverage] / [Resampler.lanczos3] 缩小，再做后续处理。
 *
 * 此类不是线程安全的可变容器：像素数组是共享的，需要独立副本时用 [copy]。
 */
data class PixelImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "尺寸必须为正：${width}x$height" }
        require(pixels.size == width * height) {
            "pixels.size=${pixels.size} 与 ${width}x$height 不匹配"
        }
    }

    /** 是否完全不透明（全 alpha=255）。true 时缩放/合成走更快、更精确的 3 通道路径。 */
    val opaque: Boolean by lazy {
        var i = 0
        while (i < pixels.size) {
            if ((pixels[i] ushr 24) != 0xFF) return@lazy false
            i++
        }
        true
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    operator fun set(x: Int, y: Int, argb: Int) {
        pixels[y * width + x] = argb
    }

    fun copy(): PixelImage = PixelImage(width, height, pixels.copyOf())

    /** 截取子图（不拷贝时会被裁剪到源图范围内；结果始终至少 1x1）。 */
    fun subImage(x0: Int, y0: Int, w: Int, h: Int): PixelImage {
        val cw = w.coerceAtLeast(1)
        val ch = h.coerceAtLeast(1)
        val left = x0.coerceIn(0, width - 1)
        val top = y0.coerceIn(0, height - 1)
        val right = (left + cw).coerceAtMost(width)
        val bottom = (top + ch).coerceAtMost(height)
        val outW = right - left
        val outH = bottom - top
        val out = IntArray(outW * outH)
        for (y in 0 until outH) {
            System.arraycopy(pixels, (top + y) * width + left, out, y * outW, outW)
        }
        return PixelImage(outW, outH, out)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PixelImage) return false
        return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + pixels.contentHashCode()
        return result
    }

    override fun toString(): String = "PixelImage(${width}x$height)"

    companion object {
        fun filled(width: Int, height: Int, argb: Int): PixelImage =
            PixelImage(width, height, IntArray(width * height) { argb })

        fun opaque(width: Int, height: Int): PixelImage = filled(width, height, ARGB_OPAQUE_BLACK)

        const val ARGB_OPAQUE_BLACK: Int = 0xFF000000.toInt()
    }
}

/** alpha 通道（0..255）。 */
val Int.alpha8: Int get() = (this ushr 24) and 0xFF

/** 红通道（0..255）。 */
val Int.red8: Int get() = (this ushr 16) and 0xFF

/** 绿通道（0..255）。 */
val Int.green8: Int get() = (this ushr 8) and 0xFF

/** 蓝通道（0..255）。 */
val Int.blue8: Int get() = this and 0xFF

/** 拼装 ARGB。各通道会先夹紧到 0..255。 */
fun argb(a: Int, r: Int, g: Int, b: Int): Int {
    val aa = a.coerceIn(0, 255)
    val rr = r.coerceIn(0, 255)
    val gg = g.coerceIn(0, 255)
    val bb = b.coerceIn(0, 255)
    return (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
}
