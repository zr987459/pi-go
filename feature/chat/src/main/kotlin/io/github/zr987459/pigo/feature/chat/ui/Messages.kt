package io.github.zr987459.pigo.feature.chat.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.core.ui.MonoStyle
import io.github.zr987459.pigo.feature.chat.logic.ChatRow
import io.github.zr987459.pigo.feature.chat.logic.ToolPhase
import io.github.zr987459.pigo.feature.chat.logic.ToolText
import io.github.zr987459.pigo.feature.chat.logic.formatSeconds
import kotlinx.coroutines.delay

/** 一行消息的回调集合。 */
class RowActions(
    val onRetryLocal: (String) -> Unit = {},
    val onEditLocal: (String) -> Unit = {},
    val onDeleteLocal: (String) -> Unit = {},
    val onResend: (String) -> Unit = {},
    val onToggleTurn: (String) -> Unit = {},
    val onOpenModelSettings: (() -> Unit)? = null,
)

@Composable
fun ChatRowView(row: ChatRow, isLatestUser: Boolean, actions: RowActions, modifier: Modifier = Modifier) {
    when (row) {
        is ChatRow.User -> UserBubble(row, isLatestUser, actions, modifier)
        is ChatRow.Assistant -> AssistantMessage(row, modifier)
        is ChatRow.Tool -> ToolCard(row.tool, row.phase, modifier)
        is ChatRow.Notice -> NoticeLine(row.notice, modifier)
        is ChatRow.Raw -> RawCard(row.raw, modifier)
        is ChatRow.Error -> ErrorCard(row, actions, modifier)
        is ChatRow.TurnSummary -> TurnSummaryRow(row, actions, modifier)
    }
}

// ---------------- 用户气泡 ----------------

@Composable
private fun UserBubble(row: ChatRow.User, isLatest: Boolean, actions: RowActions, modifier: Modifier) {
    val failed = row.delivery == Delivery.FAILED
    Column(modifier.fillMaxWidth().testTag("row:user"), horizontalAlignment = Alignment.End) {
        Surface(
            shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
            color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Text(
                row.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        val label = when (row.delivery) {
            Delivery.SENDING -> "发送中…"
            Delivery.QUEUED -> "已排队"
            Delivery.FAILED -> "发送失败" + (row.error?.let { "：$it" } ?: "")
            Delivery.SENT -> if (row.localId != null || isLatest) "已发送" else null
        }
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp).testTag("delivery:${row.delivery.name}"),
            )
        }
        if (failed && row.localId != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { actions.onDeleteLocal(row.localId) }) { Text("删除") }
                TextButton(onClick = { actions.onEditLocal(row.localId) }) { Text("改一改") }
                TextButton(onClick = { actions.onRetryLocal(row.localId) }, modifier = Modifier.testTag("failed:retry")) { Text("重试") }
            }
            val settings = actions.onOpenModelSettings
            if (row.needsSettings && settings != null) {
                TextButton(onClick = settings, modifier = Modifier.testTag("failed:settings")) { Text("去设置模型") }
            }
        }
    }
}

// ---------------- 助手消息 ----------------

