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
 * 界面主题（r5/openminis）：照 OpenMinis Android 端 `ui/theme/Theme.kt` + `ChatColors.kt`（iOS 系统色板）移植。
 * - 聊天页纯白 / 纯黑底，设置等分组页用 iOS 式分组底色（#F2F2F7 / #000）+ 白 / #1C1C1E 卡片；
 * - 强调色 = OpenMinis 的去饱和 iOS 蓝（#528AD2 / #6A94CE）；用户气泡 = 半透明灰 / 深色不透明石板蓝；
 * - 圆角 12 / 12 / 20 / 24 / 28（MinisShapes），输入卡片 20dp；发送键黑（浅色）/ 白（深色）圆形。
 * 字段名沿用 r3 的 CalmColors（background、surface、stroke、text、secondary、accent…），新增的字段对应 OpenMinis ChatPalette。
 */
@Immutable
data class CalmColors(
    /** 聊天页底色（OpenMinis ChatPalette.background）。 */
    val background: Color,
    /** 卡片 / 面板底色（分组卡片、弹出菜单、底部面板）。 */
    val surface: Color,
    /** 分隔线 / 细描边（separator / toolBorder）。 */
    val stroke: Color,
    val text: Color,
    val secondary: Color,
    val accent: Color,
    val success: Color,
    val error: Color,
    /** 用户消息气泡（ChatPalette.userBubble）。 */
    val userBlock: Color,
    val isDark: Boolean,
    /** 三级文字（tertiaryLabel）。 */
    val tertiary: Color = secondary,
    /** 分组页底色（iOS systemGroupedBackground）。 */
    val groupedBg: Color = background,
    /** 输入卡片底色。 */
    val inputBg: Color = surface,
    /** 输入卡片里 ＋ / ／ 圆钮底色与描边。 */
    val inputIconBg: Color = surface,
    val inputIconBorder: Color = Color.Transparent,
    /** 工具胶囊底色与描边。 */
    val toolCapsuleBg: Color = surface,
    val toolBorder: Color = stroke,
    /** 发送键（可用 / 不可用）。 */
    val sendButton: Color = text,
    val sendButtonDisabled: Color = stroke,
    val codeBlockBg: Color = surface,
    val codeBlockText: Color = text,
    val inlineCodeBg: Color = surface,
    val inlineCodeText: Color = accent,
    val link: Color = accent,
    /** 思考块的蓝。 */
    val thinking: Color = accent,
    /** 新对话 FAB 的暖米色（minisFabColor）。 */
    val fab: Color = accent,
    /** 停止键 / 撤回键的红（iOS systemRed）。 */
    val danger: Color = error,
    val toastBg: Color = text,
)

/** OpenMinis LightChatPalette + LightColorScheme。 */
val CalmLight = CalmColors(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    stroke = Color(0x4D3C3C43),
    text = Color(0xFF000000),
    secondary = Color(0x993C3C43),
    accent = Color(0xFF528AD2),
    success = Color(0xFF34C759),
    error = Color(0xFFFF3B30),
    userBlock = Color(0x1E787880),
    isDark = false,
    tertiary = Color(0x4D3C3C43),
    groupedBg = Color(0xFFF2F2F7),
    inputBg = Color(0xFFFFFFFF),
    inputIconBg = Color(0xFFF2F2F7),
    inputIconBorder = Color.Transparent,
    toolCapsuleBg = Color(0xFFF2F2F7),
    toolBorder = Color(0x14000000),
    sendButton = Color(0xFF000000),
    sendButtonDisabled = Color(0x2E3C3C43),
    codeBlockBg = Color(0xFF000000),
    codeBlockText = Color(0xFF34C759),
    inlineCodeBg = Color(0xFFF2F2F7),
    inlineCodeText = Color(0xFFFF9500),
    link = Color(0xFF007AFF),
    thinking = Color(0xFF007AFF),
    fab = Color(0xFFB7AF96),
    danger = Color(0xFFFF3B30),
    toastBg = Color(0xE6202024),
)

