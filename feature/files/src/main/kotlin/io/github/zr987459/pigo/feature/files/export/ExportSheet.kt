package io.github.zr987459.pigo.feature.files.export

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.ExportFormat
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SessionExporter
import io.github.zr987459.pigo.feature.files.platform.FileShare
import io.github.zr987459.pigo.feature.files.platform.Pickers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 会话导出（E1 宿主侧分享、E2）：HTML（引擎 `export_html`）、Markdown、JSONL 原文件；每种可「分享」或「保存到手机」。
 * W5 从设置页 / 会话菜单接到这里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(exporter: SessionExporter, sessionGuestPath: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<ExportFormat?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun run(fmt: ExportFormat, save: Boolean) {
        busy = fmt
        message = null
        scope.launch {
            when (val r = exporter.export(sessionGuestPath, fmt)) {
                is Outcome.Err -> message = r.message
                is Outcome.Ok -> {
                    val f: File = r.value
                    if (save) {
                        val target = Pickers.createDocument(ctx, f.name, fmt.mimeType)
                        if (target != null) {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { ctx.contentResolver.openOutputStream(target, "wt")!!.use { o -> f.inputStream().use { it.copyTo(o) } } }.isSuccess
                            }
                            message = if (ok) "已保存 ${f.name}" else "保存失败"
                        }
                    } else {
                        val staged = withContext(Dispatchers.IO) { FileShare.stage(ctx, f) }
                        if (!FileShare.share(ctx, staged, fmt.mimeType, "分享会话")) message = "没有可以分享的应用"
                    }
                }
            }
            busy = null
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("export.sheet")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("导出会话", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            listOf(
                ExportFormat.HTML to "网页（HTML），保留排版，适合发给别人看",
                ExportFormat.MARKDOWN to "Markdown，当前分支的对话、思考和工具调用",
                ExportFormat.JSONL to "会话原文件（JSONL），可导入其他 pi",
            ).forEach { (fmt, desc) ->
                ListItem(
                    headlineContent = { Text(fmt.name.let { if (it == "MARKDOWN") "Markdown" else it }) },
                    supportingContent = { Text(desc) },
                    trailingContent = {
                        if (busy == fmt) CircularProgressIndicator(Modifier.padding(8.dp))
                        else Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { run(fmt, save = false) }, enabled = busy == null) { Text("分享") }
                            TextButton(onClick = { run(fmt, save = true) }, enabled = busy == null) { Text("保存") }
                        }
                    },
                )
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp)) }
        }
    }
}
