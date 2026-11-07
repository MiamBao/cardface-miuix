package com.dsh.miuix.cardface.imaging

import kotlin.math.pow

/**
 * sRGB ⇄ 线性光 转换表。
 *
 * 正确的高质量缩放必须在线性光空间做加权平均：sRGB 是 gamma 编码的，
 * 直接在 sRGB 空间平均会让高对比区域（黑白细线、树叶与天空、文字边缘）
 * 明显变暗、发灰，线性光下平均的物理亮度才是对的。
 *
 * 这里用 256 项解码表（精确）和 4096 项编码表（线性插值，最大误差 < 0.5/255）
 * 代替逐像素 `pow`，把 1.7M 次编码降到查表。
 */
object Srgb {

    private const val ENCODE_STEPS = 4096

    /** sRGB 字节 → 线性光 [0,1]。 */
    private val DECODE: FloatArray = FloatArray(256) { i ->
        val c = i / 255.0
        val linear = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        linear.toFloat()
    }

    /** 线性光 [0,1] → sRGB [0,255]。末项为 1.0 的端点，便于线性插值。 */
    private val ENCODE: FloatArray = FloatArray(ENCODE_STEPS + 1) { i ->
        val t = i.toDouble() / ENCODE_STEPS
        val s = if (t <= 0.0031308) 12.92 * t else 1.055 * t.pow(1.0 / 2.4) - 0.055
        (s * 255.0).toFloat()
    }

    /** sRGB 字节值（0..255）→ 线性光 [0,1]。 */
    fun decode(value: Int): Float = DECODE[value and 0xFF]

    /**
     * 线性光 [0,1] → sRGB 浮点值 [0,255]（夹紧，四舍五入交给调用方）。
     */
    fun encode(linear: Float): Float {
        val t = if (linear.isNaN()) 0f else linear.coerceIn(0f, 1f)
        val pos = t * ENCODE_STEPS
        val i = pos.toInt().coerceIn(0, ENCODE_STEPS - 1)
        val frac = pos - i
        return ENCODE[i] + (ENCODE[i + 1] - ENCODE[i]) * frac
    }

    /** 线性光 [0,1] → 0..255 的整数字节，带四舍五入与夹紧。 */
    fun encode8(linear: Float): Int {
        val v = encode(linear) + 0.5f
        return if (v <= 0f) 0 else if (v >= 255f) 255 else v.toInt()
    }
}
