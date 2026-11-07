package com.dsh.miuix.cardface

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
