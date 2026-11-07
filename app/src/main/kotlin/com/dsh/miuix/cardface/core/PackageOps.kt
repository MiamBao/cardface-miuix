package com.dsh.miuix.cardface.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * 卡包 / LSPosed 的包管理操作：
 *  - 检测安装状态、打开卡包、打开 LSPosed
 *  - 隐藏 / 恢复桌面图标（桌面入口是 activity-alias .StartMainActivity，
 *    MainActivity 上挂了 MAIN + MODULE_SETTINGS，所以隐藏图标后 LSPosed 仍能打开设置页）
 *  - 强杀 / 重启卡包（优先 root，其次 ActivityManager 兜底）
 *
 * 所有方法都不抛异常，失败返回 false 并写日志。
 */
object PackageOps {

    private const val TAG = "cardface.pkg"

    const val LSPOSED_PACKAGE = "org.lsposed.manager"
    private const val LSPOSED_MAIN_ACTIVITY = "$LSPOSED_PACKAGE.ui.activity.MainActivity"
    private const val LAUNCHER_ALIAS_SUFFIX = ".StartMainActivity"
    private const val HIDDEN_CHANNEL_ID = "hidden_entry"
    private const val HIDDEN_NOTIFICATION_ID = 42

    // ---------- 检测 ----------

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun installedWallets(context: Context): List<WalletKind> =
        WalletKind.entries.filter { isInstalled(context, it.packageName) }

    fun primaryWallet(context: Context): WalletKind? = installedWallets(context).firstOrNull()

    fun lsposedInstalled(context: Context): Boolean = LsposedManager.isInstalled(context)

    // ---------- 打开 ----------

    fun openPackage(context: Context, packageName: String): Boolean = try {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalStateException("no launch intent for $packageName")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        true
    } catch (t: Throwable) {
        android.util.Log.w(TAG, "openPackage($packageName) failed: $t")
        false
    }

    fun openWallet(context: Context, kind: WalletKind?): Boolean {
        if (kind == null) return false
        if (openPackage(context, kind.packageName)) return true
        return openWalletByActivity(context, kind.packageName)
    }

    /** 卡包没有 launcher intent（系统应用）时，枚举 Activity 逐个尝试。 */
    private fun openWalletByActivity(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val activities = try {
            pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES).activities?.map { it.name }.orEmpty()
        } catch (t: Throwable) {
            return false
        }
        // 卡包没有 launcher：先试「双击快捷页」（卡面视图，最直观），再试 MainActivity。
        val explicit = listOf(
            "$packageName.ui.quick.DoubleClickActivity",
            "$packageName.ui.MainActivity",
        ).filter { activities.contains(it) }
        val preferred = explicit + activities.filter { name ->
            listOf("main", "home", "wallet", "cardlist").any { name.contains(it, ignoreCase = true) }
        } + activities
        for (name in preferred.distinct()) {
            try {
                val intent = Intent().apply {
                    component = ComponentName(packageName, name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (pm.resolveActivity(intent, 0) == null) continue
                context.startActivity(intent)
                return true
            } catch (_: Throwable) {
                // 非导出/不存在，试下一个
            }
        }
        return false
    }

    /** 优先精确组件，其次启动 Intent（LSPosed 管理器部分版本没有 launcher intent）。 */
    fun openLsposed(context: Context): Boolean {
        if (LsposedManager.open(context)) return true
        try {
            val component = ComponentName(LSPOSED_PACKAGE, LSPOSED_MAIN_ACTIVITY)
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                context.startActivity(intent)
                return true
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "openLsposed by component failed: $t")
        }
        return openPackage(context, LSPOSED_PACKAGE)
    }

    // ---------- 桌面图标 ----------

    private fun launcherAlias(context: Context): ComponentName =
        ComponentName(context.packageName, context.packageName + LAUNCHER_ALIAS_SUFFIX)

    /** 显示 / 隐藏桌面图标（只影响 activity-alias，不影响 LSPosed 的模块设置入口）。 */
    fun setLauncherVisible(context: Context, visible: Boolean): Boolean = try {
        val newState = if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(
            launcherAlias(context),
            newState,
            PackageManager.DONT_KILL_APP,
        )
        true
    } catch (t: Throwable) {
        android.util.Log.w(TAG, "setLauncherVisible($visible) failed: $t")
        false
    }

    /**
     * 启动自愈：组件实际状态和 prefs 不一致时以 prefs 为准（避免以前隐藏/恢复失败把图标弄丢）。
     * 返回 true 表示做了修正。
     */
    fun ensureLauncherMatchesPrefs(context: Context, hidden: Boolean): Boolean {
        val visible = isLauncherVisible(context) ?: return false
        if (visible == !hidden) return false
        return setLauncherVisible(context, !hidden)
    }

    /**
     * 隐藏图标后弹一个常驻通知作为入口（否则用户只能从 LSPosed 模块设置进来，容易找不到）。
     */
    fun postHiddenEntryNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    HIDDEN_CHANNEL_ID,
                    "隐藏入口",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT,
                )
                nm.createNotificationChannel(channel)
            }
            val intent = Intent(context, Class.forName("com.dsh.miuix.cardface.MainActivity")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pending = android.app.PendingIntent.getActivity(
                context,
                0,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = androidx.core.app.NotificationCompat.Builder(context, HIDDEN_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("卡面工坊已隐藏")
                .setContentText("点这里打开卡面工坊（或在设置里恢复桌面图标）")
                .setOngoing(true)
                .setContentIntent(pending)
                .build()
            nm.notify(HIDDEN_NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "postHiddenEntryNotification failed: $t")
        }
    }

    fun cancelHiddenEntryNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.cancel(HIDDEN_NOTIFICATION_ID)
        } catch (_: Throwable) {
        }
    }

    /** 桌面图标当前是否启用；未知返回 null。 */
    fun isLauncherVisible(context: Context): Boolean? = try {
        when (context.packageManager.getComponentEnabledSetting(launcherAlias(context))) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> false
            else -> true
        }
    } catch (_: Throwable) {
        null
    }

    // ---------- 强杀 / 重启 ----------

    /** 强杀卡包：优先 root（am force-stop），没 root 时退化为 killBackgroundProcesses。 */
    fun forceStopWallet(context: Context, kind: WalletKind?): Boolean {
        val pkg = kind?.packageName ?: return false
        val rootResult = RootShell.forceStop(pkg)
        if (rootResult.ok) return true
        android.util.Log.w(TAG, "root force-stop failed (rc=${rootResult.exitCode}), fallback to AM")
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(pkg)
            true
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "killBackgroundProcesses($pkg) failed: $t")
            false
        }
    }

    /** 重启卡包（保存替换图后建议执行，让卡包重新读图）。 */
    fun restartWallet(context: Context, kind: WalletKind?): Boolean {
        val pkg = kind?.packageName ?: return false
        val stopped = forceStopWallet(context, kind)
        // 强杀后稍等一下再拉起，避免被系统当作后台进程立刻回收。
        try {
            Thread.sleep(350)
        } catch (_: InterruptedException) {
        }
        val opened = openWallet(context, kind)
        return stopped || opened
    }

    /** 卡包是否正在运行（粗略判断，仅用于界面提示）。 */
    fun isWalletRunning(context: Context, kind: WalletKind?): Boolean {
        val pkg = kind?.packageName ?: return false
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            am.runningAppProcesses?.any { it.processName == pkg } == true
        } catch (_: Throwable) {
            false
        }
    }
}
