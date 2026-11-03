// 设置页组件移植自 dsh-Go（https://github.com/ccch1mneyyy/dsh-Go，AGPL-3.0）：
// app/lib/ui/settings/settings_widgets.dart（SettingsSection / SettingsTile / SettingsIcon / InfoRow）、
// common/app_sheet.dart（SheetGroup）、common/overview_card.dart（OverviewChevron）、
// settings/background_guide_page.dart（_Step / _VendorRow）、shell/page_frame.dart（大标题）、
// theme/app_theme.dart（按钮、输入框的尺寸与圆角）。Flutter → Compose 逐项翻译，尺寸照搬。
package io.github.zr987459.pigo.feature.settings.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zr987459.pigo.core.ui.AppIcon
import io.github.zr987459.pigo.core.ui.AppIcons
import io.github.zr987459.pigo.core.ui.AppMotion
import io.github.zr987459.pigo.core.ui.AppShape
import io.github.zr987459.pigo.core.ui.DshTheme
import io.github.zr987459.pigo.core.ui.GlassSurface
import io.github.zr987459.pigo.core.ui.GlassTier
import io.github.zr987459.pigo.core.ui.Glyph

/** settings_widgets.dart `SettingsMetrics`。 */
internal object SettingsMetrics {
    val inset = 16.dp
    val trailingPad = 12.dp
    val icon = 32.dp
    val iconGlyph = 18.dp
    val iconGap = 12.dp
    val rowHeight = 52.dp
    val rowHeightTwoLine = 64.dp
}

/** settings_screen.dart `SettingsHues`：分组的图标色（identity 下标）。 */
internal object SettingsHues {
    const val AI = 0
    const val ABILITIES = 4
    const val APPEARANCE = 1
    const val GENERAL = 5
    const val HOSTS = 6
    const val HELP = 7
}

/** semantic_colors.dart `DshColors.identity` / `onIdentity`（浅 / 深）。 */
private val IdentityLight = listOf(0xFFDAECFF, 0xFFE9E6FF, 0xFFF9E1F3, 0xFFFFE2E0, 0xFFF8E6D1, 0xFFE0EFD8, 0xFFD3F1E9, 0xFFD1F0F8)
private val OnIdentityLight = listOf(0xFF173F65, 0xFF3E3463, 0xFF562B4E, 0xFF602A2A, 0xFF563500, 0xFF2A4618, 0xFF00483D, 0xFF004552)
private val IdentityDark = listOf(0xFF243A50, 0xFF38334F, 0xFF472E42, 0xFF4E2E2D, 0xFF47341A, 0xFF2D3E24, 0xFF174038, 0xFF153E47)
private val OnIdentityDark = listOf(0xFFC5DEF8, 0xFFDBD6F7, 0xFFEFD0E7, 0xFFF7D0CD, 0xFFEED7BB, 0xFFCEE2C4, 0xFFBCE5DB, 0xFFBAE3ED)

@Composable @ReadOnlyComposable
internal fun identityColors(tint: Int): Pair<Color, Color> {
    val i = Math.floorMod(tint, 8)
    return if (DshTheme.isDark) Color(IdentityDark[i]) to Color(OnIdentityDark[i]) else Color(IdentityLight[i]) to Color(OnIdentityLight[i])
}

/** dsh-Go `groupTitleStyle`：labelLarge + 三级灰。 */
@Composable @ReadOnlyComposable
internal fun groupTitleStyle(): TextStyle = MaterialTheme.typography.labelLarge.copy(color = DshTheme.colors.textFaint)

/** settingsTitleStyle：bodyLarge 15sp / 1.4。 */
@Composable @ReadOnlyComposable
internal fun settingsTitleStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp)

