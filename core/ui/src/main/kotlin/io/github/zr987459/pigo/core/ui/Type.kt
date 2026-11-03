// 字号阶梯移植自 dsh-Go（https://github.com/ccch1mneyyy/dsh-Go，AGPL-3.0）app/lib/ui/theme/typography.dart。
// 等宽字体 JetBrains Mono（SIL OFL 1.1），来自 dsh-Go app/assets/fonts/。见 THIRD_PARTY_NOTICES.md。
package io.github.zr987459.pigo.core.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** JetBrains Mono：代码块、工具输出、行内代码。 */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

private val Even = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(size: Int, weight: FontWeight, height: Float, spacing: TextUnit = 0.sp) = TextStyle(
    fontSize = size.sp,
    fontWeight = weight,
    lineHeight = (size * height).sp,
    letterSpacing = spacing,
    lineHeightStyle = Even,
)

/** dsh-Go 的字号阶梯：display 细体（300），标题半粗（600），正文 16/1.65。中文跟随系统字体。 */
val PiGoTypography = Typography(
    displayLarge = style(36, FontWeight.Light, 1.2f),
    displayMedium = style(32, FontWeight.Light, 1.2f),
    displaySmall = style(28, FontWeight.Light, 1.25f),
    headlineLarge = style(26, FontWeight.SemiBold, 1.3f),
    headlineMedium = style(22, FontWeight.SemiBold, 1.3f),
    headlineSmall = style(20, FontWeight.SemiBold, 1.35f),
    titleLarge = style(18, FontWeight.SemiBold, 1.4f),
    titleMedium = style(16, FontWeight.SemiBold, 1.45f),
    titleSmall = style(14, FontWeight.SemiBold, 1.45f),
    bodyLarge = style(16, FontWeight.Normal, 1.65f),
    bodyMedium = style(14, FontWeight.Normal, 1.55f),
    bodySmall = style(12, FontWeight.Normal, 1.5f),
    labelLarge = style(14, FontWeight.SemiBold, 1.4f, 0.1.sp),
    labelMedium = style(12, FontWeight.SemiBold, 1.35f, 0.2.sp),
    labelSmall = style(11, FontWeight.Normal, 1.35f, 0.2.sp),
)

/** 代码块、工具输出用的等宽字体（13sp / 1.55）。 */
val MonoStyle = TextStyle(
    fontFamily = JetBrainsMono,
    fontSize = 13.sp,
    lineHeight = 20.sp,
    fontFeatureSettings = "calt 0, liga 0",
)

/** 其他排版样式（dsh-Go `AppType`）。 */
object AppType {
    /** 等宽：默认 13sp。 */
    fun mono(size: Int = 13, weight: FontWeight = FontWeight.Normal) =
        MonoStyle.copy(fontSize = size.sp, lineHeight = (size * 1.55f).sp, fontWeight = weight)

    /** 等宽小号标签（工具名、文件路径）。 */
    val MonoLabel = MonoStyle.copy(fontSize = 12.sp, lineHeight = 16.sp)

    /** 空会话的大字问候（「下午好」）：细体 40sp。 */
    val Greeting = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Light, lineHeight = 48.sp, letterSpacing = 0.02.em)

    /** 眉题：12sp 半粗、字距 0.8。设置分组标题等。 */
    val Eyebrow = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, lineHeight = 16.sp)

    /** 品牌字（抽屉顶部「pi-go」）。 */
    val Wordmark = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, lineHeight = 22.sp)
}
