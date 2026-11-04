package io.github.zr987459.pigo.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/*
 * 设置页的极简组件（DESIGN-r3 §4.1、X8）：只用 Material 3 默认主题，不用玻璃、渐变、彩色图标。
 * r3/calm 的 CalmTheme 合入后由外层主题统一配色，这里不写死颜色。替代已删除的旧视觉组件。
 */

/** 输入框、按钮统一圆角。 */
internal val FieldShape: Shape = RoundedCornerShape(12.dp)

/** 兼容旧调用的分组色下标（已不再着色，保留常量避免改动调用处）。 */
internal object SettingsHues {
    const val AI = 0
    const val GENERAL = 0
    const val HOSTS = 0
    const val HELP = 0
}

/** 语义色：成功 / 警告 / 次要文字，全部取自当前 Material 配色。 */
internal object SettingsColors {
    val success: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
    val warning: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.tertiary
    val faint: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable @ReadOnlyComposable
internal fun groupTitleStyle(): TextStyle = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)

/** 页面底：纯背景色，无光晕。 */
@Composable
internal fun PlainBackdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
}

@Composable
internal fun LargeTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.fillMaxWidth().padding(start = 24.dp, top = 6.dp, end = 24.dp, bottom = 16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SettingsColors.faint)
        }
    }
}

/** 分组：标题 + 细分隔线下的行；没有卡片底。 */
@Composable
internal fun SettingsSection(
    title: String?,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        if (title != null) {
            Text(title, style = groupTitleStyle(), modifier = titleModifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp).semantics { heading() })
        }
        Column(Modifier.fillMaxWidth(), content = content)
        if (footer != null) {
            Text(footer, style = MaterialTheme.typography.bodySmall, color = SettingsColors.faint, modifier = Modifier.padding(start = 24.dp, top = 4.dp, end = 24.dp))
        }
        HorizontalDivider(Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 一行：标题（可选副标题）… 右侧值 / trailing / ›。[icon]、[tint] 为兼容旧调用保留，不再绘制。 */
@Composable
internal fun SettingsTile(
    title: String,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") icon: Any? = null,
    @Suppress("UNUSED_PARAMETER") tint: Int = 0,
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
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onTap != null) Modifier.clickable(enabled = enabled, onClick = onTap) else Modifier)
            .heightIn(min = 52.dp)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    danger -> scheme.error
                    !enabled -> scheme.onSurface.copy(alpha = 0.38f)
                    else -> scheme.onSurface
                },
            )
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SettingsColors.faint)
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
            Spacer(Modifier.width(8.dp))
            Text("›", style = MaterialTheme.typography.titleMedium, color = SettingsColors.faint)
        }
    }
}

@Composable
internal fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
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

@Composable
internal fun StepRow(index: Int, text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Text("$index.", style = MaterialTheme.typography.bodyLarge, color = SettingsColors.faint, modifier = Modifier.width(28.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun ExpandRow(title: String, steps: List<String>, initiallyOpen: Boolean = false, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.heightIn(min = 52.dp).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(if (open) "⌃" else "⌄", color = SettingsColors.faint)
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium, color = SettingsColors.faint) }
            }
        }
    }
}

/** 提示文字（Key 安全提示等）：一段次要色文字，不用彩色卡片。 */
@Composable
internal fun NoticeCard(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall, color = SettingsColors.faint)
    }
}

@Composable
internal fun PlainFilledButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, shape = FieldShape, modifier = modifier.heightIn(min = 44.dp)) { Text(text) }
}

@Composable
internal fun PlainOutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = FieldShape, modifier = modifier.heightIn(min = 44.dp)) { Text(text) }
}

@Composable
internal fun plainFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors()

@Composable
internal fun PlainOutlined(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = FieldShape, contentPadding = PaddingValues(horizontal = 20.dp), modifier = modifier.heightIn(min = 44.dp), content = content)
}

@Composable
internal fun PlainFilled(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(onClick = onClick, enabled = enabled, shape = FieldShape, contentPadding = PaddingValues(horizontal = 20.dp), modifier = modifier.heightIn(min = 44.dp), content = content)
}

/** 返回键（替代旧的玻璃圆钮）。 */
@Composable
internal fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 44.dp).clickable(onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text("‹ 返回", style = MaterialTheme.typography.bodyLarge)
    }
}
