package com.dsh.miuix.cardface.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * LSPosed RemotePreferences 桥：
 *  - App 侧通过 libxposed service 把设置写进 LSPosed 框架数据库
 *  - hook 侧用 getRemotePreferences 同步读，冷启动也能拿到映射（原版做法）
 *
 * service 不可用时自动退化：所有写操作排队，连上后补写；App 功能不受影响。
 */
object RemotePrefsBridge {

    private const val TAG = "CardFaceRemote"

    @Volatile private var service: XposedService? = null
    @Volatile private var settings: SharedPreferences? = null
    @Volatile private var cardUrls: SharedPreferences? = null

    private val pending = ConcurrentLinkedQueue<(XposedService) -> Unit>()
    private val readyListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var available: Boolean = false
        private set

    fun init() {
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    this@RemotePrefsBridge.service = service
                    settings = runCatching { service.getRemotePreferences("settings") }.getOrNull()
                    cardUrls = runCatching { service.getRemotePreferences("card_urls") }.getOrNull()
                    available = settings != null
                    Log.i(TAG, "service bound, remote available=$available")
                    while (true) {
                        val op = pending.poll() ?: break
                        runCatching { op(service) }.onFailure { Log.w(TAG, "flush op failed", it) }
                    }
                    readyListeners.forEach { runCatching { it() } }
                }

                override fun onServiceDied(service: XposedService) {
                    this@RemotePrefsBridge.service = null
                    settings = null
                    cardUrls = null
                    available = false
                    Log.i(TAG, "service died")
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "init remote prefs failed", t)
        }
    }

    fun onReady(listener: () -> Unit) {
        if (available) runCatching { listener() } else readyListeners.add(listener)
    }

    fun group(name: String): SharedPreferences? = when (name) {
        "settings" -> settings
        "card_urls" -> cardUrls
        else -> null
    }

    /** 往远程组里写；service 未就绪时排队。 */
    fun apply(group: String, block: (SharedPreferences.Editor) -> Unit) {
        val s = service
        val prefs = group(group)
        if (s != null && prefs != null) {
            runCatching { prefs.edit().also(block).apply() }
                .onFailure { Log.w(TAG, "remote write failed: $group", it) }
        } else {
            pending.add { svc ->
                runCatching {
                    val p = when (group) {
                        "settings" -> svc.getRemotePreferences("settings")
                        "card_urls" -> svc.getRemotePreferences("card_urls")
                        else -> null
                    }
                    if (p != null) p.edit().also(block).apply()
                }
            }
        }
    }

    /** 首次连接后把本地偏好迁移到远程（只做一次）。 */
    fun migrateIfNeeded(context: Context) {
        onReady {
            val marker = context.getSharedPreferences("remote_sync", Context.MODE_PRIVATE)
            if (marker.getBoolean("migrated", false)) return@onReady
            val localSettings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val localUrls = context.getSharedPreferences("card_urls", Context.MODE_PRIVATE)
            val reserved = setOf(
                "hide_icon", "show_all_cards", "super_land", "test_mode", "debug",
                "dev_mode", "first_run", "class", "method", "method_arg",
                "load_url_from_arg", "replace_url_to_arg", "sync_mode", "auto_check_update",
            )
            runCatching {
                settings?.edit()?.also { e ->
                    localSettings.all.forEach { (k, v) ->
                        if (k in reserved || k == "last_hook_at") return@forEach
                        if (v is String && v.isNotEmpty()) e.putString(k, v)
                    }
                }?.apply()
                cardUrls?.edit()?.also { e ->
                    val set = localUrls.getStringSet("all_card_url_set", emptySet()) ?: emptySet()
                    if (set.isNotEmpty()) e.putStringSet("all_card_url_set", set)
                }?.apply()
                marker.edit().putBoolean("migrated", true).apply()
                Log.i(TAG, "migrated local prefs to remote")
            }.onFailure { Log.w(TAG, "migrate failed", it) }
        }
    }
}