/** page_frame.dart 的大标题：padding (24, 6, 24, 20)，headlineLarge + 可选 bodyMedium 副标题。 */
@Composable
internal fun LargeTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.fillMaxWidth().padding(start = 24.dp, top = 6.dp, end = 24.dp, bottom = 20.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * SettingsSection：外边距 (16, 0, 16, 24)；标题 labelLarge 三级灰，padding (8, 0, 8, 8)；
 * 下面是 20dp 圆角的玻璃卡（SheetGroup，上下各 4dp、行间 2dp）；可选脚注 bodySmall 三级灰，行高 1.55。
 */
@Composable
internal fun SettingsSection(
    title: String?,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        if (title != null) {
            Text(
                title,
                style = groupTitleStyle(),
                modifier = titleModifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp).semantics { heading() },
            )
        }
        GlassSurface(Modifier.fillMaxWidth(), tier = GlassTier.Card, shape = AppShape.Lg) {
            Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = (12 * 1.55).sp),
                color = DshTheme.colors.textFaint,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp),
            )
        }
    }
}

/** SettingsIcon：32dp 圆形底 + 18dp 字形，颜色取 identity[tint]（danger 用错误色容器）。 */
@Composable
internal fun SettingsIcon(glyph: Glyph, tint: Int = 0, danger: Boolean = false) {
    val (bg, fg) = if (danger) MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer else identityColors(tint)
    Box(Modifier.size(SettingsMetrics.icon).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        AppIcon(glyph, null, size = SettingsMetrics.iconGlyph, tint = fg)
    }
}

/** OverviewChevron：20dp 右箭头，三级灰，右移 3dp。 */
@Composable
internal fun Chevron() {
    AppIcon(AppIcons.disclosure, null, Modifier.offset(x = 3.dp), size = 20.dp, tint = DshTheme.colors.textFaint)
}

/**
 * SettingsTile：最小高度 52dp（有副标题 64dp），padding (16, 6, 12|16, 6)；
 * [图标 32 + 12] 标题 15sp（副标题 bodySmall 三级灰）… 右侧值 bodyMedium 次级色 / trailing / chevron。
 * 按下只有淡色高亮（onSurface 5%），没有水波（NoSplash）。
 */
@Composable
internal fun SettingsTile(
    title: String,
    modifier: Modifier = Modifier,
    icon: Glyph? = null,
    tint: Int = 0,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    danger: Boolean = false,
    chevron: Boolean? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val showChevron = chevron ?: (onTap != null && trailing == null)
    val endsInGlyph = showChevron || trailing != null
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onTap != null) Modifier.clickable(enabled = enabled, onClick = onTap) else Modifier)
            .heightIn(min = if (subtitle == null) SettingsMetrics.rowHeight else SettingsMetrics.rowHeightTwoLine)
            .padding(
                start = SettingsMetrics.inset,
                top = 6.dp,
                end = if (endsInGlyph) SettingsMetrics.trailingPad else SettingsMetrics.inset,
                bottom = 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            SettingsIcon(icon, tint, danger)
            Spacer(Modifier.width(SettingsMetrics.iconGap))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = settingsTitleStyle(),
                color = when {
                    danger -> scheme.error
                    !enabled -> scheme.onSurface.copy(alpha = 0.38f)
                    else -> scheme.onSurface
                },
            )
            if (subtitle != null) {
                Spacer(Modifier.height(1.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = DshTheme.colors.textFaint)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor ?: scheme.onSurfaceVariant, maxLines = 1)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (showChevron) {
            Spacer(Modifier.width(4.dp))
            Chevron()
        }
    }
}

