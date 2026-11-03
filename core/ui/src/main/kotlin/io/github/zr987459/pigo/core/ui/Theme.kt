package io.github.zr987459.pigo.core.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * pi-go 主题：dsh-Go 的雾蓝玻璃中性色 + 黛青强调色（浅色/深色），dsh-Go 字号阶梯与圆角词汇。
 *
 * - M3 颜色：`MaterialTheme.colorScheme`（手工配色，见 [DshPalette]）。
 * - 扩展颜色（渐变背景、玻璃面、用户气泡、代码井、状态色）：[DshTheme.colors]。
 * - 圆角：`MaterialTheme.shapes`（= [AppShape.Material]）或直接用 [AppShape]。
 *
 * @param dynamicColor 默认 **false**（与 dsh-Go 一致，不跟随壁纸取色）。为 true 时只替换 M3 颜色，扩展颜色不变。
 */
@Composable
fun PiGoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DshPalette.DarkScheme
        else -> DshPalette.LightScheme
    }
    CompositionLocalProvider(LocalDshColors provides if (darkTheme) DshColors.Dark else DshColors.Light) {
        MaterialTheme(
            colorScheme = colors,
            typography = PiGoTypography,
            shapes = AppShape.Material,
            content = content,
        )
    }
}