/** OpenMinis DarkChatPalette（T153 安卓提亮版）+ DarkColorScheme。 */
val CalmDark = CalmColors(
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    stroke = Color(0x99545458),
    text = Color(0xFFFFFFFF),
    secondary = Color(0x99EBEBF5),
    accent = Color(0xFF6A94CE),
    success = Color(0xFF30D158),
    error = Color(0xFFFF453A),
    userBlock = Color(0xFF2F3A5C),
    isDark = true,
    tertiary = Color(0x4DEBEBF5),
    groupedBg = Color(0xFF000000),
    inputBg = Color(0xFF2C2C30),
    inputIconBg = Color(0xFF1C1C1E),
    inputIconBorder = Color(0xFF595959),
    toolCapsuleBg = Color(0xFF28282C),
    toolBorder = Color(0x40545458),
    sendButton = Color(0xFFFFFFFF),
    sendButtonDisabled = Color(0x2EEBEBF5),
    codeBlockBg = Color(0xFF262626),
    codeBlockText = Color(0xFF8CF38C),
    inlineCodeBg = Color(0xFF34343A),
    inlineCodeText = Color(0xFFFF9F0A),
    link = Color(0xFF0A84FF),
    thinking = Color(0xFF0A84FF),
    fab = Color(0xFF504C42),
    danger = Color(0xFFFF3B30),
    toastBg = Color(0xE63A3A3F),
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

@Immutable
data class CalmType(
    val body: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    /** 用户气泡正文（OpenMinis：bodyMedium 16.5sp）。 */
    val bubble: TextStyle = TextStyle(fontSize = 16.5.sp, lineHeight = 23.sp),
    val title: TextStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    /** 大标题（会话列表「pi-go」、设置页标题，OpenMinis 20sp Bold）。 */
    val largeTitle: TextStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp),
    val label: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    val caption: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    val code: TextStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.sp, lineHeight = 20.sp),
)

object CalmDims {
    val grid = 8.dp
    val radius = 12.dp
    val composerRadius = 20.dp
    /** 用户气泡圆角（OpenMinis 18dp）。 */
    val bubbleRadius = 18.dp
    /** 卡片 / 分组卡片圆角（MinisShapes.medium）。 */
    val cardRadius = 20.dp
    /** 底部面板 / 对话框圆角（MinisShapes.extraLarge）。 */
    val sheetRadius = 28.dp
    val stroke = 1.dp
    /** 动效时长（§4.6）。 */
    const val MOTION_MS = 220
    /** 页面推入 / 推出（会话列表 ↔ 聊天 ↔ 终端），OpenMinis 导航转场同量级。 */
    const val PAGE_MS = 280
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
            primary = c.accent, onPrimary = Color(0xFF003737), background = c.groupedBg, onBackground = c.text,
            surface = c.groupedBg, onSurface = c.text, surfaceVariant = c.surface, onSurfaceVariant = c.secondary,
            outline = c.stroke, outlineVariant = c.stroke, error = c.error, surfaceContainerLowest = c.groupedBg,
            surfaceContainer = c.surface, surfaceContainerLow = c.surface, surfaceContainerHigh = Color(0xFF2C2C2E),
            surfaceContainerHighest = Color(0xFF2C2C2E),
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = Color.White, background = c.groupedBg, onBackground = c.text,
            surface = c.groupedBg, onSurface = c.text, surfaceVariant = c.surface, onSurfaceVariant = c.secondary,
            outline = Color(0xFFD1D1D6), outlineVariant = Color(0xFFD1D1D6), error = c.error, surfaceContainerLowest = c.groupedBg,
            surfaceContainer = c.surface, surfaceContainerLow = c.surface, surfaceContainerHigh = Color(0xFFF7F7FA),
            surfaceContainerHighest = Color(0xFFF7F7FA),
        )
    }
    CompositionLocalProvider(LocalCalmColors provides c, LocalCalmType provides CalmType()) {
        MaterialTheme(
            colorScheme = scheme,
            // OpenMinis MinisShapes：菜单 12、卡片 20、抽屉 24、对话框 / 底部面板 28。
            shapes = Shapes(
                extraSmall = RoundedCornerShape(12.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(CalmDims.cardRadius),
                large = RoundedCornerShape(24.dp),
                extraLarge = RoundedCornerShape(CalmDims.sheetRadius),
            ),
            content = content,
        )
    }
}
