package com.dsh.miuix.cardface.core

import java.io.File

/** 一张被拦截到的卡面：原始图片地址 + 已设置的替换图。 */
data class CardFaceItem(
    val url: String,
    /** 替换值：图库文件名（不带路径）或 http(s) 地址；null 表示未替换。 */
    val replacement: String?,
    /** 用户自定义的卡面名称（为空时用 URL 推导）。 */
    val customName: String? = null,
) {
    val replaced: Boolean get() = !replacement.isNullOrBlank()
    val replacementIsRemote: Boolean
        get() = replacement?.startsWith("http://") == true || replacement?.startsWith("https://") == true

    /** 用于界面展示的短名称：取 URL 最后一段，去掉查询串。 */
    val displayName: String
        get() {
            if (!customName.isNullOrBlank()) return customName.trim()
            val raw = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
            val decoded = try {
                java.net.URLDecoder.decode(raw, "UTF-8")
            } catch (_: Throwable) {
                raw
            }
            return when {
                decoded.isBlank() -> url
                // 纯 hash 文件名（32/40 位十六进制）显示成短名
                decoded.matches(Regex("^[0-9a-fA-F]{24,}$")) -> "卡面 " + decoded.take(8)
                else -> decoded
            }
        }
}

/** 图库里的一张替换图。 */
data class GalleryImage(val file: File) {
    val name: String get() = file.name
    val displayName: String get() = file.nameWithoutExtension
}

enum class WalletKind(val packageName: String, val label: String) {
    MIUI("com.miui.tsmclient", "小米智能卡"),
    COLOROS("com.finshell.wallet", "ColorOS 卡包"),
}

/** 模块与卡包的运行状态。 */
data class ModuleStatus(
    val lsposedInstalled: Boolean = false,
    val moduleActivated: Boolean = false,
    val walletInstalled: Boolean = false,
    val walletKind: WalletKind? = null,
    /** 卡包进程最近一次被 hook 的时间戳（毫秒），0 表示从未。 */
    val lastHookAt: Long = 0L,
)
