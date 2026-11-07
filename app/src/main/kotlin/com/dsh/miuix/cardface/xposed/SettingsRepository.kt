package com.dsh.miuix.cardface.xposed

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.libxposed.api.XposedModule

/**
 * 汇总设置来源：
 *  - libxposed `getRemotePreferences()`（LSPosed 数据库，被 hook 进程里**只读**、但会实时推送更新）
 *  - 模块 App 的 RemotePreferences provider（ContentResolver，可读写；App 的 SharedPreferences 真身）
 *
 * 两个来源做合并：provider 有值时覆盖 framework，保证 App 里刚改的设置尽快生效；
 * 如果 provider 因为包可见性等原因不可访问，则退化为只用 framework 数据。
 */
class SettingsRepository(private val module: XposedModule) {

    @Volatile
    private var context: Context? = null

    @Volatile
    private var provider: ProviderPrefs? = null

    @Volatile
    private var frameworkSettings: SharedPreferences? = null

    @Volatile
    private var frameworkCardUrls: SharedPreferences? = null

    @Volatile
    private var providerSettings: Map<String, Any?> = emptyMap()

    @Volatile
    private var providerCardUrls: Map<String, Any?> = emptyMap()

    @Volatile
    private var providerReadable = false

    /** 卡包 files/cardface.map 快照（App 写入），作为最低优先级兜底。 */
    @Volatile
    private var snapshotMappings: Map<String, String> = emptyMap()

    @Volatile
    private var dirty = true

    private var lastProviderReadMs = 0L

    @Volatile
    private var lastHookAtWrittenMs = 0L

    private var observerRegistered = false

    // ---------- 初始化 ----------

    fun init() {
        try {
            frameworkSettings = module.getRemotePreferences(GROUP_SETTINGS)
            frameworkCardUrls = module.getRemotePreferences(GROUP_CARD_URLS)
            XLog.i(
                "remote prefs ready: settings=${frameworkSettings?.all?.size ?: -1} keys, " +
                    "card_urls=${frameworkCardUrls?.all?.size ?: -1} keys",
            )
        } catch (t: Throwable) {
            // 非 LSPosed（embedded framework）/不支持 remote prefs 时这里是正常的
            XLog.w("remote prefs unavailable, fallback to provider only", t)
            frameworkSettings = null
            frameworkCardUrls = null
        }
        XLog.debug = rawBoolean(KEY_DEBUG, false)
    }

    fun attachContext(context: Context) {
        this.context = context.applicationContext ?: context
        loadSnapshot()
        val p = ProviderPrefs(this.context!!)
        provider = p
        registerObserver()
        refresh(force = true)
        XLog.i("hook context ready, provider prefs readable=$providerReadable")
    }

