package io.github.zr987459.pigo.core.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Teal = Color(0xFF00897B)
private val TealDark = Color(0xFF4DB6AC)

private val LightColors: ColorScheme = lightColorScheme(
    primary = Teal,
    secondary = Color(0xFF5C6BC0),
    tertiary = Color(0xFFEF6C00),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = TealDark,
    secondary = Color(0xFF9FA8DA),
    tertiary = Color(0xFFFFB74D),
)

/** pi-go 主题（MVP 期间冻结，DESIGN.md §2.1）。Android 12+ 默认跟随系统动态取色。 */
@Composable
fun PiGoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = PiGoTypography, content = content)
}
