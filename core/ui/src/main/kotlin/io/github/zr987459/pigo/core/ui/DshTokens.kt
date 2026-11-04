// 设计词汇移植自 dsh-Go（https://github.com/ccch1mneyyy/dsh-Go，AGPL-3.0）：
// app/lib/ui/theme/surfaces.dart、shapes.dart、motion.dart。见 THIRD_PARTY_NOTICES.md §dsh-Go。
package io.github.zr987459.pigo.core.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 一层玻璃面的样式（dsh-Go `GlassStyle`）：填充、发丝描边、阴影。Compose 版不做背景模糊。 */
@Deprecated("r3: r3/calm 删除（DESIGN-r3 X8，改用 core/ui/calm 的 CalmTheme / CalmIcons）")
@Immutable
data class GlassStyle(
    val fill: Color,
    val hairline: Color,
    val shadow: Color,
    val elevation: Dp,
)

/**
 * M3 ColorScheme 装不下的 dsh-Go 颜色（dsh-Go `DshSurfaces` + 代码井 + 状态色）。
 * 通过 [DshTheme.colors] 读取。
 */
@Deprecated("r3: r3/calm 删除（DESIGN-r3 X8，改用 core/ui/calm 的 CalmTheme / CalmIcons）")
@Immutable
data class DshColors(
    val isDark: Boolean,
    val backdropTop: Color,
    val backdropMid: Color,
    val backdropBottom: Color,
    val glowPrimary: Color,
    val glowSecondary: Color,
    val userBubble: Color,
    val onUserBubble: Color,
    val scrim: Color,
    /** 顶部胶囊、输入条：最实的一层（浅色≈白）。 */
    val chrome: GlassStyle,
    /** 底部弹层、抽屉、对话框。 */
    val sheet: GlassStyle,
    /** 卡片（工具卡、设置分组）。 */
    val card: GlassStyle,
    /** 小胶囊、Chip、圆形图标底。 */
    val pill: GlassStyle,
    val textFaint: Color,
    val textGhost: Color,
    val codeWell: Color,
    val codeWellHeader: Color,
    val codeWellBorder: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    /** 「已停止」等中性状态点。 */
    val idle: Color,
) {
    companion object {
        val Light = DshColors(
            isDark = false,
            backdropTop = DshPalette.MistTop,
            backdropMid = DshPalette.MistMid,
            backdropBottom = DshPalette.MistBottom,
            glowPrimary = DshPalette.Glow.copy(alpha = 0.42f),
            glowSecondary = DshPalette.Haze.copy(alpha = 0.38f),
            userBubble = DshPalette.Bubble,
            onUserBubble = DshPalette.Ink,
            scrim = DshPalette.Scrim.copy(alpha = 0.32f),
            chrome = GlassStyle(Color.White.copy(alpha = 0.92f), DshPalette.ShadowInk.copy(alpha = 0.06f), DshPalette.ShadowInk.copy(alpha = 0.14f), 10.dp),
            sheet = GlassStyle(DshPalette.MistLow.copy(alpha = 0.97f), DshPalette.ShadowInk.copy(alpha = 0.07f), DshPalette.ShadowInk.copy(alpha = 0.18f), 16.dp),
            card = GlassStyle(Color.White.copy(alpha = 0.72f), DshPalette.ShadowInk.copy(alpha = 0.06f), Color.Transparent, 0.dp),
            pill = GlassStyle(Color.White.copy(alpha = 0.66f), DshPalette.ShadowInk.copy(alpha = 0.07f), Color.Transparent, 0.dp),
            textFaint = DshPalette.InkFaint,
            textGhost = DshPalette.InkGhost,
            codeWell = DshPalette.CodeWell,
            codeWellHeader = DshPalette.CodeWellHeader,
            codeWellBorder = DshPalette.CodeWellBorder,
            success = DshPalette.Success,
            warning = DshPalette.Warning,
            danger = DshPalette.Danger,
            idle = DshPalette.InkGhost,
        )

        val Dark = DshColors(
            isDark = true,
            backdropTop = DshPalette.NightTop,
            backdropMid = DshPalette.Night,
            backdropBottom = DshPalette.NightBottom,
            glowPrimary = DshPalette.GlowDark.copy(alpha = 0.28f),
            glowSecondary = DshPalette.HazeDark.copy(alpha = 0.24f),
            userBubble = DshPalette.BubbleDark,
            onUserBubble = DshPalette.Paper,
            scrim = Color.Black.copy(alpha = 0.48f),
            chrome = GlassStyle(DshPalette.Smoke.copy(alpha = 0.96f), Color.White.copy(alpha = 0.09f), Color.Black.copy(alpha = 0.5f), 10.dp),
            sheet = GlassStyle(DshPalette.SheetDark, Color.White.copy(alpha = 0.08f), Color.Black.copy(alpha = 0.55f), 16.dp),
            card = GlassStyle(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.07f), Color.Transparent, 0.dp),
            pill = GlassStyle(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.08f), Color.Transparent, 0.dp),
            textFaint = DshPalette.PaperFaint,
            textGhost = DshPalette.PaperGhost,
            codeWell = DshPalette.CodeWellDark,
            codeWellHeader = DshPalette.CodeWellHeaderDark,
            codeWellBorder = DshPalette.CodeWellBorderDark,
            success = DshPalette.SuccessDark,
            warning = DshPalette.WarningDark,
            danger = DshPalette.DangerLift,
            idle = DshPalette.PaperGhost,
        )
    }
}