    /** App 侧设置变化时，ContentObserver 只清缓存，下次拦截时重读。 */
    private fun registerObserver() {
        val ctx = context ?: return
        if (observerRegistered) return
        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    dirty = true
                }
            }
            ctx.contentResolver.registerContentObserver(
                Uri.parse("content://${com.dsh.miuix.cardface.core.HookPrefsProtocol.AUTHORITY}/$GROUP_SETTINGS"),
                true,
                observer,
            )
            ctx.contentResolver.registerContentObserver(
                Uri.parse("content://${com.dsh.miuix.cardface.core.HookPrefsProtocol.AUTHORITY}/$GROUP_CARD_URLS"),
                true,
                observer,
            )
            observerRegistered = true
        } catch (t: Throwable) {
            XLog.w("register prefs observer failed", t)
        }
    }

    // ---------- 读 ----------

    fun refresh(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && !dirty && now - lastProviderReadMs < PROVIDER_REFRESH_INTERVAL_MS) return
        dirty = false
        lastProviderReadMs = now
        val p = provider ?: return
        val settings = p.readAll(GROUP_SETTINGS)
        val cards = p.readAll(GROUP_CARD_URLS)
        if (settings != null || cards != null) {
            providerReadable = true
            if (settings != null) providerSettings = settings
            if (cards != null) providerCardUrls = cards
            XLog.d("provider prefs refreshed: settings=${providerSettings.size}, card_urls=${providerCardUrls.size}")
        }
    }

    fun mergeSettings(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        // 远程（LSPosed 框架）优先级最高；provider 次之。合并顺序：provider -> framework。
        out.putAll(providerSettings)
        frameworkSettings?.let { prefs ->
            try {
                out.putAll(prefs.all)
            } catch (t: Throwable) {
                XLog.w("read framework settings failed", t)
            }
        }
        return out
    }

    private fun loadSnapshot() {
        val ctx = context ?: return
        try {
            val file = java.io.File(ctx.filesDir, "cardface.map")
            if (!file.isFile) return
            val map = LinkedHashMap<String, String>()
            file.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab in 1 until line.length - 1) {
                    map[line.substring(0, tab)] = line.substring(tab + 1)
                }
            }
            snapshotMappings = map
        } catch (_: Throwable) {
        }
    }

    fun mergeCardUrls(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        frameworkCardUrls?.let { prefs ->
            try {
                out.putAll(prefs.all)
            } catch (t: Throwable) {
                XLog.w("read framework card_urls failed", t)
            }
        }
        out.putAll(providerCardUrls)
        return out
    }

    fun rawString(key: String): String? = mergeSettings()[key] as? String

    fun rawBoolean(key: String, default: Boolean): Boolean =
        when (val v = mergeSettings()[key]) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", ignoreCase = true)
            else -> default
        }

    fun rawStringSet(key: String): Set<String> {
        val v = mergeCardUrls()[key] ?: mergeSettings()[key]
        return when (v) {
            is Set<*> -> v.filterIsInstance<String>().toSet()
            else -> emptySet()
        }
    }

    fun snapshot(): HookSettings {
        refresh()
        val s = mergeSettings()
        val compat = when {
            s.containsKey(KEY_COMPAT_COPY) -> s[KEY_COMPAT_COPY].asBoolean(false)
            s.containsKey(KEY_COMPAT_MODE) -> s[KEY_COMPAT_MODE].asBoolean(false)
            else -> false
        }
        val debug = s[KEY_DEBUG].asBoolean(false)
        XLog.debug = debug
        return HookSettings(
            debug = debug,
            showAllCards = s[KEY_SHOW_ALL_CARDS].asBoolean(false),
            superLand = s[KEY_SUPER_LAND].asBoolean(true),
            testMode = s[KEY_TEST_MODE].asBoolean(false),
            compatMode = compat,
            loadUrlFromArg = s[KEY_LOAD_FROM_ARG].asBoolean(false),
            replaceUrlToArg = s[KEY_REPLACE_TO_ARG].asBoolean(false),
            customClass = s[KEY_CUSTOM_CLASS].asString().orEmpty(),
            customMethod = s[KEY_CUSTOM_METHOD].asString().orEmpty(),
            customMethodArg = s[KEY_CUSTOM_METHOD_ARG].asString().orEmpty(),
        )
    }

    /** settings 里除保留键外的 String 项 = url -> 替换值（文件名 / http 地址）。 */
    fun mappings(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        // 快照优先级最低：先放快照，再被 provider，最后被远程覆盖。
        out.putAll(snapshotMappings)
        mergeSettings().forEach { (key, value) ->
            if (key in RESERVED_KEYS) return@forEach
            if (value is String && value.isNotEmpty()) out[key] = value
        }
        return out
    }

    fun blacklist(): Set<String> = rawStringSet(KEY_BLACK_CARD_URLS)

    fun cardUrls(): Set<String> = rawStringSet(KEY_ALL_CARD_URLS)

    // ---------- 写 ----------

    /** 写回 settings.last_hook_at（节流，避免每张图都跨进程写）。 */
    fun touchLastHookAt(force: Boolean = false) {
        val wall = System.currentTimeMillis()
        if (!force && wall - lastHookAtWrittenMs < LAST_HOOK_AT_INTERVAL_MS) return
        val p = provider ?: run {
            XLog.d("no context yet, skip last_hook_at")
            return
        }
        val ok = p.putLong(GROUP_SETTINGS, KEY_LAST_HOOK_AT, wall)
        if (ok) {
            lastHookAtWrittenMs = wall
            providerSettings = LinkedHashMap(providerSettings).apply { put(KEY_LAST_HOOK_AT, wall) }
            XLog.d("last_hook_at updated")
        } else {
            XLog.w("write last_hook_at failed (provider not writable)")
        }
    }

    /** 把新 URL 加入 card_urls/all_card_url_set；已存在返回 false。 */
    fun addCardUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        refresh(force = true)
        val current = cardUrls()
        if (url in current) return false
        val next = LinkedHashSet(current).apply { add(url) }
        providerCardUrls = LinkedHashMap(providerCardUrls).apply { put(KEY_ALL_CARD_URLS, next) }
        val p = provider
        if (p == null) {
            XLog.w("no context yet, card url list not persisted")
            return false
        }
        val ok = p.putStringSet(GROUP_CARD_URLS, KEY_ALL_CARD_URLS, next)
        if (ok) {
            XLog.i("card url added to list: $url (total=${next.size})")
        } else {
            XLog.w("write card url list failed (provider not writable)")
        }
        return ok
    }

    // ---------- 工具 ----------

    private fun Any?.asBoolean(default: Boolean): Boolean = when (this) {
        null -> default
        is Boolean -> this
        is Number -> toInt() != 0
        is String -> equals("true", ignoreCase = true)
        else -> default
    }

    private fun Any?.asString(): String? = when (this) {
        null -> null
        is String -> this
        else -> toString()
    }

    companion object {
        const val GROUP_SETTINGS = "settings"
        const val GROUP_CARD_URLS = "card_urls"

        const val KEY_HIDE_ICON = "hide_icon"
        const val KEY_SHOW_ALL_CARDS = "show_all_cards"
        const val KEY_SUPER_LAND = "super_land"
        const val KEY_TEST_MODE = "test_mode"
        const val KEY_DEBUG = "debug"
        const val KEY_DEV_MODE = "dev_mode_open"
        const val KEY_FIRST_RUN = "first_run"
        const val KEY_CUSTOM_CLASS = "class"
        const val KEY_CUSTOM_METHOD = "method"
        const val KEY_CUSTOM_METHOD_ARG = "method_arg"
        const val KEY_LOAD_FROM_ARG = "load_url_from_arg"
        const val KEY_REPLACE_TO_ARG = "replace_url_to_arg"
        const val KEY_LAST_HOOK_AT = "last_hook_at"
        const val KEY_COMPAT_COPY = "compat_copy"
        const val KEY_COMPAT_MODE = "compat_mode"
        const val KEY_ALL_CARD_URLS = "all_card_url_set"
        const val KEY_BLACK_CARD_URLS = "black_card_url_set"

        /** 这些键不是 url -> 替换值 的映射。 */
        val RESERVED_KEYS = setOf(
            KEY_HIDE_ICON, KEY_SHOW_ALL_CARDS, KEY_SUPER_LAND, KEY_TEST_MODE, KEY_DEBUG,
            KEY_DEV_MODE, KEY_FIRST_RUN, KEY_CUSTOM_CLASS, KEY_CUSTOM_METHOD,
            KEY_CUSTOM_METHOD_ARG, KEY_LOAD_FROM_ARG, KEY_REPLACE_TO_ARG, KEY_LAST_HOOK_AT,
            KEY_COMPAT_COPY, KEY_COMPAT_MODE, "card_style", "card_style_mode",
        )

        private const val PROVIDER_REFRESH_INTERVAL_MS = 1500L
        private const val LAST_HOOK_AT_INTERVAL_MS = 2000L
    }
}

/** 一次 replaceUrl 需要的设置快照。 */
data class HookSettings(
    val debug: Boolean = false,
    val showAllCards: Boolean = false,
    val superLand: Boolean = false,
    val testMode: Boolean = false,
    val compatMode: Boolean = false,
    val loadUrlFromArg: Boolean = false,
    val replaceUrlToArg: Boolean = false,
    val customClass: String = "",
    val customMethod: String = "",
    val customMethodArg: String = "",
)
