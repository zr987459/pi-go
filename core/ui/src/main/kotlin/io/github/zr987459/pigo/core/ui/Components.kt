// 通用组件移植自 dsh-Go（https://github.com/ccch1mneyyy/dsh-Go，AGPL-3.0）：
// app/lib/ui/theme/backdrop.dart（AppBackdrop）、glass.dart（GlassSurface）、common/glass_pill.dart、
// common/status_dot.dart、common/status_chip.dart、common/pressable.dart。Flutter → Compose 逐项翻译。
// 见 THIRD_PARTY_NOTICES.md §dsh-Go。
package io.github.zr987459.pigo.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

// ════════════════════════════════ 背景 ════════════════════════════════

/**
 * 页面背景（dsh-Go `AppBackdrop`）：竖向三段渐变 + 右上主色光晕 + 左下雾紫光晕。
 * 放在页面最底层，上面的 Scaffold/Surface 用透明底。
 */
@Deprecated("r3: r3/calm 删除（DESIGN-r3 X8，改用 core/ui/calm 的 CalmTheme / CalmIcons）")
@Composable
fun AppBackdrop(
    modifier: Modifier = Modifier,
    glow: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val c = DshTheme.colors
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(c.backdropTop, c.backdropMid, c.backdropBottom)))
            .drawBehind {
                if (!glow) return@drawBehind
                val longest = max(size.width, size.height)
                fun glowAt(center: Offset, radius: Float, color: Color) = drawCircle(
                    brush = Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = center, radius = radius),
                    radius = radius,
                    center = center,
                )
                glowAt(Offset(size.width * 0.92f, 0f), longest * 0.62f, c.glowPrimary)
                glowAt(Offset(0f, size.height), longest * 0.55f, c.glowSecondary)
            },
        content = content,
    )
}

/** 便捷 Modifier：只要渐变，不要光晕（列表项、抽屉内部）。 */
@Deprecated("r3: r3/calm 删除（DESIGN-r3 X8，改用 core/ui/calm 的 CalmTheme / CalmIcons）")
fun Modifier.dshGradient(colors: DshColors): Modifier =
    background(Brush.verticalGradient(listOf(colors.backdropTop, colors.backdropMid, colors.backdropBottom)))

// ════════════════════════════════ 玻璃面 ════════════════════════════════

/** 玻璃层级（dsh-Go `GlassTier`）。 */
enum class GlassTier { Chrome, Sheet, Card, Pill }

@Composable
fun glassStyle(tier: GlassTier): GlassStyle = when (tier) {
    GlassTier.Chrome -> DshTheme.colors.chrome
    GlassTier.Sheet -> DshTheme.colors.sheet
    GlassTier.Card -> DshTheme.colors.card
    GlassTier.Pill -> DshTheme.colors.pill
}

/**
 * 玻璃面（dsh-Go `GlassSurface`）：填充 + 0.5dp 发丝描边 + 冷墨阴影。可点时带按压缩放（0.97）与水波纹。
 */
@Deprecated("r3: r3/calm 删除（DESIGN-r3 X8，改用 core/ui/calm 的 CalmTheme / CalmIcons）")
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    tier: GlassTier = GlassTier.Card,
    shape: Shape = AppShape.Md,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val s = glassStyle(tier)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && onClick != null) AppMotion.PressScale else 1f,
        tween(if (pressed) AppMotion.Press else 200),
        label = "press",
    )
    Box(
        modifier
            .scale(scale)
            .then(if (s.elevation > 0.dp) Modifier.shadow(s.elevation, shape, clip = false, ambientColor = s.shadow, spotColor = s.shadow) else Modifier)
            .clip(shape)
            .background(s.fill)
            .border(0.5.dp, s.hairline, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = ripple(),
                        enabled = enabled,
                        onClickLabel = onClickLabel,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        content = content,
    )
}

/** 卡片：Card 层玻璃 + md 圆角 + 14/16 内边距。 */
@Deprecated("r3: r3/calm 删除（DESIGN-r3 X8，改用 core/ui/calm 的 CalmTheme / CalmIcons）")
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = AppShape.Md,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(modifier.fillMaxWidth(), GlassTier.Card, shape, onClick) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(AppSpace.Sm), content = content)
    }
}

// ════════════════════════════════ 胶囊按钮 ════════════════════════════════

/** 顶栏/浮动胶囊的标准高度（参考截图：48dp）。 */
val ChromePillHeight: Dp = 48.dp

/**
 * 白色悬浮胶囊（顶栏的会话名、「选择模型▾」）：Chrome 层玻璃 + 胶囊形。
 */
