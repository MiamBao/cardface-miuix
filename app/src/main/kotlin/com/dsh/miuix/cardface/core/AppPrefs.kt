package com.dsh.miuix.cardface.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 模块设置与卡面映射的存储。
 *
 * 设置放在 `settings` 里，卡面地址集合放在 `card_urls` 里；
 * 这两个 SharedPreferences 会通过 RemotePreferences 供被 hook 的卡包进程读取，
 * 因此键名与原版保持一致（hide_icon / show_all_cards / super_land / ...）。
 */
class AppPrefs(context: Context) {

    private val appContext = context.applicationContext
    val settings: SharedPreferences = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val urls: SharedPreferences = appContext.getSharedPreferences("card_urls", Context.MODE_PRIVATE)

    /** 数据版本号；写入后自增，界面用它触发重读。 */
    var revision by mutableIntStateOf(0)
        private set

    /**
     * 仅外观相关设置（动态取色 / 主题色 / 深色 / 动画）变化时递增。
     *
     * 不能直接用 [revision]：hook 写卡面 URL、last_hook_at 也会 touch()，
     * 如果主题跟着 revision 重建，会在卡包上报数据时把整个界面重新换主题，造成明显卡顿。
     */
    var appearanceRevision by mutableIntStateOf(0)
        private set

    private fun touch() {
        revision++
    }

    /** 允许外部（如 hook 自动刷新）触发界面重读。 */
    fun bumpRevision() {
        revision++
    }

    // ---------- 开关 ----------

    var hideIcon: Boolean
        get() = settings.getBoolean(KEY_HIDE_ICON, false)
        set(v) = settings.edit().putBoolean(KEY_HIDE_ICON, v).apply().also { touch() }

    var showAllCards: Boolean
        get() = settings.getBoolean(KEY_SHOW_ALL_CARDS, false)
        set(v) = settings.edit().putBoolean(KEY_SHOW_ALL_CARDS, v).apply().also { touch() }

    var superIsland: Boolean
        get() = settings.getBoolean(KEY_SUPER_LAND, true)
        set(v) = settings.edit().putBoolean(KEY_SUPER_LAND, v).apply().also { touch() }

    var testMode: Boolean
        get() = settings.getBoolean(KEY_TEST_MODE, false)
        set(v) = settings.edit().putBoolean(KEY_TEST_MODE, v).apply().also { touch() }

    var debugLog: Boolean
        get() = settings.getBoolean(KEY_DEBUG, false)
        set(v) = settings.edit().putBoolean(KEY_DEBUG, v).apply().also { touch() }

    var devMode: Boolean
        get() = settings.getBoolean(KEY_DEV_MODE, false)
        set(v) = settings.edit().putBoolean(KEY_DEV_MODE, v).apply().also { touch() }

    /** 启动时自动检查 GitHub 最新版本（可在关于页关掉）。 */
    var autoCheckUpdate: Boolean
        get() = settings.getBoolean("auto_check_update", true)
        set(v) = settings.edit().putBoolean("auto_check_update", v).apply().also { touch() }

    var firstRun: Boolean
        get() = settings.getBoolean(KEY_FIRST_RUN, true)
        set(v) = settings.edit().putBoolean(KEY_FIRST_RUN, v).apply().also { touch() }

    // ---------- 外观 ----------

    /** 是否跟随壁纸动态取色（Monet）。 */
    var dynamicColor: Boolean
        get() = settings.getBoolean(KEY_DYNAMIC_COLOR, false)
        set(v) = settings.edit().putBoolean(KEY_DYNAMIC_COLOR, v).apply().also { touch(); appearanceRevision++ }

    /** 主题色（ARGB int），动态取色关闭时生效。 */
    var keyColorArgb: Int
        get() = settings.getInt(KEY_KEY_COLOR, DEFAULT_KEY_COLOR)
        set(v) = settings.edit().putInt(KEY_KEY_COLOR, v).apply().also { touch(); appearanceRevision++ }

    /** 0 = 跟随系统，1 = 浅色，2 = 深色。 */
    var darkMode: Int
        get() = settings.getInt(KEY_DARK_MODE, 0)
        set(v) = settings.edit().putInt(KEY_DARK_MODE, v).apply().also { touch(); appearanceRevision++ }

    /** 页面/列表过渡动画开关（默认开）。 */
    var animations: Boolean
        get() = settings.getBoolean(KEY_ANIMATIONS, true)
        set(v) = settings.edit().putBoolean(KEY_ANIMATIONS, v).apply().also { touch(); appearanceRevision++ }

