package io.github.zr987459.pigo.feature.term.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.zr987459.pigo.feature.term.theme.PiTheme
import io.github.zr987459.pigo.feature.term.theme.PiThemeLoader
import java.io.File

/** Compose 侧的 pi 主题：token → [Color]；空串 token（终端默认色）落到 [fg] / [bg]。 */
@Immutable
class PiTokens(val theme: PiTheme) {
    val bg: Color = if (theme.dark) Color(0xFF1B1D22) else Color(0xFFFAFAFA)
    val fg: Color = color("text") ?: if (theme.dark) Color(0xFFDEE0E1) else Color(0xFF3B3F41)

    fun color(token: String?): Color? = token?.let { theme[it] }?.let { Color(it.argb.toInt()) }

    fun fgOf(token: String?): Color = color(token) ?: fg

    fun bgOf(token: String?): Color = color(token) ?: Color.Transparent

    /** 编辑器边框色 = 思考档（pi `thinkingXxx` token）；`!` 模式用 `bashMode`。 */
    fun thinkingBorder(level: String?): Color = fgOf(
        when (level) {
            null, "off" -> "thinkingOff"
            "minimal" -> "thinkingMinimal"
            "low" -> "thinkingLow"
            "medium" -> "thinkingMedium"
            "high" -> "thinkingHigh"
            "xhigh" -> "thinkingXhigh"
            else -> "thinkingMax"
        },
    )
}

val LocalPiTokens = staticCompositionLocalOf<PiTokens> { error("PiTokens 未提供") }

/** JetBrains Mono（只读引用 `core/ui` 的字体资源，DESIGN-r3 §8.1）。 */
val JetBrainsMono: FontFamily = FontFamily(
    Font(io.github.zr987459.pigo.core.ui.R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(io.github.zr987459.pigo.core.ui.R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(io.github.zr987459.pigo.core.ui.R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/**
 * 主题选择与 pi `/settings › theme` 一致：`dark` / `light` 用打包的原版 JSON；`system`（或未设）跟随安卓深浅色；
 * 其他名字从 [customThemeDir]（`~/.pi/agent/themes/<名>.json`）读，读不到回退到深浅色。
 */
object TermThemes {
    fun load(context: Context, name: String?, systemDark: Boolean, customThemeDir: File? = null): PiTheme {
        val resolved = when (name) {
            null, "", "system" -> if (systemDark) "dark" else "light"
            else -> name
        }
        if (resolved == "dark" || resolved == "light") {
            return PiThemeLoader.parse(context.assets.open("pi-theme/$resolved.json").bufferedReader().use { it.readText() })
        }
        val f = customThemeDir?.let { File(it, "$resolved.json") }
        return runCatching { PiThemeLoader.parse(f!!.readText()) }.getOrElse {
            PiThemeLoader.parse(context.assets.open("pi-theme/${if (systemDark) "dark" else "light"}.json").bufferedReader().use { r -> r.readText() })
        }
    }
}

@Composable
fun rememberPiTokens(themeName: String?, customThemeDir: File? = null): PiTokens {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    return remember(themeName, dark, customThemeDir) { PiTokens(TermThemes.load(context, themeName, dark, customThemeDir)) }
}
