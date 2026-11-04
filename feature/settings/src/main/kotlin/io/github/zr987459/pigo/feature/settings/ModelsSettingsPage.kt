package io.github.zr987459.pigo.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.models.ModelPanels
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.settings.PiSettings
import io.github.zr987459.pigo.feature.settings.ui.BackButton
import io.github.zr987459.pigo.feature.settings.ui.LargeTitle
import io.github.zr987459.pigo.feature.settings.ui.PlainBackdrop
import io.github.zr987459.pigo.feature.settings.ui.SettingsColors
import io.github.zr987459.pigo.feature.settings.ui.SettingsSection
import io.github.zr987459.pigo.feature.settings.ui.SettingsTile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** testTag（界面测试与覆盖测试用）。 */
object SettingsTags {
    const val PAGE = "page:settings"
    const val SEARCH = "settings.search"
    const val FOOTER = "settings.footer"
    const val PANEL = "settings.panel"
    fun section(key: String) = "settings.section:$key"
    fun row(key: String) = "settings.row:$key"
}

/**
 * 设置 › 模型（DESIGN-r3 §6.1）。逐行渲染 [SettingsModelBuilder.build] 的结果；点行得到的面板
 * （[OverlayModel]，与 /model、/login 同一份）在本页用一个极简对话框画，面板内操作回传 [ModelPanels.onPanelEvent]。
 * 只用 `MaterialTheme` 的颜色与字体（主题由 App 提供），不自带配色。
 */
