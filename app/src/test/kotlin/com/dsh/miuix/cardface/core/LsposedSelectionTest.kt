package com.dsh.miuix.cardface.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * LsposedManager.selectManager 的纯逻辑单测。
 *
 * 关键回归：只用 `.XposedService` provider 判定的模块 App 不能被当成管理器
 * （本机曾因此「打开 LSPosed」跳到了别的软件）。
 */
class LsposedSelectionTest {

    private fun candidate(
        packageName: String,
        activities: List<String> = emptyList(),
        providers: List<String> = emptyList(),
        label: String = packageName,
    ) = LsposedManager.Candidate(
        packageName = packageName,
        label = label,
        activityNames = activities,
        providerAuthorities = providers,
    )

    @Test
    fun `module apps with only XposedService provider are not managers`() {
        val candidates = listOf(
            candidate(
                "com.example.deergod",
                providers = listOf("com.example.deergod.XposedService"),
            ),
            candidate(
                "com.example.piko",
                providers = listOf("com.example.piko.XposedService"),
            ),
        )
        assertNull(LsposedManager.selectManager(candidates, "com.dsh.miuix.cardface"))
    }

    @Test
    fun `parasitic host with injected manager activity is selected`() {
        val host = candidate(
            "com.android.shell",
            activities = listOf("org.lsposed.manager.ui.activity.MainActivity"),
            providers = listOf("com.android.shell.XposedService"),
        )
        val candidates = listOf(
            candidate("com.example.module", providers = listOf("com.example.module.XposedService")),
            host,
        )
        val selected = LsposedManager.selectManager(candidates, "com.dsh.miuix.cardface")
        assertEquals("com.android.shell", selected?.packageName)
        assertEquals("org.lsposed.manager.ui.activity.MainActivity", selected?.managerActivity)
    }

    @Test
    fun `normal lsposed manager package is selected`() {
        val manager = candidate(
            "org.lsposed.manager",
            activities = listOf("org.lsposed.manager.ui.activity.MainActivity"),
        )
        val candidates = listOf(
            candidate("com.android.shell", activities = listOf("org.lsposed.manager.ui.activity.MainActivity")),
            manager,
        )
        assertEquals("org.lsposed.manager", LsposedManager.selectManager(candidates, "x")?.packageName)
    }

    @Test
    fun `self package is ignored`() {
        val candidates = listOf(
            candidate(
                "com.dsh.miuix.cardface",
                providers = listOf("com.dsh.miuix.cardface.XposedService"),
                activities = listOf("org.lsposed.manager.ui.activity.MainActivity"),
            ),
        )
        assertNull(LsposedManager.selectManager(candidates, "com.dsh.miuix.cardface"))
    }

    @Test
    fun `module provider does not outrank real host`() {
        val candidates = listOf(
            candidate("com.example.module", providers = listOf("com.example.module.XposedService")),
            candidate(
                "com.android.shell",
                activities = listOf("org.lsposed.manager.ui.activity.MainActivity"),
            ),
        )
        assertEquals(
            "com.android.shell",
            LsposedManager.selectManager(candidates, "self")?.packageName,
        )
    }
}
