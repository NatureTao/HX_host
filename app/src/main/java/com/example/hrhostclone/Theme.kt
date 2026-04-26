package com.example.hrhostclone

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

// ==================== 主题枚举 ====================

enum class AppThemeMode(val label: String) {
    Default("默认"),
    Bilibili("哔哩哔哩")
}

// ==================== 颜色定义 ====================

data class HXExtraColors(
    val pageBackground: Color,
    val cardBackground: Color,
    val panelBackground: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val mutedText: Color,
    val success: Color,
    val error: Color,
    val border: Color,
    // 哔哩哔哩特色色
    val bilibiliBlue: Color = Color(0xFF00AEEC),
    val bilibiliPink: Color = Color(0xFFFB7299),
    val navBarBackground: Color = Color.White
)

// ==================== CompositionLocal ====================

val LocalHXExtraColors = staticCompositionLocalOf<HXExtraColors> {
    error("No HXExtraColors provided")
}

val LocalAppThemeMode = staticCompositionLocalOf<AppThemeMode> {
    error("No AppThemeMode provided")
}

object HXExtraTheme {
    val colors: HXExtraColors
        @Composable
        get() = LocalHXExtraColors.current

    val themeMode: AppThemeMode
        @Composable
        get() = LocalAppThemeMode.current
}

// ==================== 主题配色方案 ====================

object ThemePresets {

    /**
     * 默认主题
     */
    val default = HXExtraColors(
        pageBackground = Color(0xFFF5F5F5),
        cardBackground = Color.White,
        panelBackground = Color(0xFFEFEFEF),
        primaryText = Color(0xFF222222),
        secondaryText = Color(0xFF555555),
        mutedText = Color(0xFF777777),
        success = Color(0xFF2E7D32),
        error = Color(0xFFD32F2F),
        border = Color(0xFFDADADA),
    )

    /**
     * 哔哩哔哩主题
     * 主色：粉蓝 #00AEEC
     * 重点按钮：粉色 rgb(251, 114, 153) = #FB7299
     */
    val bilibili = HXExtraColors(
        pageBackground = Color(0xFFF0F8FF),        // 淡蓝白背景
        cardBackground = Color.White,
        panelBackground = Color(0xFFE8F4FD),       // 浅粉蓝面板
        primaryText = Color(0xFF1A1A2E),           // 深色文字
        secondaryText = Color(0xFF4A6572),         // 灰蓝次要文字
        mutedText = Color(0xFF6B8A9A),             // 淡灰蓝提示文字
        success = Color(0xFF4CAF50),
        error = Color(0xFFE57373),
        border = Color(0xFFB3E5FC),                // 粉蓝边框
        bilibiliBlue = Color(0xFF00AEEC),          // 哔哩哔哩粉蓝
        bilibiliPink = Color(0xFFFB7299),        // 哔哩哔哩粉色
        navBarBackground = Color(0xFFFB7299)           // 哔哩哔哩粉色
    )

    /**
     * 根据枚举获取配色
     */
    fun getColors(mode: AppThemeMode): HXExtraColors {
        return when (mode) {
            AppThemeMode.Default -> default
            AppThemeMode.Bilibili -> bilibili
        }
    }
}

// ==================== 主题包装器 ====================

@Composable
fun AppTheme(
    themeMode: AppThemeMode = AppThemeMode.Default,
    content: @Composable () -> Unit
) {
    val colors = ThemePresets.getColors(themeMode)

    CompositionLocalProvider(
        LocalHXExtraColors provides colors,
        LocalAppThemeMode provides themeMode
    ) {
        content()
    }
}