@Composable
private fun AssistantMessage(row: ChatRow.Assistant, modifier: Modifier) {
    val item = row.item
    Column(modifier.fillMaxWidth().testTag("row:assistant"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var anyText = false
        for (b in item.blocks) {
            when (b) {
                is ContentBlock.Thinking -> if (row.showThinking) {
                    val ms = row.thinkingMs[b.index]
                    val active = item.streaming && ms == null && b.index == item.blocks.maxOf { it.index }
                    ThinkingBlock(b.text, active = active, durationMs = ms)
                }
                is ContentBlock.Text -> if (row.showText && b.text.isNotEmpty()) {
                    anyText = true
                    MarkdownText(b.text)
                }
                is ContentBlock.ToolCall -> Unit // 由工具卡显示
            }
        }
        if (row.showText && item.streaming && !anyText && item.blocks.none { it is ContentBlock.Thinking }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("正在回复…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (row.showText) {
            val tag = when (item.stopReason) {
                "aborted" -> "已停止"
                "length" -> "输出被截断（达到长度上限）"
                else -> null
            }
            if (tag != null) {
                Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

/** S3：可折叠的「思考中…/已思考 N 秒」。 */
@Composable
private fun ThinkingBlock(text: String, active: Boolean, durationMs: Long?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("thinking"),
    ) {
        Row(
            Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (active) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "收起思考" else "展开思考",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when {
                    active -> "思考中…"
                    durationMs != null && durationMs > 0 -> "已思考 ${formatSeconds(durationMs)}"
                    else -> "已思考"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded && text.isNotBlank()) {
            Text(
                ToolText.clip(text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp, bottom = 4.dp),
            )
        }
    }
}

// ---------------- 工具卡 ----------------

/**
 * T1/T2：运行中转圈+计时；结束显示耗时；失败红色、默认展开并显示错误文本。
 * 回合已结束但工具没走到终态（[ToolPhase.NOT_RUN] / [ToolPhase.INTERRUPTED]）时不再转圈计时，显示「未执行 / 已中断」。
 */
@Composable
fun ToolCard(tool: ChatItem.Tool, phase: ToolPhase = ToolPhase.of(tool.status, live = true), modifier: Modifier = Modifier) {
    val failed = phase == ToolPhase.ERROR
    val running = phase.spinning
    val halted = phase == ToolPhase.NOT_RUN || phase == ToolPhase.INTERRUPTED
    var userExpanded by rememberSaveable(tool.key) { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: failed
    val firstSeen = remember(tool.key) { System.currentTimeMillis() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val container = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val onContainer = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = container,
        border = if (failed) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
        modifier = modifier.fillMaxWidth().testTag("tool:${tool.toolCallId}"),
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().clickable { userExpanded = !expanded }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    running -> CircularProgressIndicator(Modifier.size(16.dp).testTag("tool:spinner"), strokeWidth = 2.dp)
                    failed -> Icon(Icons.Filled.Close, contentDescription = "失败", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    halted -> Icon(Icons.Filled.Warning, contentDescription = ToolText.phaseLabel(phase), tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp).testTag("tool:halted"))
                    else -> Icon(Icons.Filled.Check, contentDescription = "完成", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "${ToolText.label(tool.toolName)} · ${tool.toolName}",
                        style = MaterialTheme.typography.labelLarge,
                        color = onContainer,
                    )
                    ToolText.argSummary(tool)?.let {
                        Text(
                            it,
                            style = MonoStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = onContainer.copy(alpha = 0.8f),
                        )
                    }
                }
                val timeText = when {
                    running -> formatSeconds((now - (tool.startedAtMillis ?: firstSeen)).coerceAtLeast(0))
                    halted -> ToolText.phaseLabel(phase)
                    tool.durationMs != null -> "耗时 " + formatDuration(tool.durationMs!!)
                    else -> null
                }
                if (timeText != null) {
                    Text(timeText, style = MaterialTheme.typography.labelSmall, color = onContainer.copy(alpha = 0.8f), modifier = Modifier.testTag("tool:time"))
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.size(18.dp),
                    tint = onContainer,
                )
            }
            if (expanded) {
                HorizontalDivider()
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tool.args?.takeIf { it.isNotEmpty() }?.let { args ->
                        Text("参数", style = MaterialTheme.typography.labelSmall, color = onContainer)
                        Text(args.toString(), style = MonoStyle, color = onContainer)
                    }
                    val output = ToolText.of(tool.result) ?: ToolText.of(tool.partialResult)
                    Text(if (failed) "错误信息" else if (running) "输出（进行中）" else "输出", style = MaterialTheme.typography.labelSmall, color = onContainer)
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        Text(
                            output?.let { ToolText.clip(it) } ?: when {
                                running -> "等待输出…"
                                phase == ToolPhase.NOT_RUN -> "（这一轮已结束，工具没有执行）"
                                phase == ToolPhase.INTERRUPTED -> "（工具执行被中断，没有收到结果）"
                                else -> "（无输出）"
                            },
                            style = MonoStyle,
                            color = onContainer,
                            modifier = Modifier.testTag("tool:output"),
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String =
    if (ms < 1000) "$ms 毫秒" else if (ms < 60_000) String.format(java.util.Locale.ROOT, "%.1f 秒", ms / 1000.0) else formatSeconds(ms)

// ---------------- 折叠摘要 ----------------

/** T3：回合结束后，思考与工具折叠成一行摘要，可展开。 */
@Composable
private fun TurnSummaryRow(row: ChatRow.TurnSummary, actions: RowActions, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable { actions.onToggleTurn(row.key) }
            .padding(vertical = 6.dp)
            .testTag("turn:summary"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (row.expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (row.expanded) "收起过程" else "展开过程",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            row.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (row.failedTools > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (row.expanded) "收起" else "展开",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// ---------------- 错误卡 / 提示行 / 原始 JSON ----------------

/** S6 / §3.9：中文错误卡 + 原文可展开 +「重试」+「去设置模型」。 */
@Composable
private fun ErrorCard(row: ChatRow.Error, actions: RowActions, modifier: Modifier) {
    var showRaw by rememberSaveable(row.key) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth().testTag("row:error"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Text(
                    row.info.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.testTag("error:title"),
                )
            }
            Text(row.info.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            if (showRaw && row.info.raw != null) {
                Text(row.info.raw, style = MonoStyle, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (row.info.raw != null) {
                    TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "收起原文" else "查看原文") }
                }
                val settings = actions.onOpenModelSettings
                if (settings != null && row.info.suggestsSettings) {
                    TextButton(onClick = settings, modifier = Modifier.testTag("error:settings")) { Text("去设置模型") }
                }
                if (row.retryText != null) {
                    OutlinedButton(onClick = { actions.onResend(row.retryText) }, modifier = Modifier.testTag("error:retry")) { Text("重试") }
                }
            }
        }
    }
}

@Composable
private fun NoticeLine(notice: ChatItem.Notice, modifier: Modifier) {
    val color = when (notice.kind) {
        NoticeKind.ERROR -> MaterialTheme.colorScheme.error
        NoticeKind.STOPPED, NoticeKind.INTERRUPTED, NoticeKind.TRUNCATED -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("row:notice"), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            notice.text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

/** 未知 role：可展开的原始 JSON（§3.8）。 */
@Composable
private fun RawCard(raw: ChatItem.Raw, modifier: Modifier) {
    var expanded by rememberSaveable(raw.key) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.clickable { expanded = !expanded }.padding(10.dp)) {
            Text(
                "未识别的消息（${raw.role ?: "未知类型"}）· 点击${if (expanded) "收起" else "查看原始内容"}",
                style = MaterialTheme.typography.labelMedium,
            )
            if (expanded) {
                Text(ToolText.clip(raw.json.toString()), style = MonoStyle)
            }
        }
    }
}
