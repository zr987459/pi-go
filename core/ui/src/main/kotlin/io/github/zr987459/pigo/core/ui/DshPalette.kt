// 配色移植自 dsh-Go（https://github.com/ccch1mneyyy/dsh-Go，AGPL-3.0）：
// app/lib/ui/theme/palette.dart、accent.dart（黛青 daiqing）、surfaces.dart。其上游部分源自 PiPilot（MIT）。
// 见仓库根 THIRD_PARTY_NOTICES.md §dsh-Go。
package io.github.zr987459.pigo.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * dsh-Go 的「雾蓝玻璃」中性色 + 「黛青」强调色（参考截图 docs/ref/dsh-go-home.webp 用的就是这一套）。
 * 手工构造，不走动态取色：低饱和、70% 中性面、≤5% 强调色。
 * 界面代码一律从 [MaterialTheme.colorScheme] 或 [DshTheme.colors] 取色，不直接用这些常量。
 */
@Deprecated("r3: r3/calm 删除（DESIGN-r3 X8，改用 core/ui/calm 的 CalmTheme / CalmIcons）")
object DshPalette {
    // ── 浅色：晨雾（页面渐变取自参考截图，色相偏湖青） ─────────────────
    val MistTop = Color(0xFFD9EEF9)
    val MistMid = Color(0xFFE9F2FA)
    val MistBottom = Color(0xFFE4EAF7)
    val Mist = Color(0xFFEEF3FA)
    val MistLowest = Color(0xFFFBFCFF)
    val MistLow = Color(0xFFF5F8FD)
    val MistContainer = Color(0xFFEDF1F8)
    val MistHigh = Color(0xFFE6EBF3)
    val MistHighest = Color(0xFFDEE4ED)

    val Ink = Color(0xFF1E232E)
    val InkMuted = Color(0xFF4F5562)
    val InkFaint = Color(0xFF646974)
    val InkGhost = Color(0xFFA1A5AC)
    val OutlineLight = Color(0xFF7E838D)
    val RuleLight = Color(0xFFDCE0E8)
    val ShadowInk = Color(0xFF0B152D)

    // ── 深色：深海石板 ─────────────────────────────────────────
    val NightTop = Color(0xFF1B232C)
    val NightBottom = Color(0xFF1A1C23)
    val Night = Color(0xFF1C2028)
    val NightLowest = Color(0xFF14171E)
    val NightLow = Color(0xFF232730)
    val NightContainer = Color(0xFF272C36)
    val NightHigh = Color(0xFF2D323C)
    val NightHighest = Color(0xFF343944)
    val Paper = Color(0xFFE6E9F0)
    val PaperMuted = Color(0xFFB6BBC5)
    val PaperFaint = Color(0xFF9A9EA9)
    val PaperGhost = Color(0xFF656971)
    val OutlineDark = Color(0xFF737781)
    val RuleDark = Color(0xFF343841)
    val Smoke = Color(0xFF292E39)
    val SheetDark = Color(0xFF232832)

    // ── 黛青强调色 ───────────────────────────────────────────
    val Seed = Color(0xFF0F5E7E) // 截图里「启动引擎」按钮的颜色（黛青 #236185 的壁纸取色版）
    val SeedPressed = Color(0xFF0A4D69)
    val SeedContainer = Color(0xFFDBEAF5)
    val OnSeedContainer = Color(0xFF123950)
    val Bubble = Color(0xFFD6E7F3)
    val Glow = Color(0xFFA2CAE5)
    val Haze = Color(0xFFC7D0EC)
    val SeedDark = Color(0xFF8CB9D7)
    val OnSeedDark = Color(0xFF0D1F2B)
    val SeedContainerDark = Color(0xFF274152)
    val OnSeedContainerDark = Color(0xFFD5E3ED)
    val BubbleDark = Color(0xFF26333C)
    val GlowDark = Color(0xFF466E88)
    val HazeDark = Color(0xFF444C66)

