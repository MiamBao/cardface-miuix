package com.dsh.miuix.cardface.core

import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 外观配置：动态取色 / 主题色 / 深色模式 / 过渡动画。
 *
 * 供 [CardFaceApp] 构造 Miuix [ThemeController]；设置页修改 [AppPrefs] 后 revision 自增，
 * 根组件重新计算本对象并重建 ThemeController，颜色即时生效。
 */
data class Appearance(
    val dynamicColor: Boolean = true,
    val keyColor: Color = Color(0xFF2F6BFF),
    /** 0 = 跟随系统，1 = 浅色，2 = 深色 */
    val darkMode: Int = 0,
    val animations: Boolean = true,
) {
    /**
     * Miuix 只有 Monet* 模式会消费 keyColor；所以「动态取色」和「自定义主题色」都走 Monet 模式：
     * dynamic=true 时 keyColor 传 null（跟随壁纸/系统取色），false 时传种子色生成调色板。
     */
    val colorSchemeMode: ColorSchemeMode
        get() = when (darkMode) {
            1 -> ColorSchemeMode.MonetLight
            2 -> ColorSchemeMode.MonetDark
            else -> ColorSchemeMode.MonetSystem
        }

    /** 「澎湃蓝」= Miuix 原生 HyperOS 配色：白/灰底 + #3482FF 主色（开关/按钮/选中态都是它）。 */
    private val isHyperOsDefault: Boolean
        get() = !dynamicColor && (
            keyColor == Color(0xFF3482FF) ||  // 澎湃蓝
                keyColor == Color(0xFF2F6BFF)     // 旧版默认蓝，迁移到澎湃蓝
            )

    fun createController(): ThemeController {
        if (isHyperOsDefault) {
            // 用 Miuix 自带 lightColorScheme()/darkColorScheme()：背景白、卡片/分组 #F7F7F7、主色 #3482FF。
            return ThemeController(
                colorSchemeMode = when (darkMode) {
                    1 -> ColorSchemeMode.Light
                    2 -> ColorSchemeMode.Dark
                    else -> ColorSchemeMode.System
                },
            )
        }
        val seed = when {
            !dynamicColor -> keyColor
            // Android 11 及以下没有系统动态取色，退回澎湃蓝，避免一片灰。
            android.os.Build.VERSION.SDK_INT < 31 -> Color(0xFF3482FF)
            else -> null
        }
        return ThemeController(
            colorSchemeMode = colorSchemeMode,
            keyColor = seed,
            colorSpec = ThemeColorSpec.Spec2021,
            paletteStyle = ThemePaletteStyle.TonalSpot,
        )
    }
}

/** 设置页可选的主题色。 */
object ThemeColors {
    data class Preset(val name: String, val color: Color)

    val presets: List<Preset> = listOf(
        Preset("澎湃蓝", Color(0xFF3482FF)),
        Preset("青碧", Color(0xFF00A6A6)),
        Preset("翠绿", Color(0xFF0E9F6E)),
        Preset("紫罗兰", Color(0xFF7C4DFF)),
        Preset("樱粉", Color(0xFFEC4899)),
        Preset("暖橙", Color(0xFFF59E0B)),
        Preset("绯红", Color(0xFFE5484D)),
        Preset("流金", Color(0xFFB88A2E)),
    )
}

fun AppPrefs.appearance(): Appearance = Appearance(
    dynamicColor = dynamicColor,
    keyColor = Color(keyColorArgb),
    darkMode = darkMode,
    animations = animations,
)
