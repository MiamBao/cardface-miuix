package com.dsh.miuix.cardface.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * hook 进程日志：同时写 logcat 和 Xposed 日志（LSPosed 管理器「日志」页可见）。
 * 统一用 "cardface" 前缀，验收要求 logcat 里能看到 `cardface: hooked ...`。
 */
object XLog {

    const val TAG = "cardface"

    @Volatile
    private var module: XposedModule? = null

    @Volatile
    var debug: Boolean = false

    fun attach(module: XposedModule) {
        this.module = module
    }

    fun d(msg: String) {
        if (debug) write(Log.DEBUG, msg, null)
    }

    fun i(msg: String) = write(Log.INFO, msg, null)

    fun w(msg: String, tr: Throwable? = null) = write(Log.WARN, msg, tr)

    fun e(msg: String, tr: Throwable? = null) = write(Log.ERROR, msg, tr)

    private fun write(priority: Int, msg: String, tr: Throwable?) {
        try {
            if (tr == null) {
                Log.println(priority, TAG, msg)
            } else {
                Log.println(priority, TAG, "$msg: $tr")
            }
        } catch (_: Throwable) {
            // 某些 ROM 会限制 Log，忽略。
        }
        val m = module ?: return
        try {
            if (tr == null) {
                m.log(priority, TAG, msg)
            } else {
                m.log(priority, TAG, msg, tr)
            }
        } catch (_: Throwable) {
            // 框架日志不可用时只保留 logcat。
        }
    }
}
