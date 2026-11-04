package io.github.zr987459.pigo.feature.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.PresetKind
import io.github.zr987459.pigo.contract.ProviderConfigStore
import io.github.zr987459.pigo.contract.ProviderPreset
import io.github.zr987459.pigo.contract.RuntimeStatus
import io.github.zr987459.pigo.contract.SecretStore
import io.github.zr987459.pigo.feature.settings.ui.BackButton
import io.github.zr987459.pigo.feature.settings.ui.PlainBackdrop
import io.github.zr987459.pigo.feature.settings.ui.FieldShape
import io.github.zr987459.pigo.feature.settings.ui.SettingsColors
import io.github.zr987459.pigo.feature.settings.ui.PlainFilled
import io.github.zr987459.pigo.feature.settings.ui.PlainOutlined
import io.github.zr987459.pigo.feature.settings.ui.InfoRow
import io.github.zr987459.pigo.feature.settings.ui.LargeTitle
import io.github.zr987459.pigo.feature.settings.ui.NoticeCard
import io.github.zr987459.pigo.feature.settings.ui.SettingsHues
import io.github.zr987459.pigo.feature.settings.ui.SettingsSection
import io.github.zr987459.pigo.feature.settings.ui.SettingsTile
import io.github.zr987459.pigo.feature.settings.ui.plainFieldColors
import io.github.zr987459.pigo.feature.settings.ui.groupTitleStyle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导航层（`:app` 的 `PiGoNavHost`）当前调用的入口，只传了 [ProviderConfigStore]。
 * 显示 Key 末 4 位、拉取模型列表、重启引擎、运行环境信息需要的依赖，由 mvp/integration 改为调用 [SettingsScreen] 时传入。
 */
@Composable
fun SettingsPlaceholder(providers: ProviderConfigStore, modifier: Modifier = Modifier) {
    SettingsScreen(providers = providers, modifier = modifier)
}

/** 从其他页面跳到设置页时要定位的区块（导航参数 `settings?focus=…`）。 */
object SettingsFocus {
    /** 「模型与 Key」：聊天出错（没配模型、Key 错误、连不上模型服务）时的「去设置模型」。 */
    const val MODELS = "models"
    /** 「默认模型」。 */
    const val DEFAULT_MODEL = "default-model"
}

/**
 * 「测试连接」的对象。[rawKey] 为空表示用已保存的 Key；[custom] 为 true 时 [baseUrl] 是 OpenAI 兼容端点地址。
 * [model] 为空时内置 Provider 用 pi 的默认模型。
 */
data class ConnectionTarget(
    val provider: String,
    val custom: Boolean,
    val baseUrl: String?,
    val rawKey: String,
    val model: String?,
)

/** 「测试连接」的结果：成功或失败原因（中文、已脱敏）。 */
data class ConnectionCheck(val ok: Boolean, val message: String)

/** 编辑已有自定义端点时预填的内容（不含 Key；Key 留空表示保持原 Key）。 */
data class CustomEndpointPrefill(val baseUrl: String, val modelIds: List<String>)