/** InfoRow：标签在左（15sp）、值在右（bodyMedium 次级色，右对齐），最小高度 52dp。 */
@Composable
internal fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = SettingsMetrics.rowHeight).padding(horizontal = SettingsMetrics.inset, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = settingsTitleStyle(), color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** background_guide_page.dart `_Step`：24dp primaryContainer 圆里的序号 + 15sp 文本。 */
@Composable
internal fun StepRow(index: Int, text: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = SettingsMetrics.rowHeight)
            .padding(start = SettingsMetrics.inset, top = 8.dp, end = SettingsMetrics.inset, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(SettingsMetrics.icon), contentAlignment = Alignment.Center) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Text("$index", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.width(SettingsMetrics.iconGap))
        Text(text, style = settingsTitleStyle(), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

/** background_guide_page.dart `_VendorRow`：点开展开分步说明，箭头旋转 180°，240ms。 */
@Composable
internal fun ExpandRow(title: String, steps: List<String>, initiallyOpen: Boolean = false, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(initiallyOpen) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(AppMotion.Standard), label = "caret")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.heightIn(min = SettingsMetrics.rowHeight)
                .padding(start = SettingsMetrics.inset, top = 6.dp, end = SettingsMetrics.trailingPad, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = settingsTitleStyle(), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            AppIcon(AppIcons.expand, null, Modifier.rotate(turn), size = 20.dp, tint = DshTheme.colors.textFaint)
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(AppMotion.Standard)) + fadeIn(tween(AppMotion.Standard)),
            exit = shrinkVertically(tween(AppMotion.Standard)) + fadeOut(tween(AppMotion.Quick)),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = SettingsMetrics.inset, end = SettingsMetrics.inset, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                steps.forEachIndexed { i, s ->
                    Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 风险提示卡（dsh-Go 的 warning 语义色 12% 底 + shieldWarning 图标），用于 Key 安全提示等。 */
@Composable
internal fun NoticeCard(title: String, body: String, modifier: Modifier = Modifier) {
    val warn = DshTheme.colors.warning
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(AppShape.Lg)
            .background(warn.copy(alpha = if (DshTheme.isDark) 0.16f else 0.12f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AppIcon(AppIcons.securityWarning, null, size = 20.dp, tint = warn)
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodySmall.copy(lineHeight = (12 * 1.55).sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** app_theme.dart filledButtonTheme：最小高 50，左右 24，14dp 圆角，labelLarge 15sp。 */
@Composable
internal fun DshFilledButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = AppShape.Md,
        contentPadding = PaddingValues(horizontal = 24.dp),
        elevation = null,
        modifier = modifier.heightIn(min = 50.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp)) }
}

/** app_theme.dart outlinedButtonTheme：最小高 50，左右 22，14dp 圆角，描边 outline 40%（禁用 20%），文字 onSurface。 */
@Composable
internal fun DshOutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = AppShape.Md,
        contentPadding = PaddingValues(horizontal = 22.dp),
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = if (enabled) 0.4f else 0.2f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onSurface),
        modifier = modifier.heightIn(min = 50.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp)) }
}

/** app_theme.dart inputDecorationTheme：填充卡片底、14dp 圆角、outline 描边、聚焦主色。配合 shape = AppShape.Md 使用。 */
@Composable
internal fun dshFieldColors(): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    val fill = if (DshTheme.isDark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.72f)
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = fill,
        unfocusedContainerColor = fill,
        disabledContainerColor = fill,
        focusedBorderColor = scheme.primary,
        unfocusedBorderColor = scheme.outline,
        disabledBorderColor = scheme.outlineVariant,
        focusedLabelColor = scheme.primary,
        unfocusedLabelColor = scheme.onSurfaceVariant,
        focusedPlaceholderColor = DshTheme.colors.textFaint,
        unfocusedPlaceholderColor = DshTheme.colors.textFaint,
    )
}

/** [DshOutlinedButton] 的插槽版（与 M3 `OutlinedButton` 同参数顺序，便于替换）。 */
@Composable
internal fun DshOutlined(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = AppShape.Md,
        contentPadding = PaddingValues(horizontal = 22.dp),
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = if (enabled) 0.4f else 0.2f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onSurface),
        modifier = modifier.heightIn(min = 50.dp),
        content = content,
    )
}

/** 对话框主按钮：dsh-Go 对话框的 FilledButton（14dp 圆角、主色实心）。 */
@Composable
internal fun DshFilled(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = AppShape.Md,
        contentPadding = PaddingValues(horizontal = 24.dp),
        elevation = null,
        modifier = modifier.heightIn(min = 44.dp),
        content = content,
    )
}
