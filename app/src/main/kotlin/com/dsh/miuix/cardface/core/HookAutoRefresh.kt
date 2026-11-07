package com.dsh.miuix.cardface.core

/**
 * 模块升级后自动让卡包进程加载新的 hook 代码。
 *
 * 背景：LSPosed 的 hook 在卡包进程启动时安装，模块更新后必须重启卡包才生效。
 * 这里用 root 删除卡包里的方法定位缓存并 force-stop 卡包，用户打开本应用就会自动完成，
 * 不需要手动找「清除 hook 点缓存」。失败（无 root）时静默跳过。
 */
object HookAutoRefresh {

    /** hook 定位逻辑版本；改动 MethodLocator 后 +1，用户打开 App 就会自动刷新卡包。 */
    const val TARGET_VERSION = 4
    private const val KEY_VERSION = "hook_target_version"

    fun ensureFresh(context: android.content.Context, prefs: AppPrefs): Boolean {
        if (prefs.settings.getInt(KEY_VERSION, 0) >= TARGET_VERSION) return false
        val kind = WalletApps.primary(context) ?: return false
        if (!RootShell.isAvailable()) return false

        val pkg = kind.packageName
        val files = "/proc/1/root/data/data/$pkg/files"
        // 删除旧版与新版方法缓存，强制重新定位；顺手把卡包停掉。
        RootShell.exec(
            "rm -f '$files'/cardface.method*.txt '$files'/zhizi42.diycard.method*.txt 2>/dev/null; " +
                "am force-stop '$pkg' 2>/dev/null; echo done",
            timeoutMs = 8000,
        )
        prefs.settings.edit().putInt(KEY_VERSION, TARGET_VERSION).apply()
        prefs.bumpRevision()
        return true
    }
}
