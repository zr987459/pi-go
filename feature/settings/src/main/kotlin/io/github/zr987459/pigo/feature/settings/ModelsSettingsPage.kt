package io.github.zr987459.pigo.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalUriHandler
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
import kotlinx.coroutines.delay
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
    const val PANEL_TEXT = "settings.panel.text"
    const val PANEL_MESSAGE = "settings.panel.message"
    const val OPEN_URL = "settings.panel.openUrl"
    fun panelItem(key: String) = "settings.panel.item:$key"
    fun cap(actionId: String) = "settings.panel.cap:$actionId"
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
    /** 打开 OAuth 授权链接（默认用系统浏览器 / Custom Tab）；测试可替换。 */
    openUrl: ((String) -> Unit)? = null,
    /** 页末由装配层追加的行（网络代理、运行环境修复、关于；PR #21 审查第 1 轮，r3/app 最小改动）。 */
    extraContent: (@Composable () -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val openLink: (String) -> Unit = openUrl ?: { url -> runCatching { uriHandler.openUri(url) } }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var model by remember { mutableStateOf<SettingsModel?>(null) }
    var panel by remember { mutableStateOf<OverlayModel?>(null) }
    var pickMode by remember { mutableStateOf<SettingsPanelLogic.PickMode?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var openedUrl by remember { mutableStateOf<String?>(null) }
    val piValues = piSettings?.values

    LaunchedEffect(panels, query, version, piValues?.value) {
        model = runCatching { SettingsModelBuilder.build(panels, piSettings, query) }.getOrNull()
    }

    fun closePanel() { panel = null; pickMode = null; message = null; openedUrl = null }

    fun handle(r: ActionResult) {
        when (r) {
            is ActionResult.Overlay -> { panel = r.model; message = null }
            // Toast 不关面板（审查第 2 轮 Nit 3）：消息显示在面板里；没有面板时用 snackbar。
            is ActionResult.Toast -> if (panel != null) message = r.text else scope.launch { snackbar.showSnackbar(r.text) }
            is ActionResult.LocalBlock -> { panel = OverlayModel.Text("local", r.title, r.text); message = null }
            ActionResult.Done -> closePanel()
        }
        version++
    }

    /** 面板事件的唯一出口（键帽协议见契约 KeyCap）：高级表单本页处理，其余交给 ModelPanels。 */
    fun send(p: OverlayModel, key: String, value: String?) = scope.launch {
        if (SettingsPanelLogic.isAdvanced(p.id)) {
            if (key == "submit" && piSettings != null) handle(SettingsPanelLogic.submitAdvanced(piSettings, p.id, RealFormJson.parse(value)))
            return@launch
        }
        handle(panels.onPanelEvent(p.id, key, value))
    }

    fun dismiss() {
        val p = panel
        closePanel()
        version++
        if (p != null) SettingsPanelLogic.dismissEvent(p)?.let { k -> scope.launch { panels.onPanelEvent(p.id, k, null) } }
    }

    fun open(row: SettingsModel.Row) = scope.launch {
        when (val o = row.open) {
            is SettingsModel.Open.Picker -> { pickMode = o.mode; message = null; panel = panels.modelPicker(o.tab, "") }
            is SettingsModel.Open.Event -> { pickMode = null; handle(panels.onPanelEvent(o.overlayId, o.key)) }
            is SettingsModel.Open.Advanced -> {
                pickMode = null; message = null
                panel = SettingsPanelLogic.advancedForm(o.settingKey, piSettings?.values?.value.orEmpty())
            }
            SettingsModel.Open.None -> snackbar.showSnackbar("需要 pi 设置可用后才能修改")
        }
    }

    // OAuth：每秒拉一次进度，直到成功或失败；出现授权链接时自动打开一次（审查第 2 轮 Important 1）。
    val current = panel
    LaunchedEffect(current) {
        val url = SettingsPanelLogic.oauthUrl(current)
        if (url != null && url != openedUrl) { openedUrl = url; openLink(url) }
        while (SettingsPanelLogic.needsOAuthPolling(panel) && panel == current) {
            delay(1_000)
            val p = panel ?: break
            if (p != current) break
            handle(panels.onPanelEvent(p.id, "refresh"))
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
                if (extraContent != null) item(key = "extra") { extraContent() }
            }
        }
    }

    panel?.let { p ->
        PanelDialog(
            model = p,
            mode = pickMode,
            message = message,
            onDismiss = { dismiss() },
            onEvent = { key, value -> send(p, key, value) },
            onOpenUrl = openLink,
        )
    }
}

