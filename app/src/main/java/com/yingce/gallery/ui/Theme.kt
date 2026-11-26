package com.yingce.gallery.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 主题：参考 ColorOS 17 相册的观感。
 *
 * - 浅色：接近纯白的底 + 低饱和灰阶 + 一个绿色强调色（取自应用图标）
 * - 深色：近黑底（不是纯黑，避免 OLED 上层次全丢）+ 提亮的灰阶
 * - 所有动效统一用 Spring 物理参数，见 [YingceMotion]
 */
object YingceColors {
    val Accent = Color(0xFF2BB673)        // 取自 logo 的绿色
    val AccentDark = Color(0xFF1E9A5E)
    val LightBg = Color(0xFFF7F8FA)
    val LightSurface = Color(0xFFFFFFFF)
    val DarkBg = Color(0xFF0E0F12)
    val DarkSurface = Color(0xFF17181D)
}

/** 全站统一的弹簧参数：低阻尼回弹只用在"出现"，高频交互用更"硬"的阻尼避免拖沓。 */
object YingceMotion {
    /** 面板/对话框出现：轻微回弹 */
    fun <T> expressive() = spring<T>(
        dampingRatio = 0.78f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** 列表项/角标等高频小动画：不拖泥带水 */
    fun <T> snappy() = spring<T>(
        dampingRatio = 1f,
        stiffness = Spring.StiffnessMedium,
    )

    /** 拖拽结束归位：明显回弹 */
    fun <T> bouncy() = spring<T>(
        dampingRatio = 0.62f,
        stiffness = Spring.StiffnessMediumLow,
    )
}

private val YingceTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 30.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp),
)

@Composable
fun YingceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val light = lightColorScheme(
        primary = YingceColors.Accent,
        onPrimary = Color.White,
        secondary = YingceColors.AccentDark,
        background = YingceColors.LightBg,
        onBackground = Color(0xFF1A1B1F),
        surface = YingceColors.LightSurface,
        onSurface = Color(0xFF1A1B1F),
        surfaceVariant = Color(0xFFEDEFF3),
        onSurfaceVariant = Color(0xFF6B7280),
        outline = Color(0xFFC7CCD6),
        error = Color(0xFFD9534F),
    )

    val dark = darkColorScheme(
        primary = YingceColors.Accent,
        onPrimary = Color(0xFF06241A),
        secondary = Color(0xFF6FD3A4),
        background = YingceColors.DarkBg,
        onBackground = Color(0xFFE8EAED),
        surface = YingceColors.DarkSurface,
        onSurface = Color(0xFFE8EAED),
        surfaceVariant = Color(0xFF23252B),
        onSurfaceVariant = Color(0xFF9AA1AC),
        outline = Color(0xFF3A3D45),
        error = Color(0xFFFF8A80),
    )

    MaterialTheme(
        colorScheme = if (darkTheme) dark else light,
        typography = YingceTypography,
        content = content,
    )
}
