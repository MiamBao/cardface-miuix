package com.dsh.miuix.cardface

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 初始化 LSPosed RemotePreferences 通道，并把本地映射迁移过去（异步、失败不影响使用）。
        try {
            com.dsh.miuix.cardface.core.RemotePrefsBridge.init()
            com.dsh.miuix.cardface.core.RemotePrefsBridge.migrateIfNeeded(this)
        } catch (_: Throwable) {
        }
        // 自愈：组件状态与设置不一致时修正；隐藏状态下保证通知入口存在。
        try {
            val prefs = com.dsh.miuix.cardface.core.AppPrefs(this)
            com.dsh.miuix.cardface.core.PackageOps.ensureLauncherMatchesPrefs(this, prefs.hideIcon)
            if (prefs.hideIcon) {
                com.dsh.miuix.cardface.core.PackageOps.postHiddenEntryNotification(this)
            }
        } catch (_: Throwable) {
        }
        setContent { CardFaceApp() }
    }
}