/**
 * 设置页：模型与 Key（§3.10、§5.2 C1–C5）、后台运行（§5.7 N5）、运行环境与关于。
 *
 * 线程：Key 的读取（只取末 4 位）与保存都走挂起的 `SecretStore` / `ProviderConfigStore`，其实现内部切到 IO；
 * 保存后重启引擎在 [NonCancellable] 里完成，对话框在重启结束后才关闭，离开页面也不会把 pi 停在半路。
 *
 * @param secrets 用于显示 Key 末 4 位（别名约定 `key.<provider>` / `key.custom.<id>`）；为 null 时只显示「已配置」。
 * @param engine 有引擎时：保存 Key 后自动重启引擎；「默认模型」从 `get_available_models` 里选并 `set_model`。
 * @param runtimeStatus 已不在设置页显示（设置只剩模型，DESIGN-r3 §6.1）；参数保留以兼容调用方，r3/app 接线时去掉。
 * @param loadCustom 读取已有自定义端点（id → 接口地址与模型），用于编辑时预填；为 null 时编辑需要重新填写。
 * @param focus 打开时滚动到的区块（[SettingsFocus]）。
 * @param discoverModels 自定义端点「获取模型列表」：(id, baseUrl, rawKey) → 模型 id 列表；rawKey 为空表示用已保存的 Key。
 * @param testConnection 「测试连接」；为 null 时不显示按钮。
 * @param onBack 左上角返回圆钮；为 null 时不显示。
 * @param panels 模型配置面板（`feature/models` 的 RealModelPanels，r3/app 装配时传入）。**有它时整页按 DESIGN-r3 §6.1
 *   渲染**（[ModelsSettingsPage]：默认模型、常用模型、已连接、全部服务商（拼音搜索）、自定义、高级），其余参数不再使用；
 *   为 null 时保留旧版列表，只为 r3/app 装配前不坏。
 * @param piSettings pi 的 `settings.json`（默认模型、高级项的当前值）；可为 null。
 * @param onModelClick 「选择默认模型」入口；为 null 时用本页的默认模型对话框（现有行为）。
 *   留给 features 工作流接完整的模型选择器。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    providers: ProviderConfigStore,
    modifier: Modifier = Modifier,
    secrets: SecretStore? = null,
    engine: EngineClient? = null,
    runtimeStatus: RuntimeStatus? = null,
    onRepairRuntime: (() -> Unit)? = null,
    loadCustom: (suspend (id: String) -> CustomEndpointPrefill?)? = null,
    focus: String? = null,
    discoverModels: (suspend (id: String, baseUrl: String, rawKey: String) -> Outcome<List<String>>)? = null,
    testConnection: (suspend (ConnectionTarget) -> ConnectionCheck)? = null,
    onModelClick: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    panels: io.github.zr987459.pigo.contract.models.ModelPanels? = null,
    piSettings: io.github.zr987459.pigo.contract.settings.PiSettings? = null,
    /** 设置 › 模型页末尾由装配层追加的行（网络代理、运行环境修复、关于；PR #21 审查第 1 轮，r3/app 最小改动）。 */
    extraContent: (@Composable () -> Unit)? = null,
) {
    if (panels != null) {
        ModelsSettingsPage(panels, piSettings, modifier, onBack, extraContent = extraContent)
        return
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var configured by remember { mutableStateOf<List<String>>(emptyList()) }
    var masks by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var version by remember { mutableIntStateOf(0) } // 保存后 +1，触发重新读取
    var editing by remember { mutableStateOf<ProviderPreset?>(null) }
    var pickingDefault by remember { mutableStateOf(false) }
    val presets = remember(providers) { providers.presets() }
    val listState = rememberLazyListState()
    var testingCurrent by remember { mutableStateOf(false) }
    // 「当前模型」跟随引擎实时更新（换默认模型、引擎重启后不显示旧模型，测试连接也测新模型）。
    val currentModel by remember(engine) {
        engine?.chat?.map { it.model }?.distinctUntilChanged() ?: flowOf(null)
    }.collectAsState(initial = engine?.chat?.value?.model)
    var currentCheck by remember { mutableStateOf<ConnectionCheck?>(null) }

    var configuredLoaded by remember { mutableStateOf(false) }
    var focusDone by rememberSaveable(focus) { mutableStateOf(false) }

    // 从聊天页「去设置模型」等入口进来：等配置读出来（行数确定）后滚到对应区块，只定位一次；
    // 之后保存 Key 导致 configured 变化不再把列表拉回去。
    LaunchedEffect(focus, configuredLoaded) {
        if (!configuredLoaded || focusDone) return@LaunchedEffect
        val key = when (focus) {
            SettingsFocus.MODELS -> "sec-models"
            SettingsFocus.DEFAULT_MODEL -> "sec-default"
            else -> null
        } ?: return@LaunchedEffect
        val index = settingsItemIndex(key, presets, configured)
        if (index >= 0) listState.scrollToItem(index)
        focusDone = true
    }

    LaunchedEffect(providers, version) {
        configured = runCatching { providers.configured() }.getOrDefault(emptyList())
        configuredLoaded = true
        // 只取末 4 位（SecretStore 实现内部在 IO 线程解密），明文不进界面层。
        masks = if (secrets == null) emptyMap() else configured.associateWith { id ->
            val builtIn = presets.any { it.provider == id && it.kind != PresetKind.CUSTOM_OPENAI }
            KeyText.maskLast4(runCatching { secrets.last4(if (builtIn) KeyText.builtInAlias(id) else KeyText.customAlias(id)) }.getOrNull())
        }
    }

    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    /**
     * 保存后：刷新状态；有引擎就重启，让 pi 读到新的环境变量 / models.json（C4）。
     * 由对话框在关闭**之前**等待它完成；重启放在 [NonCancellable] 里，调用方的协程被取消（例如离开页面）
     * 也会把重启做完，不会出现 pi 停了没拉起。提示用页面级 [scope] 发出，不依赖对话框。
     */
    suspend fun afterSave(what: String) {
        version++
        val e = engine
        if (e == null) {
            toast("已保存$what。重新启动 pi 后生效。")
            return
        }
        val r = withContext(NonCancellable) { e.restart("模型配置已更新") }
        when (r) {
            is Outcome.Ok -> toast("已保存$what，pi 已重新启动")
            is Outcome.Err -> toast("已保存$what，但重启 pi 失败：${r.message}")
        }
    }

    PlainBackdrop(modifier.testTag("page:settings")) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (onBack != null) {
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BackButton(onBack, Modifier.testTag("settings:back"))
                    }
                }
            },
        ) { padding ->
            LazyColumn(
                Modifier.padding(padding).fillMaxSize().testTag("settings:list"),
                state = listState,
                contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            ) {
                // 下标与 settingsItemIndex 一致：大标题(0)「模型」(1) 默认模型(2) 各服务商分组(3..) 页底指引；设置只剩模型（DESIGN-r3 §6.1）。
                item(key = "title") { LargeTitle("设置") }
                item(key = "sec-models") {
                    Column(Modifier.padding(bottom = 24.dp)) {
                        Text(
                            ModelsSettings.root().single().title,
                            style = groupTitleStyle(),
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp).testTag("settings:sec-models"),
                        )
                        NoticeCard(
                            "使用可随时作废的 Key",
                            "pi 能读取容器内的文件和环境变量，请使用可随时作废的 Key，不要用主账号的 Key。" +
                                "Key 只加密保存在本机，不会写进容器里的文件。",
                        )
                    }
                }
                item(key = "sec-default") {
                    SettingsSection("默认模型", titleModifier = Modifier.testTag("settings:sec-default")) {
                        val current = currentModel
                        SettingsTile(
                            title = "选择默认模型",
                            tint = SettingsHues.AI,
                            subtitle = if (configured.isEmpty()) "先在下面填一个 Key，再选默认模型" else "新会话默认使用的模型",
                            value = current?.let { it.name ?: it.id },
                            enabled = configured.isNotEmpty(),
                            onTap = { (onModelClick ?: { pickingDefault = true })() },
                            modifier = Modifier.testTag("settings:pickDefault"),
                        )
                        val tester = testConnection
                        if (tester != null && current != null) {
                            SettingsTile(
                                title = if (testingCurrent) "正在测试…" else "测试连接",
                                tint = SettingsHues.AI,
                                subtitle = "${current.name ?: current.id}（${current.provider}）",
                                enabled = !testingCurrent,
                                chevron = false,
                                onTap = {
                                    testingCurrent = true
                                    currentCheck = null
                                    scope.launch {
                                        val isCustom = presets.none { it.provider == current.provider && it.kind != PresetKind.CUSTOM_OPENAI }
                                        currentCheck = runCatching {
                                            tester(ConnectionTarget(current.provider, isCustom, null, "", current.id))
                                        }.getOrElse { ConnectionCheck(false, "测试失败：${it.message}") }
                                        testingCurrent = false
                                    }
                                },
                                modifier = Modifier.testTag("settings:testCurrent"),
                            )
                            currentCheck?.let { CheckText(it, Modifier.padding(start = 60.dp, end = 16.dp, bottom = 10.dp)) }
                        }
                    }
                }
                PresetKind.entries.forEach { kind ->
                    val rows = presets.filter { it.kind == kind } + if (kind == PresetKind.CUSTOM_OPENAI) {
                        // 用户自己添加的端点各占一行，点开可修改（id 固定，地址与模型预填）。
                        configured.filter { id -> presets.none { it.provider == id } }.map { id ->
                            ProviderPreset(id, "自定义：$id", PresetKind.CUSTOM_OPENAI, KeyText.envVarFor(id), null, "")
                        }
                    } else {
                        emptyList()
                    }
                    item(key = "g-$kind") {
                        SettingsSection(kindLabel(kind), footer = kindHint(kind)) {
                            rows.forEach { p ->
                                val isOn = configured.contains(p.provider)
                                PresetRow(
                                    preset = p,
                                    isOn = isOn,
                                    status = when {
                                        p.provider == CUSTOM_OTHER -> null
                                        isOn -> masks[p.provider]?.takeIf { it.isNotBlank() } ?: "已配置"
                                        else -> "未配置"
                                    },
                                    onClick = { editing = p },
                                )
                            }
                        }
                    }
                }
                item(key = "footer") {
                    Text(
                        ModelsSettings.FOOTER_HINT,
                        style = MaterialTheme.typography.bodySmall,
                        color = SettingsColors.faint,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp).testTag("settings:footerHint"),
                    )
                }
            }
        }
    }

    editing?.let { p ->
        if (p.kind == PresetKind.CUSTOM_OPENAI) {
            CustomDialog(
                preset = p,
                existing = configured.contains(p.provider),
                loadCustom = loadCustom,
                discoverModels = discoverModels,
                testConnection = testConnection,
                onDismiss = { editing = null },
                onSave = { id, url, key, models ->
                    val err = runCatching { providers.upsertCustom(id, url, key, models) }.exceptionOrNull()
                    if (err == null) {
                        afterSave("「$id」") // 先等重启完成，再关闭对话框
                        editing = null
                    }
                    err?.message ?: err?.let { "保存失败" }
                },
            )
        } else {
            KeyDialog(
                preset = p,
                alreadySet = configured.contains(p.provider),
                testConnection = testConnection,
                onDismiss = { editing = null },
                onSave = { key ->
                    val err = runCatching { providers.setBuiltInKey(p.provider, key) }.exceptionOrNull()
                    if (err == null) {
                        afterSave("${p.label} 的 Key") // 先等重启完成，再关闭对话框
                        editing = null
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


@Composable
private fun CheckText(c: ConnectionCheck, modifier: Modifier = Modifier) {
    Row(modifier.testTag(if (c.ok) "settings:checkOk" else "settings:checkFail"), verticalAlignment = Alignment.CenterVertically) {
        Text(if (c.ok) "✓ " else "✗ ", color = if (c.ok) SettingsColors.success else MaterialTheme.colorScheme.error)
        Text(
            c.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (c.ok) SettingsColors.success else MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * [SettingsScreen] 里 LazyColumn 各区块的下标，与上面 item 的顺序一致：
 * 大标题(0)、「模型与 Key」(1)、每种服务商一个分组卡、「默认模型」。
 * 分组卡把一组的服务商放在同一个 item 里，所以下标不再随服务商数量变化。
 */
@Suppress("UNUSED_PARAMETER")
internal fun settingsItemIndex(key: String, presets: List<ProviderPreset>, configured: List<String>): Int = when (key) {
    "sec-models" -> 1
    "sec-default" -> 2
    else -> -1
}

/** 服务商行：apiKey 图标（已配置时用主色调）+ 名称 + 说明，右侧 Key 末 4 位 /「未配置」+ chevron。 */
@Composable
private fun PresetRow(preset: ProviderPreset, isOn: Boolean, status: String?, onClick: () -> Unit) {
    val add = preset.provider == CUSTOM_OTHER
    SettingsTile(
        title = preset.label,
        tint = if (add) SettingsHues.GENERAL else if (isOn) SettingsHues.AI else SettingsHues.HOSTS,
        subtitle = if (add) "添加新的 OpenAI 兼容服务" else preset.note.takeIf { it.isNotBlank() },
        value = status,
        valueColor = if (!isOn && !add) SettingsColors.warning else null,
        onTap = onClick,
        modifier = Modifier.testTag("preset:${preset.provider}"),
    )
}

/** 内置 Provider 的 Key 输入框。[onSave] 返回错误文案，null 表示成功。 */
@Composable
private fun KeyDialog(
    preset: ProviderPreset,
    alreadySet: Boolean,
    testConnection: (suspend (ConnectionTarget) -> ConnectionCheck)?,
    onDismiss: () -> Unit,
    onSave: suspend (String) -> String?,
) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<ConnectionCheck?>(null) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(preset.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (preset.note.isNotBlank()) Text(preset.note, style = MaterialTheme.typography.bodySmall)
                if (alreadySet) Text("已配置过 Key。不想修改就点「取消」。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; error = null },
                    label = { Text(if (alreadySet) "新的 API Key（会替换原来的 Key）" else "API Key") },
                    placeholder = { Text("粘贴 Key，例如 sk-…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    shape = FieldShape,
                    colors = plainFieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("settings:keyField"),
                )
                KeyText.preview(key)?.let { Text("将保存为 $it", style = MaterialTheme.typography.bodySmall) }
                if (testConnection != null) {
                    PlainOutlined(
                        enabled = !testing && !saving && (KeyText.clean(key).isNotEmpty() || alreadySet),
                        onClick = {
                            testing = true
                            check = null
                            scope.launch {
                                check = runCatching { testConnection(ConnectionTarget(preset.provider, false, null, key, null)) }
                                    .getOrElse { ConnectionCheck(false, "测试失败：${it.message}") }
                                testing = false
                            }
                        },
                        modifier = Modifier.testTag("settings:testKey"),
                    ) { Text(if (testing) "正在测试（最多 45 秒）…" else "测试连接") }
                    check?.let { CheckText(it) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            PlainFilled(
                enabled = !saving && KeyText.clean(key).isNotEmpty(),
                onClick = {
                    saving = true
                    scope.launch {
                        error = onSave(key)
                        saving = false
                    }
                },
            ) { Text(if (saving) "保存并重启 pi…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}

/**
 * 自定义 OpenAI 兼容端点。
 * [existing] 为 true 表示修改已有配置：id 固定，Key 可留空（保持原 Key），接口地址与模型由 [loadCustom] 预填。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomDialog(
    preset: ProviderPreset,
    existing: Boolean,
    loadCustom: (suspend (id: String) -> CustomEndpointPrefill?)?,
    discoverModels: (suspend (id: String, baseUrl: String, rawKey: String) -> Outcome<List<String>>)?,
    testConnection: (suspend (ConnectionTarget) -> ConnectionCheck)?,
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
    var discovery by remember { mutableStateOf(DiscoveryUi()) }
    val discovered = discovery.discovered
    val discovering = discovery.running
    val discoverMsg = discovery.message
    val discoverGate = remember { LatestOnly() }
    var testing by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<ConnectionCheck?>(null) }
    val keyReady = KeyText.clean(key).isNotEmpty() || existing

    /** 填好地址和 Key 后自动获取模型列表（piweb-study G2）；失败给出原因，允许手填。 */
    // 只接受最新一次请求的结果：旧请求（阻塞的 HTTP 取消不掉）晚到时不覆盖新结果，取消也不当成失败显示。
    suspend fun discover() {
        val d = discoverModels ?: return
        if (url.isBlank() || !keyReady) return
        val token = discoverGate.begin()
        discovery = discovery.started()
        try {
            val r = discoverGate.run(
                token,
                onError = { Outcome.Err(io.github.zr987459.pigo.contract.ErrCode.UNKNOWN, it.message ?: "") },
            ) { d(id.ifBlank { preset.provider }, url, key) } ?: return
            when (r) {
                is Outcome.Ok -> discovery = discovery.succeeded(r.value)
                is Outcome.Err -> discovery = discovery.failed(r.message)
            }
        } finally {
            if (discoverGate.isLatest(token)) discovery = discovery.finished()
        }
    }

    // 地址和 Key 都填好后停顿片刻自动获取一次（输入过程中不反复请求）。
    LaunchedEffect(url, key, existing) {
        // 输入变了：之前发出的请求一律作废，按钮先恢复可点（旧请求可能还卡在网络上）；
        // 旧地址取到的模型列表和提示一并清掉，不能再点选加入（审查第 2 轮 Nit）。
        discoverGate.invalidate()
        discovery = discovery.inputChanged()
        if (discoverModels == null || url.isBlank() || !keyReady) return@LaunchedEffect
        kotlinx.coroutines.delay(800)
        discover()
    }

    LaunchedEffect(preset.provider, existing) {
        if (!existing || loadCustom == null) return@LaunchedEffect
        val pre = runCatching { loadCustom(preset.provider) }.getOrNull() ?: return@LaunchedEffect
        // 用户还没动过才预填，避免覆盖正在输入的内容
        if (url.isBlank() || url == preset.defaultBaseUrl.orEmpty()) url = pre.baseUrl.ifBlank { url }
        if (models.isBlank()) models = pre.modelIds.joinToString(", ")
    }
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
                    shape = FieldShape,
                    colors = plainFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it; error = null },
                    label = { Text("接口地址（baseUrl）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    shape = FieldShape,
                    colors = plainFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it; error = null },
                    label = { Text(if (existing) "API Key（留空 = 保持原来的 Key）" else "API Key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    shape = FieldShape,
                    colors = plainFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                KeyText.preview(key)?.let { Text("将保存为 $it", style = MaterialTheme.typography.bodySmall) }
                if (discoverModels != null) {
                    PlainOutlined(
                        enabled = !discovering && url.isNotBlank() && keyReady,
                        onClick = { scope.launch { discover() } },
                        modifier = Modifier.testTag("settings:discover"),
                    ) { Text(if (discovering) "正在获取模型列表…" else "获取模型列表") }
                    discoverMsg?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (discovered.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("settings:discoverMsg"),
                        )
                    }
                    if (discovered.isNotEmpty()) {
                        val chosen = KeyText.splitModels(models)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            discovered.forEach { m ->
                                FilterChip(
                                    selected = m in chosen,
                                    onClick = { models = KeyText.toggleModel(models, m); error = null },
                                    label = { Text(m) },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = models, onValueChange = { models = it; error = null },
                    label = { Text("模型 id（多个用逗号或换行分开）") },
                    placeholder = { Text(preset.note) },
                    shape = FieldShape,
                    colors = plainFieldColors(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                )
                if (testConnection != null) {
                    PlainOutlined(
                        enabled = !testing && !saving && url.isNotBlank() && keyReady && KeyText.splitModels(models).isNotEmpty(),
                        onClick = {
                            testing = true
                            check = null
                            scope.launch {
                                val target = ConnectionTarget(id.ifBlank { preset.provider }, true, url, key, KeyText.splitModels(models).first())
                                check = runCatching { testConnection(target) }.getOrElse { ConnectionCheck(false, "测试失败：${it.message}") }
                                testing = false
                            }
                        },
                        modifier = Modifier.testTag("settings:testCustom"),
                    ) { Text(if (testing) "正在测试…" else "测试连接") }
                    check?.let { CheckText(it) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            PlainFilled(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    error = onSave(id, url, key, KeyText.splitModels(models))
                    saving = false
                }
            }) { Text(if (saving) "保存并重启 pi…" else "保存") }
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
                        shape = FieldShape,
                        colors = plainFieldColors(),
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
            PlainFilled(enabled = !saving && provider.isNotBlank() && model.isNotBlank(), onClick = {
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
