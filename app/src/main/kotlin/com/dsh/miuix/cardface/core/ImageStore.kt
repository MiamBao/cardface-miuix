package com.dsh.miuix.cardface.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 替换图库：保存在应用私有目录 files/images 下。 */
class ImageStore(context: Context) {

    private val appContext = context.applicationContext
    /** 图库里展示的图片（用户自己添加的原型图）。 */
    val dir: File = File(appContext.filesDir, "images").apply { mkdirs() }

    /** 实际应用到卡包的合成图；不出现在图库列表里，避免「应用一次多一张」。 */
    val appliedDir: File = File(appContext.filesDir, "applied").apply { mkdirs() }

    fun list(): List<GalleryImage> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".png", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { GalleryImage(it) }
            .orEmpty()

    fun find(name: String): File? =
        File(dir, name).takeIf { it.exists() } ?: File(appliedDir, name).takeIf { it.exists() }

    fun uniqueName(base: String): String {
        var name = base.ifBlank { defaultName() }
        if (!name.endsWith(".png", true)) name += ".png"
        if (find(name) == null) return name
        val stem = name.removeSuffix(".png")
        var i = 2
        while (find("$stem-$i.png") != null) i++
        return "$stem-$i.png"
    }

    fun defaultName(): String =
        "card-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun save(bitmap: Bitmap, name: String): File {
        val file = File(dir, name)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }

    /** 保存「实际应用到卡包」的合成图，放在隐藏目录，不出现在图库列表。 */
    fun saveApplied(bitmap: Bitmap, name: String): File {
        val file = File(appliedDir, name)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }

    fun load(name: String): Bitmap? = find(name)?.let { BitmapFactory.decodeFile(it.absolutePath) }

    fun delete(name: String): Boolean {
        var deleted = false
        File(dir, name).takeIf { it.exists() }?.let { deleted = it.delete() || deleted }
        File(appliedDir, name).takeIf { it.exists() }?.let { deleted = it.delete() || deleted }
        return deleted
    }

    /** 给卡包进程读取用的 content:// 地址；file:// 在 targetSdk>=24 的卡包里不可用。 */
    fun contentUri(name: String): Uri =
        Uri.parse("content://${ImageProvider.AUTHORITY}/images/$name")

    /** 本应用内展示用 file:// 地址。 */
    fun fileUri(name: String): String = find(name)?.let { "file://${it.absolutePath}" } ?: ""
}