internal val LocalDshColors = staticCompositionLocalOf { DshColors.Light }

/** 主题扩展入口：`DshTheme.colors.chrome.fill`、`DshTheme.colors.userBubble` 等。 */
@Deprecated("r3: r3/calm 删除（DESIGN-r3 X8，改用 core/ui/calm 的 CalmTheme / CalmIcons）")
object DshTheme {
    val colors: DshColors
        @Composable @ReadOnlyComposable get() = LocalDshColors.current

    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalDshColors.current.isDark

    val shapes: Shapes
        @Composable @ReadOnlyComposable get() = MaterialTheme.shapes
}

/**
 * 圆角词汇：五档 + 胶囊（dsh-Go `AppShape`）。
 * xs 6 行内代码/角标 · sm 10 Chip/小按钮 · md 14 卡片/代码井/菜单 · lg 20 用户气泡/设置分组卡 · xl 28 输入条/弹层/对话框 · pill 胶囊。
 */
object AppShape {
    val Xs = RoundedCornerShape(6.dp)
    val Sm = RoundedCornerShape(10.dp)
    val Md = RoundedCornerShape(14.dp)
    val Lg = RoundedCornerShape(20.dp)
    val Xl = RoundedCornerShape(28.dp)
    val Pill = RoundedCornerShape(percent = 50)
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val Card get() = Md
    val Bubble get() = Lg
    val Dialog get() = Xl

    /** 给 MaterialTheme 的 Shapes：对话框/菜单/卡片等 M3 组件自动套用。 */
    val Material = Shapes(extraSmall = Xs, small = Sm, medium = Md, large = Lg, extraLarge = Xl)
}

/** 间距阶梯（dsh-Go `AppSpace`）。 */
object AppSpace {
    val Xxs = 2.dp
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Xxl = 24.dp
    val Xxxl = 32.dp
    /** 页面水平留白。 */
    val Gutter = 16.dp
    /** 同一轮内部的竖向间距。 */
    val InTurn = 8.dp
    /** 换轮的竖向间距。 */
    val BetweenTurns = 24.dp
    /** 正文最大阅读宽度。 */
    val ReadingWidth = 720.dp
    /** 浮动控件（输入条、顶部胶囊）离屏幕边的距离。 */
    val FloatInset = 12.dp
}

/** 动效时长与曲线（dsh-Go `AppMotion`），单位毫秒。 */
object AppMotion {
    const val Press = 90
    const val Quick = 160
    const val Standard = 240
    const val Collapse = 280
    const val Emphasized = 340
    const val SheetIn = 380
    const val SheetOut = 260
    const val Slow = 480
    const val Breath = 2400
    const val Stagger = 30
    const val StaggerLimit = 8
    const val PressScale = 0.97f
    /** 新消息浮起的距离。 */
    val Rise = 8.dp
    val Glide: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    val Enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Exit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val SheetRise: Easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)
}