/** 面板的极简渲染（SelectList / Form / Text / Confirm）；其他类型只显示标题。密文字段遮挡显示（契约 [io.github.zr987459.pigo.contract.session.FormField.secret]）。 */
@Composable
internal fun PanelDialog(
    model: OverlayModel,
    onDismiss: () -> Unit,
    onEvent: (key: String, value: String?) -> Unit,
    mode: SettingsPanelLogic.PickMode? = null,
    message: String? = null,
    onOpenUrl: (String) -> Unit = {},
) {
    // Toast 不替换面板，所以表单已填值保留；「获取模型」等返回新表单时按新值重建。
    val formValues = remember(model) { mutableStateMapOf<String, String>().apply { if (model is OverlayModel.Form) putAll(model.values) } }
    var highlighted by remember(model.id) { mutableStateOf<String?>(null) }
    var listQuery by remember(model) { mutableStateOf((model as? OverlayModel.SelectList)?.query.orEmpty()) }
    // OAuth 的提交在途：点过一次就禁用，面板换成下一步（新 model）时复位。
    var submitted by remember(model) { mutableStateOf(false) }
    val guard = SettingsPanelLogic.guardsSubmit(model)
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
                                val isHi = highlighted == item.key
                                Row(
                                    Modifier.fillMaxWidth()
                                        .testTag(SettingsTags.panelItem(item.key))
                                        .clickable(enabled = item.enabled && !(guard && submitted)) {
                                            when (val c = SettingsPanelLogic.rowClick(model, item.key, mode)) {
                                                is SettingsPanelLogic.Click.Event -> { if (guard) submitted = true; onEvent(c.key, c.value) }
                                                is SettingsPanelLogic.Click.Highlight -> highlighted = c.key
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            (if (isHi) "▸ " else "") + (if (item.starred == true) "★ " else "") + item.label + (item.keyHint?.let { "（$it）" } ?: ""),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (isHi) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                        )
                                        item.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SettingsColors.faint) }
                                    }
                                }
                            }
                        }
                        val caps = SettingsPanelLogic.visibleKeyCaps(model, mode)
                        if (caps.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
                            caps.forEach { c ->
                                TextButton(
                                    onClick = { onEvent(c.actionId, SettingsPanelLogic.keyCapValue(model, highlighted)) },
                                    modifier = Modifier.testTag(SettingsTags.cap(c.actionId)),
                                ) { Text(c.label) }
                            }
                        }
                    }
                    is OverlayModel.Form -> model.fields.forEach { f ->
                        OutlinedTextField(
                            value = formValues[f.key].orEmpty(),
                            onValueChange = { formValues[f.key] = it },
                            label = { Text(f.label) },
                            supportingText = f.help?.let { h -> { Text(h) } },
                            singleLine = !SettingsPanelLogic.multiline(f.type),
                            minLines = if (SettingsPanelLogic.multiline(f.type)) 2 else 1,
                            enabled = f.enabled,
                            visualTransformation = if (f.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                    is OverlayModel.Text -> {
                        Text(model.text, modifier = Modifier.testTag(SettingsTags.PANEL_TEXT))
                        SettingsPanelLogic.oauthUrl(model)?.let { url ->
                            TextButton(onClick = { onOpenUrl(url) }, modifier = Modifier.testTag(SettingsTags.OPEN_URL)) { Text("打开链接") }
                        }
                    }
                    is OverlayModel.Confirm -> Text(model.message)
                    else -> Unit
                }
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp).testTag(SettingsTags.PANEL_MESSAGE))
                }
            }
        },
        confirmButton = {
            when (model) {
                is OverlayModel.Form -> Row {
                    SettingsPanelLogic.formActions(model).forEach { (k, label) ->
                        TextButton(onClick = { onEvent(k, formJson(formValues)) }, modifier = Modifier.testTag(SettingsTags.cap(k))) { Text(label) }
                    }
                    TextButton(
                        onClick = { if (guard) submitted = true; onEvent("submit", formJson(formValues)) },
                        enabled = !(guard && submitted),
                        modifier = Modifier.testTag(SettingsTags.cap("submit")),
                    ) { Text(model.submitLabel ?: "保存") }
                }
                is OverlayModel.Text -> if (SettingsPanelLogic.oauthRetryable(model)) {
                    TextButton(onClick = { onEvent("retry", null) }, modifier = Modifier.testTag(SettingsTags.cap("retry"))) { Text("重试") }
                } else Unit
                is OverlayModel.Confirm -> TextButton(onClick = { onEvent("confirm", null) }, modifier = Modifier.testTag(SettingsTags.cap("confirm"))) {
                    Text(model.confirmLabel, color = if (model.dangerous) MaterialTheme.colorScheme.error else Color.Unspecified)
                }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 表单上除「保存」外的操作（兜底；面板自带 actions 时用面板的）。 */
internal fun formActions(overlayId: String): List<Pair<String, String>> = SettingsPanelLogic.formActions(overlayId)

internal fun formJson(values: Map<String, String>): String = JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString()


/** 表单值 JSON（与 [formJson] 相反）。 */
internal object RealFormJson {
    fun parse(value: String?): Map<String, String> = runCatching {
        (kotlinx.serialization.json.Json.parseToJsonElement(value.orEmpty()) as JsonObject).mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }
    }.getOrDefault(emptyMap())
}