@Composable
fun ModelsSettingsPage(
    panels: ModelPanels,
    piSettings: PiSettings?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var model by remember { mutableStateOf<SettingsModel?>(null) }
    var panel by remember { mutableStateOf<OverlayModel?>(null) }
    val piValues = piSettings?.values

    LaunchedEffect(panels, query, version, piValues?.value) {
        model = runCatching { SettingsModelBuilder.build(panels, piSettings, query) }.getOrNull()
    }

    fun handle(r: ActionResult) {
        when (r) {
            is ActionResult.Overlay -> panel = r.model
            is ActionResult.Toast -> { panel = null; scope.launch { snackbar.showSnackbar(r.text) } }
            is ActionResult.LocalBlock -> panel = OverlayModel.Text("local", r.title, r.text)
            ActionResult.Done -> panel = null
        }
        version++
    }

    fun open(row: SettingsModel.Row) = scope.launch {
        when (val o = row.open) {
            is SettingsModel.Open.Picker -> panel = panels.modelPicker(o.tab, "")
            is SettingsModel.Open.Event -> handle(panels.onPanelEvent(o.overlayId, o.key))
            SettingsModel.Open.None -> snackbar.showSnackbar("在 ⌘ › pi 设置 中修改")
        }
    }

    PlainBackdrop(modifier.testTag(SettingsTags.PAGE)) {
        Scaffold(containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            LazyColumn(
                Modifier.padding(padding).fillMaxSize().testTag("settings:list"),
                contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            ) {
                if (onBack != null) item(key = "back") { BackButton(onBack, Modifier.padding(start = 12.dp).testTag("settings:back")) }
                item(key = "title") { LargeTitle("模型") }
                val m = model
                if (m != null) {
                    m.sections.forEach { sec ->
                        item(key = "sec:${sec.key}") {
                            SettingsSection(sec.title, Modifier.testTag(SettingsTags.section(sec.key))) {
                                if (sec.searchable) {
                                    OutlinedTextField(
                                        value = query,
                                        onValueChange = { query = it },
                                        placeholder = { Text("搜索（支持拼音首字母）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).testTag(SettingsTags.SEARCH),
                                    )
                                }
                                if (sec.rows.isEmpty()) {
                                    Text(
                                        if (sec.searchable && query.isNotBlank()) "没有匹配的服务商" else "暂无",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SettingsColors.faint,
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                    )
                                }
                                sec.rows.forEach { row ->
                                    SettingsTile(
                                        title = (when (row.connected) { true -> "● "; false -> "○ "; null -> "" }) + row.label,
                                        value = row.detail,
                                        onTap = { open(row) },
                                        modifier = Modifier.testTag(SettingsTags.row("${sec.key}/${row.key}")),
                                    )
                                }
                            }
                        }
                    }
                    item(key = "footer") {
                        Text(
                            m.footer,
                            style = MaterialTheme.typography.bodySmall,
                            color = SettingsColors.faint,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag(SettingsTags.FOOTER),
                        )
                    }
                }
            }
        }
    }

    panel?.let { p ->
        PanelDialog(
            model = p,
            onDismiss = { panel = null; version++ },
            onEvent = { key, value -> scope.launch { handle(panels.onPanelEvent(p.id, key, value)) } },
        )
    }
}

/** 面板的极简渲染（SelectList / Form / Text / Confirm）；其他类型只显示标题。密文字段遮挡显示（契约 [io.github.zr987459.pigo.contract.session.FormField.secret]）。 */
@Composable
internal fun PanelDialog(model: OverlayModel, onDismiss: () -> Unit, onEvent: (key: String, value: String?) -> Unit) {
    val formValues = remember(model) { mutableStateMapOf<String, String>().apply { if (model is OverlayModel.Form) putAll(model.values) } }
    var listQuery by remember(model) { mutableStateOf((model as? OverlayModel.SelectList)?.query.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(SettingsTags.PANEL),
        title = { Text(model.title) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                when (model) {
                    is OverlayModel.SelectList -> {
                        if (model.tabs.isNotEmpty()) Row {
                            model.tabs.forEach { t -> TextButton(onClick = { onEvent("tab:$t", listQuery) }) { Text(if (t == model.tab) "[$t]" else t) } }
                        }
                        OutlinedTextField(listQuery, { listQuery = it; onEvent("query", it) }, singleLine = true, placeholder = { Text("搜索") }, modifier = Modifier.fillMaxWidth())
                        model.groups.forEach { g ->
                            g.title?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = SettingsColors.faint, modifier = Modifier.padding(top = 8.dp)) }
                            g.items.forEach { item ->
                                Row(Modifier.fillMaxWidth().clickable(enabled = item.enabled) { onEvent(item.key, null) }.padding(vertical = 8.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text((if (item.starred == true) "★ " else "") + item.label, style = MaterialTheme.typography.bodyLarge)
                                        item.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SettingsColors.faint) }
                                    }
                                }
                            }
                        }
                        if (model.keyCaps.isNotEmpty()) Row {
                            model.keyCaps.forEach { c -> TextButton(onClick = { onEvent(c.actionId, model.selectedKey) }) { Text(c.label) } }
                        }
                    }
                    is OverlayModel.Form -> model.fields.forEach { f ->
                        OutlinedTextField(
                            value = formValues[f.key].orEmpty(),
                            onValueChange = { formValues[f.key] = it },
                            label = { Text(f.label) },
                            supportingText = f.help?.let { h -> { Text(h) } },
                            singleLine = true,
                            enabled = f.enabled,
                            visualTransformation = if (f.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                    is OverlayModel.Text -> Text(model.text)
                    is OverlayModel.Confirm -> Text(model.message)
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (model) {
                is OverlayModel.Form -> Row {
                    formActions(model.id).forEach { (k, label) -> TextButton(onClick = { onEvent(k, formJson(formValues)) }) { Text(label) } }
                    TextButton(onClick = { onEvent("submit", formJson(formValues)) }) { Text(model.submitLabel ?: "保存") }
                }
                is OverlayModel.Confirm -> TextButton(onClick = { onEvent("confirm", null) }) { Text(model.confirmLabel) }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 表单上除「保存」外的操作（与 RealModelPanels 的事件 key 一致）。 */
internal fun formActions(overlayId: String): List<Pair<String, String>> = when {
    overlayId.startsWith("provider.form:") -> listOf("test" to "测试")
    overlayId.startsWith("provider.custom") -> listOf("discover" to "获取模型", "test" to "测试")
    else -> emptyList()
}

internal fun formJson(values: Map<String, String>): String = JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString()

