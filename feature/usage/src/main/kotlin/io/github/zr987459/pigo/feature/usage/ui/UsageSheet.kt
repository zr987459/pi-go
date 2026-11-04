package io.github.zr987459.pigo.feature.usage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.feature.usage.logic.ContextBar
import io.github.zr987459.pigo.feature.usage.logic.UsageFormat
import io.github.zr987459.pigo.feature.usage.logic.UsageUiState

/**
 * 用量弹层的内容（U1–U3）。放在 ModalBottomSheet 里由 W5 接到顶栏的上下文百分比。
 * 无状态：数据来自 [UsageUiState]，操作回调给 UsageController。
 */
@Composable
@Deprecated("r3: r3/app 删除（DESIGN-r3 X3），新代码不得引用")
fun UsageSheetContent(
    state: UsageUiState,
    onCompact: (String?) -> Unit,
    onRefresh: () -> Unit,
    onCnyRate: (Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var instructions by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("用量", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (state.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            TextButton(onClick = onRefresh) { Text("刷新") }
        }
        ContextBarView(state.bar)
        val s = state.stats
        val t = s?.tokens
        StatRow("输入", UsageFormat.tokens(t?.input), "输出", UsageFormat.tokens(t?.output))
        StatRow("缓存读", UsageFormat.tokens(t?.cacheRead), "缓存写", UsageFormat.tokens(t?.cacheWrite))
        StatRow("合计", UsageFormat.tokens(t?.total), "费用", state.costUsd + (state.costCny?.let { "（$it）" } ?: ""), Modifier.testTag("usage-cost"))
        StatRow("消息", s?.totalMessages?.toString() ?: "—", "工具调用", s?.toolCalls?.toString() ?: "—")
        CnyRow(state.cnyRate, onCnyRate)
        HorizontalDivider()
        Text("压缩上下文", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = instructions,
            onValueChange = { instructions = it },
            modifier = Modifier.fillMaxWidth().testTag("compact-instructions"),
            label = { Text("压缩指示（可选）") },
            placeholder = { Text("例如：保留接口设计的讨论") },
            maxLines = 4,
        )
        Button(
            onClick = { onCompact(instructions.ifBlank { null }) },
            enabled = !state.compacting,
            modifier = Modifier.fillMaxWidth().testTag("compact-button"),
        ) {
            if (state.compacting) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在压缩…")
            } else Text("压缩")
        }
        state.lastCompaction?.let { c ->
            Text(
                "已压缩：" + UsageFormat.tokens(c.tokensBefore) + " → " + UsageFormat.tokens(c.estimatedTokensAfter),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
@Deprecated("r3: r3/app 删除（DESIGN-r3 X3），新代码不得引用")
fun ContextBarView(bar: ContextBar, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("context-bar")) {
        when (bar) {
            is ContextBar.Known -> {
                Text("上下文 " + bar.label, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { bar.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            is ContextBar.AfterCompaction -> Text("上下文 " + ContextBar.AFTER_COMPACTION_TEXT, style = MaterialTheme.typography.bodyMedium)
            ContextBar.Unknown -> Text("上下文 —", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StatRow(k1: String, v1: String, k2: String, v2: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        Stat(k1, v1, Modifier.weight(1f))
        Stat(k2, v2, Modifier.weight(1f))
    }
}

@Composable
private fun Stat(k: String, v: String, modifier: Modifier) {
    Column(modifier) {
        Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CnyRow(rate: Double?, onCnyRate: (Double?) -> Unit) {
    var text by remember(rate) { mutableStateOf((rate ?: UsageFormat.DEFAULT_CNY_PER_USD).toString()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("显示人民币", modifier = Modifier.weight(1f))
        if (rate != null) {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v; UsageFormat.parseRate(v)?.let(onCnyRate) },
                modifier = Modifier.width(96.dp),
                singleLine = true,
                label = { Text("汇率") },
            )
            Spacer(Modifier.width(8.dp))
        }
        Switch(checked = rate != null, onCheckedChange = { on -> onCnyRate(if (on) UsageFormat.parseRate(text) ?: UsageFormat.DEFAULT_CNY_PER_USD else null) })
    }
}
