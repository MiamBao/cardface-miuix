package com.dsh.miuix.cardface.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** GitHub Release 查询（只用于「检查更新 / 更新公告」）。 */
object Updates {

    data class Release(
        val versionName: String,
        val tag: String,
        val body: String,
        val htmlUrl: String,
    )

    private const val API =
        "https://api.github.com/repos/MiamBao/cardface-miuix/releases/latest"

    /** 网络请求失败返回 null，调用方按「检查失败」处理。 */
    fun fetchLatest(): Release? = runCatching {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "cardface-miuix")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val tag = json.optString("tag_name")
            val version = tag.substringAfterLast('-').ifBlank { tag }.removePrefix("v")
            Release(
                versionName = version,
                tag = tag,
                body = json.optString("body").trim(),
                htmlUrl = json.optString("html_url"),
            )
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
