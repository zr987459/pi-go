package io.github.zr987459.pigo.core.ui.calm

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zr987459.pigo.core.ui.R

/**
 * UI 形态的极简主题（DESIGN-r3 §4.2）：无玻璃、无渐变，只有一个强调色。
 * 正文系统 Sans 16sp / 行高 1.55；代码与命令 JetBrains Mono 14sp；圆角 12dp（输入框 20dp），8dp 网格。
 */
@Immutable
data class CalmColors(
    val background: Color,
    val surface: Color,
    val stroke: Color,
    val text: Color,
    val secondary: Color,
    val accent: Color,
    val success: Color,
    val error: Color,
    /** 用户消息浅色块。 */
    val userBlock: Color,
    val isDark: Boolean,
)

val CalmLight = CalmColors(
    background = Color(0xFFFAFAF8),
    surface = Color(0xFFFFFFFF),
    stroke = Color(0xFFE8E8E4),
    text = Color(0xFF1A1A1A),
    secondary = Color(0xFF6B6B6B),
    accent = Color(0xFF6D5BD0),
    success = Color(0xFF2F9E6B),
    error = Color(0xFFD0453B),
    userBlock = Color(0xFFF0EFEA),
    isDark = false,
)

val CalmDark = CalmColors(
    background = Color(0xFF111111),
    surface = Color(0xFF1A1A1A),
    stroke = Color(0xFF2A2A2A),
    text = Color(0xFFECECEC),
    secondary = Color(0xFF9A9A9A),
    accent = Color(0xFF9C8CFF),
    success = Color(0xFF4CC08A),
    error = Color(0xFFF0645A),
    userBlock = Color(0xFF222222),
    isDark = true,
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

@Immutable
data class CalmType(
    val body: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = (16 * 1.55).sp),
    val title: TextStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    val label: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    val caption: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    val code: TextStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.sp, lineHeight = 20.sp),
)

object CalmDims {
    val grid = 8.dp
    val radius = 12.dp
    val composerRadius = 20.dp
    val stroke = 1.dp
    /** 动效时长（§4.6）。 */
    const val MOTION_MS = 180
    const val FORM_FADE_MS = 120
}

val LocalCalmColors = staticCompositionLocalOf { CalmLight }
val LocalCalmType = staticCompositionLocalOf { CalmType() }

object Calm {
    val colors: CalmColors @Composable get() = LocalCalmColors.current
    val type: CalmType @Composable get() = LocalCalmType.current
}

@Composable
fun CalmTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) CalmDark else CalmLight
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = Color.Black, background = c.background, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surface, onSurfaceVariant = c.secondary,
            outline = c.stroke, outlineVariant = c.stroke, error = c.error, surfaceContainer = c.surface,
            surfaceContainerLow = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = Color.White, background = c.background, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surface, onSurfaceVariant = c.secondary,
            outline = c.stroke, outlineVariant = c.stroke, error = c.error, surfaceContainer = c.surface,
            surfaceContainerLow = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface,
        )
    }
    CompositionLocalProvider(LocalCalmColors provides c, LocalCalmType provides CalmType()) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(
                small = RoundedCornerShape(CalmDims.radius),
                medium = RoundedCornerShape(CalmDims.radius),
                large = RoundedCornerShape(CalmDims.radius),
            ),
            content = content,
        )
    }
}
