package com.dsh.miuix.cardface.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader

/**
 * 演示卡面：让没装卡包（或 hook 还没生效）的用户也能完整体验「选图 → 裁剪 → 应用」流程。
 *
 * 演示图不是拿透明标志 PNG 直接当卡面，而是现场合成一张 960×606 的彩色卡面
 * （渐变底 + 内置卡面标志），这样列表里看起来就是一张真实卡片。
 * 「设置 › 清除所有卡面记录」会把演示数据和图片一起清掉。
 */
object DemoCards {

    private const val DEMO_PREFIX = "demo-card-v2-"
    private const val OLD_PREFIX = "demo-card-"

    data class DemoCard(
        val url: String,
        val assetName: String,
        val imageName: String,
        val label: String,
        val gradient: IntArray,
    )

    val cards: List<DemoCard>
        get() = listOf(
            DemoCard(
                url = "https://demo.cardface.local/transit/shanghai.png",
                assetName = "shanghai",
                imageName = "${DEMO_PREFIX}shanghai.png",
                label = "上海公共交通卡（演示）",
                gradient = intArrayOf(Color.parseColor("#1E5BFF"), Color.parseColor("#7B3FE4")),
            ),
            DemoCard(
                url = "https://demo.cardface.local/transit/jingjinji.png",
                assetName = "jingjinji",
                imageName = "${DEMO_PREFIX}jingjinji.png",
                label = "京津冀互联互通卡（演示）",
                gradient = intArrayOf(Color.parseColor("#0E9F6E"), Color.parseColor("#1D4ED8")),
            ),
        )

    fun isDemoUrl(url: String): Boolean = url.startsWith("https://demo.cardface.local/")

    fun hasDemo(prefs: AppPrefs): Boolean = prefs.allCardUrls().any { isDemoUrl(it) }

    /** 载入演示卡面；重复调用是幂等的（会重新生成演示图）。 */
    fun seed(context: Context, prefs: AppPrefs, store: ImageStore): Boolean {
        var ok = false
        cards.forEach { demo ->
            val bitmap = composeCard(context, demo)
            if (bitmap != null) {
                store.save(bitmap, demo.imageName)
                bitmap.recycle()
                prefs.setReplacement(demo.url, demo.imageName)
                ok = true
            }
        }
        if (ok) prefs.addCardUrl(cards.map { it.url })
        return ok
    }

    /** 删除演示图片（含旧版本命名）。 */
    fun clearImages(store: ImageStore) {
        store.dir.listFiles { f -> f.isFile && f.name.startsWith(OLD_PREFIX) }
            ?.forEach { it.delete() }
    }

    /** 合成 960×606 演示卡面：斜向渐变 + 轻装饰 + 内置标志。 */
    private fun composeCard(context: Context, demo: DemoCard): Bitmap? {
        val w = 960
        val h = 606
        return try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                demo.gradient[0], demo.gradient[1],
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

            // 轻装饰：右上角两圈半透明白，模拟卡片底纹
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.argb(38, 255, 255, 255)
            canvas.drawCircle(w - 70f, -40f, 220f, paint)
            canvas.drawCircle(w - 70f, -40f, 320f, paint)

            val logo = context.assets.open("card_logo/${demo.assetName}.png").use { input ->
                BitmapFactory.decodeStream(input)
            }
            if (logo != null) {
                paint.style = Paint.Style.FILL
                paint.alpha = 255
                canvas.drawBitmap(logo, 0f, 0f, paint)
                logo.recycle()
            }
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}
