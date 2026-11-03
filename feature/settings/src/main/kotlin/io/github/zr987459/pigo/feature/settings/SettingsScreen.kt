package io.github.zr987459.pigo.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.PresetKind
import io.github.zr987459.pigo.contract.ProviderConfigStore
import io.github.zr987459.pigo.contract.ProviderPreset
import io.github.zr987459.pigo.contract.RuntimeStatus
import io.github.zr987459.pigo.contract.SecretStore
import kotlinx.coroutines.launch

/**
 * 导航层（`:app` 的 `PiGoNavHost`）当前调用的入口，只传了 [ProviderConfigStore]。
 * 显示 Key 末 4 位、拉取模型列表、重启引擎、运行环境信息需要的依赖，由 mvp/integration 改为调用 [SettingsScreen] 时传入。
 */
@Composable
fun SettingsPlaceholder(providers: ProviderConfigStore, modifier: Modifier = Modifier) {
    SettingsScreen(providers = providers, modifier = modifier)
}

/**
 * 设置页：模型与 Key（§3.10、§5.2 C1–C5）、后台运行（§5.7 N5）、运行环境与关于。
 *
 * @param secrets 用于显示 Key 末 4 位（别名约定 `key.<provider>` / `key.custom.<id>`）；为 null 时只显示「已配置」。
 * @param engine 有引擎时：保存 Key 后自动重启引擎；「默认模型」从 `get_available_models` 里选并 `set_model`。
 * @param runtimeStatus 运行环境版本信息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    providers: ProviderConfigStore,
    modifier: Modifier = Modifier,
    secrets: SecretStore? = null,
    engine: EngineClient? = null,
    runtimeStatus: RuntimeStatus? = null,
    onRepairRuntime: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var configured by remember { mutableStateOf<List<String>>(emptyList()) }
    var masks by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var version by remember { mutableIntStateOf(0) } // 保存后 +1，触发重新读取
    var editing by remember { mutableStateOf<ProviderPreset?>(null) }
    var pickingDefault by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    val presets = remember(providers) { providers.presets() }

    LaunchedEffect(providers, version) {
        configured = runCatching { providers.configured() }.getOrDefault(emptyList())
        masks = if (secrets == null) emptyMap() else configured.associateWith { id ->
            val builtIn = presets.any { it.provider == id && it.kind != PresetKind.CUSTOM_OPENAI }
            KeyText.mask(secrets.get(if (builtIn) KeyText.builtInAlias(id) else KeyText.customAlias(id)))
        }
    }

    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    /** 保存后：刷新状态；有引擎就重启，让 pi 读到新的环境变量 / models.json（C4）。 */
    suspend fun afterSave(what: String) {
        version++
        val e = engine
        if (e == null) {
            toast("已保存$what。重新启动 pi 后生效。")
            return
        }
        when (val r = e.restart("模型配置已更新")) {
            is Outcome.Ok -> toast("已保存$what，pi 已重新启动")
            is Outcome.Err -> toast("已保存$what，但重启 pi 失败：${r.message}")
        }
    }

    Scaffold(
        modifier = modifier.testTag("page:settings"),
        topBar = { TopAppBar(title = { Text("设置") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Section("模型与 Key") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(
                        "安全提示：pi 能读取容器内的文件和环境变量，请使用可随时作废的 Key，不要用主账号的 Key。" +
                            "Key 只加密保存在本机，不会写进容器里的文件。",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            PresetKind.entries.forEach { kind ->
                item(key = "h-$kind") { Group(kindLabel(kind), kindHint(kind)) }
                items(presets.filter { it.kind == kind }, key = { "p-${it.provider}" }) { p ->
                    val isOn = configured.contains(p.provider)
                    PresetRow(
                        preset = p,
                        status = when {
                            p.provider == CUSTOM_OTHER -> configured.filter { id -> presets.none { it.provider == id } }
                                .takeIf { it.isNotEmpty() }?.let { "已添加：" + it.joinToString("、") } ?: "可添加任意 OpenAI 兼容服务"
                            isOn -> "已配置 " + (masks[p.provider] ?: "")
                            else -> "未配置"
                        },
                        onClick = { editing = p },
                    )
                }
            }
            item { Section("默认模型") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (configured.isEmpty()) "先在上面填一个 Key，再选默认模型。" else "选一个模型作为新会话的默认模型。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = { pickingDefault = true }, enabled = configured.isNotEmpty()) {
                            Text("选择默认模型")
                        }
                    }
                }
            }
            item { Section("后台运行") }
            item {
                Card(Modifier.fillMaxWidth().clickable { showGuide = true }) {
                    Column(Modifier.padding(12.dp)) {
                        Text("让 pi 在后台也能继续运行", style = MaterialTheme.typography.titleSmall)
                        Text("电池优化、自启动设置，以及 Android 12+ 子进程限制说明", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Section("运行环境与关于") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val st = runtimeStatus
                        if (st == null) {
                            Text("运行环境信息暂不可用", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("状态：${phaseLabel(st.phase.name)}", style = MaterialTheme.typography.bodySmall)
                            Text("Ubuntu ${st.ubuntu ?: "-"} · Node ${st.node ?: "-"} · pi ${st.pi ?: "-"}", style = MaterialTheme.typography.bodySmall)
                            st.error?.let { Text("问题：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                        onRepairRuntime?.let { OutlinedButton(onClick = it) { Text("修复运行环境") } }
                        Text("pi-go 以 Apache-2.0 许可证开源。", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    editing?.let { p ->
        if (p.kind == PresetKind.CUSTOM_OPENAI) {
            CustomDialog(
                preset = p,
                onDismiss = { editing = null },
                onSave = { id, url, key, models ->
                    val err = runCatching { providers.upsertCustom(id, url, key, models) }.exceptionOrNull()
                    if (err == null) {
                        editing = null
                        afterSave("「$id」")
                    }
                    err?.message ?: err?.let { "保存失败" }
                },
            )
        } else {
            KeyDialog(
                preset = p,
                alreadySet = configured.contains(p.provider),
                onDismiss = { editing = null },
                onSave = { key ->
                    val err = runCatching { providers.setBuiltInKey(p.provider, key) }.exceptionOrNull()
                    if (err == null) {
                        editing = null
                        afterSave("${p.label} 的 Key")
                    }
                    err?.message ?: err?.let { "保存失败" }
                },
            )
        }
    }

    if (pickingDefault) {
        DefaultModelDialog(
            configured = configured,
            engine = engine,
            onDismiss = { pickingDefault = false },
            onSave = { provider, model, thinking ->
                val e = engine
                if (e != null) {
                    val r = e.setModel(provider, model)
                    if (r is Outcome.Err) return@DefaultModelDialog "切换模型失败：${r.message}"
                    if (thinking != null) e.setThinking(thinking)
                }
                val err = runCatching { providers.setDefault(provider, model, thinking) }.exceptionOrNull()
                if (err == null) {
                    pickingDefault = false
                    toast("默认模型已设为 $provider / $model")
                }
                err?.message ?: err?.let { "保存失败" }
            },
        )
    }

    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text("后台运行") },
            text = { BackgroundGuideContent(Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showGuide = false }) { Text("关闭") } },
        )
    }
}

internal const val CUSTOM_OTHER = "custom"

private fun kindLabel(k: PresetKind) = when (k) {
    PresetKind.PAY_AS_YOU_GO -> "按量 API"
    PresetKind.CODING_PLAN -> "编程套餐（需要套餐 Key）"
    PresetKind.CUSTOM_OPENAI -> "自定义 OpenAI 兼容"
}

private fun kindHint(k: PresetKind) = when (k) {
    PresetKind.PAY_AS_YOU_GO -> "在服务商开放平台充值后创建的 Key，按用量计费。"
    PresetKind.CODING_PLAN -> "购买了编程套餐才有的专用 Key，普通开放平台 Key 填这里会失败。"
    PresetKind.CUSTOM_OPENAI -> "其他兼容 OpenAI 接口的服务，需要自己填接口地址和模型 id。"
}

private fun phaseLabel(p: String) = when (p) {
    "NOT_INSTALLED" -> "未安装"
    "INSTALLING" -> "安装中"
    "READY" -> "正常"
    "BROKEN" -> "需要修复"
    else -> p
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Group(title: String, hint: String) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PresetRow(preset: ProviderPreset, status: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("preset:${preset.provider}")) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(preset.label, style = MaterialTheme.typography.bodyLarge)
                if (preset.note.isNotBlank()) Text(preset.note, style = MaterialTheme.typography.bodySmall)
                Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (status.startsWith("已")) "修改" else "填写", color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 内置 Provider 的 Key 输入框。[onSave] 返回错误文案，null 表示成功。 */
@Composable
private fun KeyDialog(
    preset: ProviderPreset,
    alreadySet: Boolean,
    onDismiss: () -> Unit,
    onSave: suspend (String) -> String?,
) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(preset.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (preset.note.isNotBlank()) Text(preset.note, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; error = null },
                    label = { Text(if (alreadySet) "新的 API Key（留空不修改）" else "API Key") },
                    placeholder = { Text("粘贴 Key，例如 sk-…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag("settings:keyField"),
                )
                KeyText.preview(key)?.let { Text("将保存为 $it", style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && KeyText.clean(key).isNotEmpty(),
                onClick = {
                    saving = true
                    scope.launch {
                        error = onSave(key)
                        saving = false
                    }
                },
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}

/** 自定义 OpenAI 兼容端点。 */
@Composable
private fun CustomDialog(
    preset: ProviderPreset,
    onDismiss: () -> Unit,
    onSave: suspend (id: String, baseUrl: String, key: String, models: List<String>) -> String?,
) {
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf(if (preset.provider == CUSTOM_OTHER) "" else preset.provider) }
    var url by remember { mutableStateOf(preset.defaultBaseUrl.orEmpty()) }
    var key by remember { mutableStateOf("") }
    var models by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(preset.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = id, onValueChange = { id = it; error = null },
                    label = { Text("名称（英文 id）") }, placeholder = { Text("例如 bailian") }, singleLine = true,
                    enabled = preset.provider == CUSTOM_OTHER,
                    supportingText = { Text("Key 会以环境变量 ${KeyText.envVarFor(id)} 交给 pi") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it; error = null },
                    label = { Text("接口地址（baseUrl）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it; error = null },
                    label = { Text("API Key（修改已有配置时可留空）") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                KeyText.preview(key)?.let { Text("将保存为 $it", style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    value = models, onValueChange = { models = it; error = null },
                    label = { Text("模型 id（多个用逗号或换行分开）") },
                    placeholder = { Text(preset.note) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    error = onSave(id, url, key, KeyText.splitModels(models))
                    saving = false
                }
            }) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}

/** 选择默认模型：有引擎时从 `get_available_models` 里选，否则手动填写。 */
@Composable
private fun DefaultModelDialog(
    configured: List<String>,
    engine: EngineClient?,
    onDismiss: () -> Unit,
    onSave: suspend (provider: String, model: String, thinking: String?) -> String?,
) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var provider by remember { mutableStateOf(configured.firstOrNull().orEmpty()) }
    var model by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(engine) {
        val e = engine ?: return@LaunchedEffect
        when (val r = e.models()) {
            is Outcome.Ok -> models = r.value.filter { configured.isEmpty() || it.provider in configured }
            is Outcome.Err -> loadError = "读取模型列表失败：${r.message}，可以手动填写。"
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("选择默认模型") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val list = models
                if (engine != null && list == null && loadError == null) Text("正在读取模型列表…")
                loadError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (!list.isNullOrEmpty()) {
                    list.forEach { m ->
                        FilterChip(
                            selected = provider == m.provider && model == m.id,
                            onClick = { provider = m.provider; model = m.id },
                            label = { Text("${m.name ?: m.id}（${m.provider}）") },
                        )
                    }
                } else {
                    Text("服务商", style = MaterialTheme.typography.labelMedium)
                    configured.forEach { id ->
                        FilterChip(selected = provider == id, onClick = { provider = id }, label = { Text(id) })
                    }
                    OutlinedTextField(
                        value = model, onValueChange = { model = it; error = null },
                        label = { Text("模型 id") }, placeholder = { Text("例如 deepseek-chat") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("思考强度（可不选）", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    KeyText.THINKING_LEVELS.forEach { (lv, label) ->
                        FilterChip(selected = thinking == lv, onClick = { thinking = if (thinking == lv) null else lv }, label = { Text(label) })
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && provider.isNotBlank() && model.isNotBlank(), onClick = {
                saving = true
                scope.launch {
                    error = onSave(provider, model.trim(), thinking)
                    saving = false
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}
