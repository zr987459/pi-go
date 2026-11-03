package io.github.zr987459.pigo.feature.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.feature.chat.DialogEntry
import io.github.zr987459.pigo.feature.chat.logic.ExtUi
import io.github.zr987459.pigo.feature.chat.logic.ExtUiAnswer
import kotlinx.coroutines.delay

/**
 * 扩展对话框（§3.6）：select / confirm / input / editor。
 * 任何关闭方式（返回键、点外面、「取消」）都回 `cancelled`，保证 pi 不会一直等。
 * 带 timeout 的显示倒计时；到时只关掉对话框（pi 侧按默认值处理），不再应答。
 */
@Composable
fun ExtUiDialog(
    entry: DialogEntry,
    pending: Int,
    onAnswer: (String, ExtUiAnswer) -> Unit,
    onExpired: (String) -> Unit,
) {
    val r = entry.request
    val id = r.id
    val cancel = { onAnswer(id, ExtUiAnswer.CANCELLED) }

    var now by remember(id) { mutableLongStateOf(System.currentTimeMillis()) }
    val remaining = ExtUi.remainingSeconds(r, entry.shownAtMillis, now)
    if (r.timeoutMs != null) {
        LaunchedEffect(id) {
            while (true) {
                now = System.currentTimeMillis()
                if ((ExtUi.remainingSeconds(r, entry.shownAtMillis, now) ?: 1) <= 0) {
                    onExpired(id)
                    break
                }
                delay(250)
            }
        }
    }

    val title = ExtUi.titleOf(r) + if (pending > 0) "（还有 $pending 个请求）" else ""
    val countdown: @Composable () -> Unit = {
        if (remaining != null) {
            Text(
                "剩余 $remaining 秒，到时自动按默认处理",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.testTag("ext:countdown"),
            )
        }
    }
    val message: @Composable () -> Unit = {
        r.message?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }

    when (r.method) {
        "select" -> AlertDialog(
            containerColor = io.github.zr987459.pigo.core.ui.DshTheme.colors.sheet.fill,
            onDismissRequest = cancel,
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    message()
                    countdown()
                    if (r.options.isEmpty()) Text("（扩展没有提供选项）")
                    LazyColumn(Modifier.heightIn(max = 360.dp).testTag("ext:options")) {
                        itemsIndexed(r.options) { i, opt ->
                            if (i > 0) HorizontalDivider()
                            Text(
                                opt,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAnswer(id, ExtUiAnswer(value = opt)) }
                                    .padding(vertical = 12.dp, horizontal = 4.dp)
                                    .testTag("ext:option:$i"),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = cancel, modifier = Modifier.testTag("ext:cancel")) { Text("取消") } },
            modifier = Modifier.testTag("ext:dialog:select"),
        )

        "confirm" -> AlertDialog(
            containerColor = io.github.zr987459.pigo.core.ui.DshTheme.colors.sheet.fill,
            onDismissRequest = cancel,
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    message()
                    countdown()
                }
            },
            confirmButton = {
                TextButton(onClick = { onAnswer(id, ExtUiAnswer(confirmed = true)) }, modifier = Modifier.testTag("ext:yes")) { Text("同意") }
            },
            dismissButton = {
                TextButton(onClick = { onAnswer(id, ExtUiAnswer(confirmed = false)) }, modifier = Modifier.testTag("ext:no")) { Text("拒绝") }
            },
            modifier = Modifier.testTag("ext:dialog:confirm"),
        )

        "input", "editor" -> {
            val editor = r.method == "editor"
            var text by rememberSaveable(id) { mutableStateOf(r.prefill ?: "") }
            AlertDialog(
                containerColor = io.github.zr987459.pigo.core.ui.DshTheme.colors.sheet.fill,
                onDismissRequest = cancel,
                title = { Text(title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        message()
                        countdown()
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            placeholder = { r.placeholder?.let { Text(it) } },
                            singleLine = !editor,
                            minLines = if (editor) 6 else 1,
                            maxLines = if (editor) 16 else 1,
                            modifier = Modifier.fillMaxWidth().testTag("ext:text"),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onAnswer(id, ExtUiAnswer(value = text)) }, modifier = Modifier.testTag("ext:ok")) {
                        Text(if (editor) "保存" else "确定")
                    }
                },
                dismissButton = { TextButton(onClick = cancel, modifier = Modifier.testTag("ext:cancel")) { Text("取消") } },
                modifier = Modifier.testTag("ext:dialog:${r.method}"),
            )
        }

        // 理论上到不了这里：未知 method 在控制器里已立即回 cancelled。保底仍给一个可关闭的对话框。
        else -> AlertDialog(
            containerColor = io.github.zr987459.pigo.core.ui.DshTheme.colors.sheet.fill,
            onDismissRequest = cancel,
            title = { Text(title) },
            text = { Text("这个扩展请求暂不支持（${r.method}）。") },
            confirmButton = { TextButton(onClick = cancel) { Text("关闭") } },
        )
    }
}

/** 扩展 `set_editor_text` 想替换用户正在写的草稿时的确认框。 */
@Composable
fun ReplaceDraftDialog(text: String, onReplace: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        containerColor = io.github.zr987459.pigo.core.ui.DshTheme.colors.sheet.fill,
        onDismissRequest = onKeep,
        title = { Text("替换输入框内容？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("扩展想把输入框改成下面的内容，你正在写的草稿会被替换：", style = MaterialTheme.typography.bodySmall)
                Text(text, maxLines = 8)
            }
        },
        confirmButton = { TextButton(onClick = onReplace) { Text("替换") } },
        dismissButton = { TextButton(onClick = onKeep) { Text("保留我的草稿") } },
    )
}