@Composable
fun ChromePill(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp),
    onClickLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    GlassSurface(
        modifier.defaultMinSize(minHeight = ChromePillHeight),
        GlassTier.Chrome,
        AppShape.Pill,
        onClick,
        onClickLabel = onClickLabel,
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(contentPadding).defaultMinSize(minHeight = ChromePillHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

/** 白色悬浮圆形图标按钮（顶栏菜单、新建对话）。 */
@Composable
fun ChromeIconButton(
    glyph: Glyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = ChromePillHeight,
    iconSize: Dp = 22.dp,
    tier: GlassTier = GlassTier.Chrome,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    GlassSurface(
        modifier.size(size).semantics { this.contentDescription = contentDescription },
        tier,
        CircleShape,
        onClick,
        enabled,
    ) {
        AppIcon(glyph, null, Modifier.align(Alignment.Center), size = iconSize, tint = if (enabled) tint else tint.copy(alpha = 0.38f))
    }
}

/**
 * 主色实心胶囊按钮（空会话的「启动引擎」）：深青底、白字、labelLarge 加大到 17sp。
 */
@Composable
fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: Glyph? = null,
) {
    PillButton(text, onClick, modifier, enabled, leading, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
}

/** 次要胶囊按钮（板岩蓝灰底）。 */
@Composable
fun TonalPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: Glyph? = null,
) {
    PillButton(text, onClick, modifier, enabled, leading, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
}

@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    leading: Glyph?,
    container: Color,
    content: Color,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) AppMotion.PressScale else 1f, tween(if (pressed) AppMotion.Press else 200), label = "press")
    val bg by animateColorAsState(if (enabled) container else container.copy(alpha = 0.38f), tween(AppMotion.Quick), label = "bg")
    Row(
        modifier
            .scale(scale)
            .clip(AppShape.Pill)
            .background(bg)
            .clickable(interaction, ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 28.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (enabled) content else content.copy(alpha = 0.6f)
        if (leading != null) {
            AppIcon(leading, null, size = 18.dp, tint = fg)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 17.sp, letterSpacing = 0.5.sp), color = fg)
    }
}

// ════════════════════════════════ 图标 ════════════════════════════════

private val IconFamily = FontFamily(Font(R.font.dsh_icons))

/**
 * 画一个 [Glyph]（dsh-Go 图标字体，Phosphor 线框风格）。颜色默认取 [LocalContentColor]。
 * [contentDescription] 为 null 表示装饰性图标。
 */
@Composable
fun AppIcon(
    glyph: Glyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    tint: Color = LocalContentColor.current,
) {
    val sp = with(androidx.compose.ui.platform.LocalDensity.current) { size.toSp() }
    Box(
        modifier
            .size(size)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph.code.toString(),
            style = TextStyle(fontFamily = IconFamily, fontSize = sp, lineHeight = sp, color = tint, textAlign = TextAlign.Center),
        )
    }
}

// ════════════════════════════════ 状态 ════════════════════════════════

/** 状态色调（dsh-Go `StatusTone`）。 */
enum class StatusTone { Ok, Busy, Warn, Error, Idle }

@Composable
fun statusColor(tone: StatusTone): Color = when (tone) {
    StatusTone.Ok -> DshTheme.colors.success
    StatusTone.Busy -> MaterialTheme.colorScheme.primary
    StatusTone.Warn -> DshTheme.colors.warning
    StatusTone.Error -> DshTheme.colors.danger
    StatusTone.Idle -> DshTheme.colors.idle
}

/** 状态点（dsh-Go `StatusDot`）：Busy 时 1.2s 呼吸（不透明度 1 ↔ 0.35），颜色切换 160ms 过渡。 */
@Composable
fun StatusDot(tone: StatusTone, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val color by animateColorAsState(statusColor(tone), tween(AppMotion.Quick), label = "dot")
    val alpha = if (tone == StatusTone.Busy) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "a").value
    } else {
        1f
    }
    Box(modifier.size(size).clip(CircleShape).background(color.copy(alpha = color.alpha * alpha)))
}

/** 状态胶囊（dsh-Go `StatusChip`）：点 + 文字，淡色底。 */
@Composable
fun StatusPill(tone: StatusTone, label: String, modifier: Modifier = Modifier) {
    val dark = DshTheme.isDark
    val bg = when (tone) {
        StatusTone.Idle, StatusTone.Busy ->
            if (dark) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        else -> statusColor(tone).copy(alpha = if (dark) 0.16f else 0.12f)
    }
    Row(
        modifier.clip(AppShape.Pill).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(tone, size = 6.dp)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 分组眉题（设置页分组标题等）。 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = AppType.Eyebrow,
        color = DshTheme.colors.textFaint,
        modifier = modifier.padding(start = 4.dp, top = AppSpace.Lg, bottom = AppSpace.Sm),
    )
}

/** 让子树内的文字/图标默认用 [color]。 */
@Composable
fun ProvideContentColor(color: Color, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalContentColor provides color, content = content)

// ════════════════════════════════ 旧 API（保留） ════════════════════════════════

/** 占位页骨架：标题 + 说明 + 可选内容。各 feature 的 Placeholder.kt 使用。 */
@Composable
fun PlaceholderScreen(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    testTag: String = "placeholder:$title",
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp).testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        content()
    }
}

/** 通用信息卡片（现为 [GlassCard] 外观）。 */
@Composable
fun InfoCard(title: String, body: String, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 状态芯片（只读）。新代码请用 [StatusPill]。 */
@Deprecated("用 StatusPill(tone, label)", ReplaceWith("StatusPill(StatusTone.Idle, label, modifier)"))
@Composable
fun StatusChip(label: String, modifier: Modifier = Modifier) {
    StatusPill(StatusTone.Idle, label, modifier)
}