    /** 兼容模式：把图片 root 复制到卡包私有目录并返回 file://（默认关，走 content:// 无需 root）。 */
    var compatCopy: Boolean
        get() = settings.getBoolean("compat_copy", false)
        set(v) = settings.edit().putBoolean("compat_copy", v).apply().also { touch() }

    // 自定义 hook 点
    var hookClass: String
        get() = settings.getString("class", "") ?: ""
        set(v) = settings.edit().putString("class", v).apply().also { touch() }

    var hookMethod: String
        get() = settings.getString("method", "") ?: ""
        set(v) = settings.edit().putString("method", v).apply().also { touch() }

    var hookMethodArg: String
        get() = settings.getString("method_arg", "") ?: ""
        set(v) = settings.edit().putString("method_arg", v).apply().also { touch() }

    var hookLoadUrlFromArg: Boolean
        get() = settings.getBoolean("load_url_from_arg", false)
        set(v) = settings.edit().putBoolean("load_url_from_arg", v).apply().also { touch() }

    var hookReplaceUrlToArg: Boolean
        get() = settings.getBoolean("replace_url_to_arg", false)
        set(v) = settings.edit().putBoolean("replace_url_to_arg", v).apply().also { touch() }

    // ---------- 卡面 ----------

    fun allCardUrls(): List<String> =
        urls.getStringSet(KEY_ALL_CARD_URLS, emptySet()).orEmpty().toList().sorted()

    fun blacklistedUrls(): Set<String> =
        urls.getStringSet(KEY_BLACK_CARD_URLS, emptySet()).orEmpty().toSet()

    fun addBlacklisted(url: String) {
        val next = blacklistedUrls() + url
        urls.edit().putStringSet(KEY_BLACK_CARD_URLS, next).apply()
        touch()
    }

    fun removeBlacklisted(url: String) {
        val next = blacklistedUrls() - url
        urls.edit().putStringSet(KEY_BLACK_CARD_URLS, next).apply()
        touch()
    }

    fun isBlacklisted(url: String): Boolean = blacklistedUrls().contains(url)

    /** 返回所有可替换的卡面（除去黑名单）。 */
    /** 卡面自定义名称，单独一个 prefs，避免和 URL 集合混在一起。 */
    private val names: android.content.SharedPreferences =
        context.getSharedPreferences("card_names", android.content.Context.MODE_PRIVATE)

    fun customName(url: String): String? = names.getString(url, null)?.takeIf { it.isNotBlank() }

    fun setCustomName(url: String, name: String?) {
        names.edit().apply {
            if (name.isNullOrBlank()) remove(url) else putString(url, name.trim())
        }.apply()
        touch()
    }

    fun cardFaces(): List<CardFaceItem> {
        val black = blacklistedUrls()
        val replacementNames = mappedImageNames().toSet()
        return allCardUrls()
            .filterNot { black.contains(it) }
            // 卡包加载我们复制进去的替换图时也会产生 file:// URL，这不是真实卡面，过滤掉。
            .filterNot { url ->
                val name = url.substringAfterLast('/')
                url.contains("com.dsh.miuix.cardface") ||
                    replacementNames.contains(name) ||
                    name.matches(Regex("^card-\\d{8}-\\d{6}\\.png$"))
            }
            // 图标 / 横幅 / 广告 / 二维码等明显不是卡面的图，不展示。
            .filterNot { isJunkImageUrl(it) }
            .map { CardFaceItem(url = it, replacement = replacementFor(it), customName = customName(it)) }
    }

