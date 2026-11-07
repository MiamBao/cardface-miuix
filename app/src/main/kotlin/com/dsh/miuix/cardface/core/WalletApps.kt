package com.dsh.miuix.cardface.core

import android.content.Context
import android.content.pm.PackageManager

/** 检测卡包与 LSPosed 环境。 */
object WalletApps {

    fun isInstalled(context: Context, pkg: String): Boolean =
        try {
            context.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    fun installed(context: Context): List<WalletKind> =
        WalletKind.entries.filter { isInstalled(context, it.packageName) }

    fun primary(context: Context): WalletKind? = installed(context).firstOrNull()

    fun lsposedInstalled(context: Context): Boolean = LsposedManager.isInstalled(context)

    fun status(context: Context, prefs: AppPrefs): ModuleStatus {
        val kind = primary(context)
        val lastHook = prefs.lastHookAt()
        return ModuleStatus(
            lsposedInstalled = lsposedInstalled(context) ||
                LsposedManager.hasFrameworkTrace(context, lastHook),
            moduleActivated = lastHook > 0L,
            walletInstalled = kind != null,
            walletKind = kind,
            lastHookAt = lastHook,
        )
    }
}
