package io.github.zr987459.pigo.feature.chat.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.RetryBanner
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.feature.chat.logic.ComposerState
import io.github.zr987459.pigo.feature.chat.logic.PrimaryAction

/** 输入框上方的芯片区：待插话/待排队（以 `queue_update` 为准）、已撤回 n 条、扩展状态行。 */
@Composable
fun QueueAndStatus(
    queue: QueueSnapshot,
    withdrawn: List<String>,
    statusLines: List<String>,
    onWithdrawnClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queue.isEmpty && withdrawn.isEmpty() && statusLines.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        statusLines.forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("chat:status"),
            )
        }
        if (!queue.isEmpty || withdrawn.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                queue.steering.forEach { t -> QueueChip("待插话", t, "chip:steer") }
                queue.followUp.forEach { t -> QueueChip("待排队", t, "chip:followUp") }
                if (withdrawn.isNotEmpty()) {
                    AssistChip(
                        onClick = onWithdrawnClick,
                        label = { Text("已撤回 ${withdrawn.size} 条 · 点此处理") },
                        modifier = Modifier.testTag("chip:withdrawn"),
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueChip(kind: String, text: String, tag: String) {
    AssistChip(
        onClick = {},
        label = { Text("$kind：$text", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) },
        modifier = Modifier.testTag(tag),
    )
}

/** 自动重试黄条（§3.5 auto_retry_*）。 */
@Composable
fun RetryBar(retry: RetryBanner, onStop: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth().testTag("chat:retry")) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "模型请求失败，正在第 ${retry.attempt}/${retry.maxAttempts} 次重试…",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                retry.errorMessage?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // 契约暂无 abortRetry()（§3.5 abort_retry），这里走的是 stop()：clear_queue + abort，会一并撤回排队消息，文案写明。
            TextButton(onClick = onStop, modifier = Modifier.testTag("retry:stop")) { Text("停止并撤回排队") }
        }
    }
}

/**
 * 输入框 + 发送键（§3.7）：空闲=发送；生成中有字=排队（长按选插话）；生成中无字=停止。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposerBar(
    composer: ComposerState,
    primary: PrimaryAction,
    running: Boolean,
    onDraftChange: (String) -> Unit,
    onSubmit: (SendMode?) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 光标位置留在界面里；草稿文本同步到控制器（旋转、切页都不丢）。只有程序改写草稿（revision 变化）时才回写输入框。
    var field by remember { mutableStateOf(TextFieldValue(composer.draft, TextRange(composer.draft.length))) }
    LaunchedEffect(composer.revision) {
        if (composer.draft != field.text) field = TextFieldValue(composer.draft, TextRange(composer.draft.length))
    }
    var menu by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().padding(8.dp)) {
        if (composer.stopping) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Text(
                    if (composer.stopSlow) "仍在停止，正在强制结束…" else "正在停止…",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("chat:stopping"),
                )
            }
        } else if (running) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (composer.draft.isBlank()) "生成中 · 点「停止」可以中断" else "生成中 · 点「排队」本轮结束后再发；长按可「插话」",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                // 输入框有字时发送键是「排队」，这里保留一个停止入口；草稿不会被撤回的消息覆盖。
                if (composer.draft.isNotBlank()) {
                    TextButton(onClick = onStop, modifier = Modifier.testTag("chat:stopAlt")) {
                        Text("停止", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = field,
                onValueChange = {
                    val changed = it.text != field.text
                    field = it
                    if (changed) onDraftChange(it.text)
                },
                placeholder = { Text(if (running) "补充说明或下一步要求…" else "给 pi 发消息，例如：列出当前目录") },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp, max = 180.dp).testTag("chat:input"),
                shape = RoundedCornerShape(20.dp),
            )
            Box {
                val (label, enabled) = when (primary) {
                    PrimaryAction.SEND -> "发送" to true
                    PrimaryAction.QUEUE -> "排队" to true
                    PrimaryAction.STOP -> "停止" to true
                    PrimaryAction.DISABLED -> (if (composer.stopping) "停止中" else "发送") to false
                }
                val bg = when {
                    !enabled -> MaterialTheme.colorScheme.surfaceVariant
                    primary == PrimaryAction.STOP -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                }
                val fg = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    primary == PrimaryAction.STOP -> MaterialTheme.colorScheme.onError
                    else -> MaterialTheme.colorScheme.onPrimary
                }
                Surface(
                    color = bg,
                    contentColor = fg,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .heightIn(min = 52.dp)
                        .testTag("chat:send")
                        .combinedClickable(
                            enabled = enabled,
                            onClickLabel = label,
                            onLongClickLabel = if (primary == PrimaryAction.QUEUE) "选择插话或排队" else null,
                            onClick = {
                                when (primary) {
                                    PrimaryAction.SEND, PrimaryAction.QUEUE -> onSubmit(null)
                                    PrimaryAction.STOP -> onStop()
                                    PrimaryAction.DISABLED -> Unit
                                }
                            },
                            onLongClick = { if (primary == PrimaryAction.QUEUE) menu = true },
                        ),
                ) {
                    Box(Modifier.padding(horizontal = 18.dp, vertical = 15.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("插话")
                                Text("当前工具执行完就送达", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        onClick = {
                            menu = false
                            onSubmit(SendMode.STEER)
                        },
                        modifier = Modifier.testTag("menu:steer"),
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("排队")
                                Text("本轮彻底结束后再发送", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        onClick = {
                            menu = false
                            onSubmit(SendMode.FOLLOW_UP)
                        },
                        modifier = Modifier.testTag("menu:followUp"),
                    )
                }
            }
        }
    }
}

/** 「已撤回 n 条」芯片的处理对话框：插入输入框（接在草稿后面）或丢弃。 */
@Composable
fun WithdrawnDialog(texts: List<String>, onInsert: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("已撤回 ${texts.size} 条消息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("停止时这些排队/插话的消息没有发出。你正在写草稿，所以没有自动放回输入框。", style = MaterialTheme.typography.bodySmall)
                texts.forEach { Text("· $it", maxLines = 4, overflow = TextOverflow.Ellipsis) }
            }
        },
        confirmButton = { TextButton(onClick = onInsert, modifier = Modifier.testTag("withdrawn:insert")) { Text("插入输入框") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("丢弃") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
