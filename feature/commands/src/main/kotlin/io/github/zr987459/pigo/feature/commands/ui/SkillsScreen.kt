package io.github.zr987459.pigo.feature.commands.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.CommandSource
import io.github.zr987459.pigo.feature.commands.skills.ResourceItem
import io.github.zr987459.pigo.feature.commands.skills.SkillTemplateManager
import io.github.zr987459.pigo.feature.commands.skills.SkillsUiState
import kotlinx.coroutines.launch

/** 技能与提示模板管理（C4）。页面外壳由 W5 的导航提供。 */
@Composable
fun SkillsContent(
    state: SkillsUiState,
    onView: (ResourceItem) -> Unit,
    onCloseView: () -> Unit,
    onToggle: (ResourceItem, Boolean) -> Unit,
    onCreate: suspend (name: String, description: String, body: String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("技能与提示模板", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { creating = true }) { Text("新建模板") }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.needsReload) Text(SkillTemplateManager.RELOAD_HINT, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(horizontal = 16.dp))
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.items, key = { it.kind.name + ":" + (it.path ?: it.name) }) { item ->
                Row(Modifier.fillMaxWidth().clickable(enabled = item.path != null) { onView(item) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (item.kind == CommandSource.SKILL) "技能 · " else "模板 · ") + "/" + item.name, style = MaterialTheme.typography.bodyMedium)
                        item.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                        Text(scopeText(item), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = item.enabled, enabled = item.editable, onCheckedChange = { onToggle(item, it) })
                }
            }
        }
    }
    if (state.viewing != null) {
        AlertDialog(
            onDismissRequest = onCloseView,
            confirmButton = { TextButton(onClick = onCloseView) { Text("关闭") } },
            title = { Text("/" + state.viewing.name) },
            text = { Text(state.viewText.orEmpty(), fontFamily = FontFamily.Monospace, modifier = Modifier.verticalScroll(rememberScrollState())) },
        )
    }
    if (creating) CreateTemplateDialog(onDismiss = { creating = false }, onCreate = onCreate)
}

private fun scopeText(i: ResourceItem): String = when {
    !i.editable -> "只读（项目或包内）"
    !i.enabled -> "已停用"
    else -> "用户目录"
}

@Composable
private fun CreateTemplateDialog(onDismiss: () -> Unit, onCreate: suspend (String, String, String) -> Boolean) {
    var name by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建提示模板") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("命令名（例如 review）") }, singleLine = true)
                OutlinedTextField(desc, { desc = it }, label = { Text("描述（可选）") }, singleLine = true)
                OutlinedTextField(body, { body = it }, label = { Text("内容，可用 \$1、\$@ 引用参数") }, minLines = 4)
            }
        },
        confirmButton = {
            Button(onClick = { scope.launch { if (onCreate(name, desc, body)) onDismiss() } }, enabled = name.isNotBlank() && body.isNotBlank()) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