    // ── 中性辅助 / 语义色 ────────────────────────────────────
    val Slate = Color(0xFF545E6F)
    val SlateContainer = Color(0xFFE2E6F0)
    val SlateLift = Color(0xFFB3BED2)
    val OnSlateLift = Color(0xFF1A2230)
    val Dusk = Color(0xFF6B578F)
    val DuskContainer = Color(0xFFECE5FB)
    val OnDuskContainer = Color(0xFF3B2C54)
    val DuskLift = Color(0xFFBEAEDE)
    val OnDuskLift = Color(0xFF251C36)
    val DuskContainerDark = Color(0xFF3E3451)
    val OnDuskContainerDark = Color(0xFFE4DDF3)
    val Danger = Color(0xFFA54743)
    val OnDanger = Color(0xFFFDF9F8)
    val DangerContainer = Color(0xFFFFE2E0)
    val OnDangerContainer = Color(0xFF642724)
    val DangerLift = Color(0xFFE69089)
    val OnDangerLift = Color(0xFF331513)
    val DangerContainerDark = Color(0xFF542C29)
    val OnDangerContainerDark = Color(0xFFF8D9D7)
    val Success = Color(0xFF3E7D5A)
    val SuccessDark = Color(0xFF8CC7A4)
    val Warning = Color(0xFF9A6A1E)
    val WarningDark = Color(0xFFE2B66E)
    val Scrim = Color(0xFF0B111F)

    // ── 代码井 ───────────────────────────────────────────────
    val CodeWell = Color(0xFFEDF0F6)
    val CodeWellHeader = Color(0xFFE7EBF3)
    val CodeWellBorder = Color(0xFFDBDEE5)
    val CodeWellDark = Color(0xFF14171E)
    val CodeWellHeaderDark = Color(0xFF1A1E26)
    val CodeWellBorderDark = Color(0xFF2F333A)

    val LightScheme: ColorScheme = lightColorScheme(
        primary = Seed,
        onPrimary = Color.White,
        primaryContainer = SeedContainer,
        onPrimaryContainer = OnSeedContainer,
        secondary = Slate,
        onSecondary = Color.White,
        secondaryContainer = SlateContainer,
        onSecondaryContainer = Ink,
        tertiary = Dusk,
        onTertiary = Color.White,
        tertiaryContainer = DuskContainer,
        onTertiaryContainer = OnDuskContainer,
        error = Danger,
        onError = OnDanger,
        errorContainer = DangerContainer,
        onErrorContainer = OnDangerContainer,
        background = Mist,
        onBackground = Ink,
        surface = Mist,
        onSurface = Ink,
        surfaceVariant = MistHigh,
        onSurfaceVariant = InkMuted,
        surfaceDim = MistHighest,
        surfaceBright = MistLowest,
        surfaceContainerLowest = MistLowest,
        surfaceContainerLow = MistLow,
        surfaceContainer = MistContainer,
        surfaceContainerHigh = MistHigh,
        surfaceContainerHighest = MistHighest,
        surfaceTint = Color.Transparent,
        inverseSurface = Color(0xFF252A35),
        inverseOnSurface = Color(0xFFECEEF4),
        inversePrimary = SeedDark,
        outline = OutlineLight,
        outlineVariant = RuleLight,
        scrim = Scrim,
    )

    val DarkScheme: ColorScheme = darkColorScheme(
        primary = SeedDark,
        onPrimary = OnSeedDark,
        primaryContainer = SeedContainerDark,
        onPrimaryContainer = OnSeedContainerDark,
        secondary = SlateLift,
        onSecondary = OnSlateLift,
        secondaryContainer = Color(0x17FFFFFF),
        onSecondaryContainer = Paper,
        tertiary = DuskLift,
        onTertiary = OnDuskLift,
        tertiaryContainer = DuskContainerDark,
        onTertiaryContainer = OnDuskContainerDark,
        error = DangerLift,
        onError = OnDangerLift,
        errorContainer = DangerContainerDark,
        onErrorContainer = OnDangerContainerDark,
        background = Night,
        onBackground = Paper,
        surface = Night,
        onSurface = Paper,
        surfaceVariant = NightHigh,
        onSurfaceVariant = PaperMuted,
        surfaceDim = NightLowest,
        surfaceBright = NightHighest,
        surfaceContainerLowest = NightLowest,
        surfaceContainerLow = NightLow,
        surfaceContainer = NightContainer,
        surfaceContainerHigh = NightHigh,
        surfaceContainerHighest = NightHighest,
        surfaceTint = Color.Transparent,
        inverseSurface = Paper,
        inverseOnSurface = Ink,
        inversePrimary = Seed,
        outline = OutlineDark,
        outlineVariant = RuleDark,
        scrim = Color.Black,
    )
}
