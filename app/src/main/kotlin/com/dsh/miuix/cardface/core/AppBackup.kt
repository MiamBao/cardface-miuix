package com.dsh.miuix.cardface.core

import android.content.Context
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 数据备份/恢复：设置、映射、卡面名称、图库替换图。 */
object AppBackup {

    private const val FORMAT = 1
    private val PREFS = listOf("settings", "card_urls", "card_names")

    fun export(context: Context, prefs: AppPrefs): File {
        val out = File(context.cacheDir, "cardface-backup.zip")
        ZipOutputStream(FileOutputStream(out).buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("format.txt"))
            zip.write("$FORMAT\n".toByteArray())
            zip.closeEntry()
            for (name in PREFS) {
                val f = File(context.dataDir, "shared_prefs/$name.xml")
                if (f.isFile) {
                    zip.putNextEntry(ZipEntry("prefs/$name.xml"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            fun addDir(dir: File, prefix: String) {
                dir.listFiles()?.filter { it.isFile }?.forEach { img ->
                    // 重装后 UID 变化或文件损坏时跳过，别让一张图拖垮整个备份。
                    if (!img.canRead()) return@forEach
                    runCatching {
                        zip.putNextEntry(ZipEntry("$prefix/${img.name}"))
                        img.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            addDir(File(context.filesDir, "images"), "images")
            // 映射实际指向 applied/（应用后的合成图），以前漏掉了这个目录。
            addDir(File(context.filesDir, "applied"), "applied")
        }
        return out
    }

    data class RestoreResult(val mappings: Int, val images: Int)

    /** 恢复备份；失败抛异常由调用方提示。传入当前 AppPrefs，恢复后让界面立刻重读。 */
    fun restore(context: Context, uri: Uri, prefs: AppPrefs = AppPrefs(context)): RestoreResult {
        val tmp = File(context.cacheDir, "restore.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { out -> input.copyTo(out) }
        } ?: error("无法读取备份文件")
        var mappings = 0
        var images = 0
        ZipFile(tmp).use { zip ->
            val settingsXml = zip.getEntry("prefs/settings.xml")?.let { read(zip, it) }
            val urlsXml = zip.getEntry("prefs/card_urls.xml")?.let { read(zip, it) }
            val namesXml = zip.getEntry("prefs/card_names.xml")?.let { read(zip, it) }
            if (settingsXml == null && urlsXml == null) error("备份内容不完整")
            if (settingsXml != null) {
                val values = parseMap(settingsXml)
                prefs.restoreSettings(values.filterKeys { it !in EPHEMERAL })
            }
            if (urlsXml != null) {
                val urls = (parseMap(urlsXml)["all_card_url_set"] as? Set<*>)?.mapNotNull { it as? String }.orEmpty()
                prefs.restoreUrls(urls.toSet())
                mappings = urls.size
            }
            if (namesXml != null) {
                val names = parseMap(namesXml).mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
                prefs.restoreNames(names)
            }
            val imagesDir = File(context.filesDir, "images").apply { mkdirs() }
            val appliedDir = File(context.filesDir, "applied").apply { mkdirs() }
            zip.entries().asSequence().filter {
                (it.name.startsWith("images/") || it.name.startsWith("applied/")) && !it.isDirectory
            }.forEach { e ->
                val isApplied = e.name.startsWith("applied/")
                val name = e.name.removePrefix(if (isApplied) "applied/" else "images/")
                if (name.isNotEmpty() && !name.contains('/')) {
                    val target = File(if (isApplied) appliedDir else imagesDir, name)
                    zip.getInputStream(e).use { input ->
                        FileOutputStream(target).use { out -> input.copyTo(out) }
                    }
                    images++
                }
            }
            // 兼容旧备份：旧版只打包了 images/，但映射可能指向 applied/。
            // 卡包里通常还留着已应用的副本，缺图时从卡包私有目录补回来。
            val wallet = WalletApps.primary(context)
            if (wallet != null) {
                val walletImages = "/data/data/${wallet.packageName}/files/images"
                prefs.mappedImageNames().forEach { name ->
                    if (File(imagesDir, name).exists() || File(appliedDir, name).exists()) return@forEach
                    if (RootShell.copyIntoDir("$walletImages/$name", appliedDir.absolutePath, name, "644").ok) {
                        images++
                    }
                }
            }
        }
        // 文件已经全部写回，再触发一次界面重读；避免首页先拿到旧图/空文件。
        prefs.bumpRevision()
        CardSync.writeMapSnapshot(context, prefs)
        tmp.delete()
        return RestoreResult(mappings, images)
    }

    private fun read(zip: ZipFile, entry: ZipEntry): String =
        zip.getInputStream(entry).bufferedReader().use { it.readText() }

    private fun parseMap(xml: String): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())
        var setName: String? = null
        var setValues = mutableSetOf<String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "set" -> {
                        setName = parser.getAttributeValue(null, "name")
                        setValues = mutableSetOf()
                    }
                    "boolean" -> parser.getAttributeValue(null, "name")?.let {
                        result[it] = parser.getAttributeValue(null, "value") == "true"
                    }
                    "int" -> parser.getAttributeValue(null, "name")?.let {
                        result[it] = parser.getAttributeValue(null, "value")?.toIntOrNull() ?: 0
                    }
                    "long" -> parser.getAttributeValue(null, "name")?.let {
                        result[it] = parser.getAttributeValue(null, "value")?.toLongOrNull() ?: 0L
                    }
                    "string" -> {
                        val name = parser.getAttributeValue(null, "name")
                        val text = parser.nextText()
                        if (setName != null) setValues.add(text)
                        else if (name != null) result[name] = text
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "set") {
                    setName?.let { result[it] = setValues.toSet() }
                    setName = null
                }
            }
            parser.next()
        }
        return result
    }

    private val EPHEMERAL = setOf(
        "last_hook_at", "hook_target_version", "first_run", "dev_mode_open",
        "hidden_promo_bank_faces_v1", "data_saver_prompt_v1",
    )
}