    /** 卡包里大量非卡面图片（图标、横幅、广告）会被 hook 抓到，这里统一过滤。 */
    fun isJunkImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        return JUNK_URL_MARKERS.any { lower.contains(it) }
    }

    /** 清理历史遗留的杂图 URL；返回清掉的数量。 */
    fun sanitizeCardUrls(): Int {
        val before = allCardUrls()
        val replacementNames = mappedImageNames().toSet()
        val kept = before.filterNot { url ->
            val name = url.substringAfterLast('/')
            isJunkImageUrl(url) ||
                url.contains("com.dsh.miuix.cardface") ||
                replacementNames.contains(name) ||
                name.matches(Regex("^card-\\d{8}-\\d{6}\\.png$"))
        }
        if (kept.size == before.size) return 0
        urls.edit().putStringSet(KEY_ALL_CARD_URLS, kept.toSet()).apply()
        touch()
        return before.size - kept.size
    }

    /** 黑名单里的卡面（用于黑名单页展示）。 */
    fun blacklistedCardFaces(): List<CardFaceItem> =
        blacklistedUrls().sorted().map {
            CardFaceItem(url = it, replacement = replacementFor(it), customName = customName(it))
        }

    fun replacementFor(url: String): String? =
        settings.getString(url, "")?.takeIf { it.isNotBlank() }

    fun setReplacement(url: String, value: String?) {
        val editor = settings.edit()
        if (value.isNullOrBlank()) editor.remove(url) else editor.putString(url, value)
        editor.apply()
        touch()
    }

    /** 追加卡面 URL（供演示数据/测试使用；hook 上报走的是 provider 写回）。 */
    fun addCardUrl(urls: List<String>) {
        val next = allCardUrls().toMutableSet()
        next.addAll(urls)
        this.urls.edit().putStringSet(KEY_ALL_CARD_URLS, next).apply()
        touch()
    }

    fun removeCardUrl(url: String) {
        val next = allCardUrls().toMutableSet()
        next.remove(url)
        this.urls.edit().putStringSet(KEY_ALL_CARD_URLS, next).apply()
        setReplacement(url, null)
        touch()
    }

    /** settings 里所有「图库文件名」型映射值（排除 http 地址与空值），用于同步到卡包目录。 */
    /** 所有 URL -> 替换值（文件名 / http 地址）的映射，用于给 hook 进程写本地快照。 */
    fun mappingPairs(): Map<String, String> {
        val reserved = setOf(
            KEY_HIDE_ICON, KEY_SHOW_ALL_CARDS, KEY_SUPER_LAND, KEY_TEST_MODE,
            KEY_DEBUG, KEY_DEV_MODE, KEY_FIRST_RUN, "class", "method", "method_arg",
            "load_url_from_arg", "replace_url_to_arg",
        )
        val out = LinkedHashMap<String, String>()
        settings.all.forEach { (key, value) ->
            if (key in reserved) return@forEach
            if (value is String && value.isNotEmpty()) out[key] = value
        }
        return out
    }

    fun mappedImageNames(): List<String> = settings.all.values
        .filterIsInstance<String>()
        .filter { it.isNotBlank() && !it.startsWith("http://") && !it.startsWith("https://") }
        .filter { !it.contains('/') && !it.contains('\\') }
        .distinct()

    fun replacedCount(): Int = allCardUrls().count { !replacementFor(it).isNullOrBlank() }

    fun clearCardUrls() {
        urls.edit().clear().apply()
        blackMarkClear()
        touch()
    }

    private fun blackMarkClear() {
        // 清空所有卡面时同时清掉映射（settings 里除设置键以外的 URL 键）
        val editor = settings.edit()
        val reserved = setOf(
            KEY_HIDE_ICON, KEY_SHOW_ALL_CARDS, KEY_SUPER_LAND, KEY_TEST_MODE,
            KEY_DEBUG, KEY_DEV_MODE, KEY_FIRST_RUN, "class", "method", "method_arg",
            "load_url_from_arg", "replace_url_to_arg",
        )
        settings.all.keys.filterNot { reserved.contains(it) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /** hook 进程报告的存活时间戳。 */
    fun lastHookAt(): Long = settings.getLong(KEY_LAST_HOOK_AT, 0L)

    fun clearHookState() {
        settings.edit().remove(KEY_LAST_HOOK_AT).apply()
        touch()
    }

    companion object {
        const val KEY_HIDE_ICON = "hide_icon"
        const val KEY_SHOW_ALL_CARDS = "show_all_cards"
        const val KEY_SUPER_LAND = "super_land"
        const val KEY_TEST_MODE = "test_mode"
        const val KEY_DEBUG = "debug"
        const val KEY_DEV_MODE = "dev_mode_open"
        const val KEY_FIRST_RUN = "first_run"
        const val KEY_ALL_CARD_URLS = "all_card_url_set"
        const val KEY_BLACK_CARD_URLS = "black_card_url_set"
        const val KEY_LAST_HOOK_AT = "last_hook_at"
        private val JUNK_URL_MARKERS = listOf(
            "/icon", "icon_", "_icon", "avatar", "banner", "advert", "splash",
            "qrcode", "qr_code", "popup", "placeholder", "default_icon", "/logo/",
            "guide_", "status_bar", "loading",
        )
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_KEY_COLOR = "key_color"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_ANIMATIONS = "animations"
        val DEFAULT_KEY_COLOR = 0xFF3482FF.toInt()
    }
}
