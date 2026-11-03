package io.github.zr987459.pigo.feature.models

import io.github.zr987459.pigo.contract.AuthKind
import io.github.zr987459.pigo.contract.AuthManager
import io.github.zr987459.pigo.contract.AuthState
import io.github.zr987459.pigo.contract.CatalogEntry
import io.github.zr987459.pigo.contract.CustomModel
import io.github.zr987459.pigo.contract.CustomProvider
import io.github.zr987459.pigo.contract.CustomProviderStore
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.ModelAvailability
import io.github.zr987459.pigo.contract.ModelCatalog
import io.github.zr987459.pigo.contract.ModelRef
import io.github.zr987459.pigo.contract.OAuthStep
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.ProviderApi
import io.github.zr987459.pigo.contract.ProviderInfo
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.models.ModelPanels
import io.github.zr987459.pigo.contract.models.ModelPickerTab
import io.github.zr987459.pigo.contract.session.FormField
import io.github.zr987459.pigo.contract.session.KeyCap
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SelectGroup
import io.github.zr987459.pigo.contract.session.SelectItem
import io.github.zr987459.pigo.contract.settings.PiSettingType
import io.github.zr987459.pigo.contract.settings.PiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * [ModelPanels] 的实现（DESIGN-r3 §6、§2.8）：产出与形态无关的 [OverlayModel]，UI 形态（CalmSheet）与
 * 终端形态（TermSelectList）各自渲染；`/model`、`/scoped-models`、`/login`、`/logout`、`provider.custom` 共用。
 *
 * 面板 id 与事件（[onPanelEvent] 的 overlayId / key / value）：
 * - [ID_MODEL]：`tab:<ALL|SCOPED|RECENT>`、`query`（value = 搜索词）、`model:<provider/id>`（选择；未配置 → 服务商表单，
 *   配好后自动选回）、`star:<ref>`（常用 = `enabledModels`）、`default:<ref>`（写 default*）、`refresh`、`providers`。
 * - [ID_PROVIDERS]：`query`、`provider:<id>`、`custom`（新建自定义服务商）。
 * - `provider.form:<id>`：`submit`（value = 表单值 JSON 对象）、`oauth`、`test`、`clear`。
 * - `oauth:<id>`：`refresh`、`code`（value = 授权码 / 回调 URL）、`select:<选项>`、`cancel`。
 * - `provider.custom[:<id>]`：`discover` / `test` / `submit`（value = 表单值 JSON）、`remove`。
 * - [ID_LOGOUT]：`provider:<id>`。
 *
 * 生成中也可切模型：`set_model` 立即发出，返回提示「下一轮生效」（§6.4）。
 */
class RealModelPanels(
    private val catalog: ModelCatalog,
    private val auth: AuthManager,
    private val custom: CustomProviderStore,
    private val engine: EngineClient,
    private val scope: CoroutineScope,
    /** pi 的 settings.json（常用模型、默认模型）；为 null 时这两项只读不写。 */
    private val piSettings: PiSettings? = null,
    /** 是否正在生成；默认读引擎的 `chat.running`。 */
    private val isGenerating: () -> Boolean = { engine.chat.value.running },
    /** 凭据变化后（保存 Key、OAuth 完成、退出登录）由装配方决定如何让 pi 读到新凭据。 */
    private val onCredentialsChanged: suspend (provider: String) -> Unit = {},
) : ModelPanels {

    /** 点了未配置模型后等待配置完成再自动选回的模型（W5 PendingModel 思路）。 */
    private val _pending = MutableStateFlow<ModelRef?>(null)
    val pending: StateFlow<ModelRef?> = _pending.asStateFlow()

    private val _oauth = MutableStateFlow<Map<String, OAuthStep>>(emptyMap())
    val oauthSteps: StateFlow<Map<String, OAuthStep>> = _oauth.asStateFlow()
    private val oauthJobs = mutableMapOf<String, Job>()

    /** 自定义服务商表单的已填值（发现模型后回填）。 */
    private val customDrafts = mutableMapOf<String, Map<String, String>>()

    // ---------------------------------------------------------------- 模型面板

    override suspend fun modelPicker(tab: ModelPickerTab, query: String): OverlayModel {
        val entries = catalog.entries().okOr(emptyList())
        val providers = catalog.providers().okOr(emptyList())
        val names = providers.associate { it.id to it.displayName }
        val current = engine.chat.value.model?.let { ModelRef(it.provider, it.id) }
        val scoped = scopedRefs()
        val q = query.trim().lowercase()
        fun item(e: CatalogEntry) = SelectItem(
            key = "model:${e.model.ref}",
            label = e.model.name ?: e.model.id,
            detail = listOfNotNull(
                names[e.model.provider] ?: e.model.provider,
                PickerLogic.contextText(e.model.contextWindow),
                if (e.availability == ModelAvailability.NEEDS_AUTH) "未配置" else null,
            ).joinToString(" · "),
            starred = e.model.ref.toString() in scoped,
            enabled = true,
            keyHint = if (e.model.ref == current) "当前" else null,
        )
        val groups = when (tab) {
            ModelPickerTab.ALL -> PickerLogic.sections(entries, providers, emptyList(), q).map { s ->
                SelectGroup(if (s.ready) "已配置" else "${s.title} · 未配置", s.rows.map(::item))
            }
            ModelPickerTab.SCOPED -> {
                val byRef = entries.associateBy { it.model.ref.toString() }
                listOf(SelectGroup("常用", scoped.mapNotNull { byRef[it] }.filter { PickerLogic.matches(it, names[it.model.provider], q) }.map(::item)))
            }
            ModelPickerTab.RECENT -> {
                val byRef = entries.associateBy { it.model.ref }
                listOf(SelectGroup("最近", catalog.recent().mapNotNull { byRef[it] }.filter { PickerLogic.matches(it, names[it.model.provider], q) }.map(::item)))
            }
        }
        return OverlayModel.SelectList(
            id = ID_MODEL,
            title = "选择模型",
            groups = groups,
            query = query,
            tabs = TAB_LABELS.values.toList(),
            tab = TAB_LABELS.getValue(tab),
            selectedKey = current?.let { "model:$it" },
            keyCaps = listOf(
                KeyCap("Enter", "选择", "$ID_MODEL:select"),
                KeyCap("Ctrl+S", "设为默认", "$ID_MODEL:default"),
                KeyCap("Space", "常用", "$ID_MODEL:star"),
                KeyCap("R", "刷新", "$ID_MODEL:refresh"),
                KeyCap("P", "管理服务商", "$ID_MODEL:providers"),
            ),
        )
    }

    // ---------------------------------------------------------------- 服务商

    override suspend fun providerList(query: String): OverlayModel {
        val providers = catalog.providers().okOr(emptyList())
        val states = providers.associate { it.id to auth.state(it.id) }
        val customs = custom.list()
        val shown = providers.filter { ProviderSearch.matches(ProviderSearch.Row(it.id, it.displayName), query) }
        val connected = shown.filter { states[it.id]?.configured == true }
        val rest = shown.filter { states[it.id]?.configured != true }
        return OverlayModel.SelectList(
            id = ID_PROVIDERS,
            title = "服务商",
            query = query,
            groups = listOfNotNull(
                connected.takeIf { it.isNotEmpty() }?.let { l -> SelectGroup("已连接", l.map { providerItem(it, states[it.id]) }) },
                SelectGroup("全部服务商（${providers.size}）", rest.map { providerItem(it, states[it.id]) }),
                SelectGroup(
                    "自定义",
                    listOf(SelectItem("custom", "＋ 添加自定义服务商")) +
                        customs.map { SelectItem("custom:${it.id}", it.id, it.baseUrl, toggled = null) },
                ),
            ),
            keyCaps = listOf(KeyCap("Enter", "配置", "$ID_PROVIDERS:select")),
        )
    }

    private fun providerItem(p: ProviderInfo, st: AuthState?) = SelectItem(
        key = "provider:${p.id}",
        label = p.displayName,
        detail = authText(p, st),
        toggled = st?.configured == true,
    )

    override suspend fun providerForm(providerId: String): OverlayModel {
        val p = provider(providerId) ?: return OverlayModel.Text("provider.form:$providerId", providerId, "未知服务商：$providerId")
        val st = auth.state(providerId)
        val fields = buildList {
            if (p.envVar != null && (AuthKind.API_KEY in p.authKinds || p.fields.isEmpty())) {
                add(FormField(KEY_FIELD, "API Key", PiSettingType.Str, help = keyHelp(st)))
            }
            p.fields.forEach { add(FormField(it.envVar, it.label, PiSettingType.Str, help = it.hint)) }
        }
        val oauth = p.authKinds.any { it == AuthKind.OAUTH_PKCE || it == AuthKind.OAUTH_DEVICE }
        return OverlayModel.Form(
            id = "provider.form:$providerId",
            title = p.displayName + (if (oauth) "（可用账号登录：键 oauth）" else ""),
            fields = fields,
            values = emptyMap(), // 密文不回填；只显示末四位（help）
            submitLabel = "保存",
        )
    }

    override suspend fun oauthFlow(providerId: String): OverlayModel {
        if (oauthJobs[providerId]?.isActive != true) {
            _oauth.update { it + (providerId to OAuthStep.Starting) }
            oauthJobs[providerId] = scope.launch {
                auth.login(providerId).collect { step ->
                    _oauth.update { it + (providerId to step) }
                    if (step is OAuthStep.Done) credentialsReady(providerId)
                }
            }
        }
        return oauthPanel(providerId)
    }

    private fun oauthPanel(providerId: String): OverlayModel {
        val id = "oauth:$providerId"
        val title = "登录 $providerId"
        return when (val s = _oauth.value[providerId] ?: OAuthStep.Starting) {
            OAuthStep.Starting -> OverlayModel.Text(id, title, "正在开始登录…")
            is OAuthStep.OpenUrl -> OverlayModel.Text(id, title, listOfNotNull("在浏览器中打开：", s.url, s.instructions).joinToString("\n"))
            is OAuthStep.DeviceCode -> OverlayModel.Text(id, title, "打开 ${s.verificationUri}\n输入代码：${s.userCode}")
            is OAuthStep.NeedCode -> OverlayModel.Form(id, title, listOf(FormField("code", s.prompt, PiSettingType.Str)), submitLabel = "提交")
            is OAuthStep.Select -> OverlayModel.SelectList(id, s.prompt, listOf(SelectGroup(null, s.options.map { SelectItem("select:$it", it) })))
            is OAuthStep.Progress -> OverlayModel.Text(id, title, s.message)
            is OAuthStep.Done -> OverlayModel.Text(id, title, "已登录" + ((s.state as? AuthState.OAuth)?.accountLabel?.let { "：$it" } ?: ""))
            is OAuthStep.Failed -> OverlayModel.Text(id, title, "登录失败：${s.message}")
        }
    }

    override suspend fun logoutPicker(): OverlayModel {
        val providers = catalog.providers().okOr(emptyList())
        val items = providers.mapNotNull { p ->
            val st = auth.state(p.id)
            if (st.configured) SelectItem("provider:${p.id}", p.displayName, authText(p, st)) else null
        }
        return OverlayModel.SelectList(ID_LOGOUT, "退出登录", listOf(SelectGroup(if (items.isEmpty()) "没有已登录的服务商" else null, items)))
    }

    override suspend fun customProviderForm(providerId: String?): OverlayModel {
        val existing = providerId?.let { id -> custom.list().firstOrNull { it.id == id } }
        val draftKey = providerId ?: ""
        val values = customDrafts[draftKey] ?: existing?.let { c ->
            mapOf(
                "id" to c.id,
                "baseUrl" to c.baseUrl,
                "api" to c.api.wire,
                "models" to c.models.joinToString(",") { it.id },
                "headers" to c.headers.entries.joinToString(",") { "${it.key}: ${it.value}" },
            )
        } ?: mapOf("api" to ProviderApi.OPENAI_COMPLETIONS.wire)
        return OverlayModel.Form(
            id = if (providerId == null) ID_CUSTOM else "$ID_CUSTOM:$providerId",
            title = if (providerId == null) "添加自定义服务商" else providerId,
            fields = listOf(
                FormField("id", "名称（id）", PiSettingType.Str, enabled = providerId == null),
                FormField("baseUrl", "接口地址", PiSettingType.Str, help = "如 http://10.0.0.2:8000/v1"),
                FormField("api", "接口类型", PiSettingType.Enum(ProviderApi.entries.map { it.wire })),
                FormField(KEY_FIELD, "API Key", PiSettingType.Str, help = "留空保持原值；可写 !命令"),
                FormField("models", "模型", PiSettingType.StrList, help = "「发现模型」自动填；也可手动加"),
                FormField("headers", "请求头", PiSettingType.StrList, help = "Name: value"),
            ),
            values = values,
            submitLabel = "保存",
        )
    }

    // ---------------------------------------------------------------- 事件

    override suspend fun onPanelEvent(overlayId: String, key: String, value: String?): ActionResult = when {
        overlayId == ID_MODEL -> onModelEvent(key, value)
        overlayId == ID_PROVIDERS -> onProvidersEvent(key, value)
        overlayId == ID_LOGOUT && key.startsWith("provider:") -> logout(key.removePrefix("provider:"))
        overlayId.startsWith("provider.form:") -> onProviderFormEvent(overlayId.removePrefix("provider.form:"), key, value)
        overlayId.startsWith("oauth:") -> onOAuthEvent(overlayId.removePrefix("oauth:"), key, value)
        overlayId == ID_CUSTOM || overlayId.startsWith("$ID_CUSTOM:") ->
            onCustomEvent(overlayId.removePrefix(ID_CUSTOM).removePrefix(":").ifEmpty { null }, key, value)
        else -> ActionResult.Toast("未知面板操作：$overlayId/$key")
    }

    private suspend fun onModelEvent(key: String, value: String?): ActionResult = when {
        key.startsWith("tab:") -> ActionResult.Overlay(modelPicker(ModelPickerTab.valueOf(key.removePrefix("tab:")), value.orEmpty()))
        key == "query" -> ActionResult.Overlay(modelPicker(ModelPickerTab.ALL, value.orEmpty()))
        key == "refresh" -> when (val r = catalog.refresh()) {
            is Outcome.Ok -> ActionResult.Overlay(modelPicker())
            is Outcome.Err -> ActionResult.Toast("刷新失败：${r.message}")
        }
        key == "providers" -> ActionResult.Overlay(providerList())
        key.startsWith("model:") -> ModelRef.parse(key.removePrefix("model:"))?.let { selectModel(it) } ?: ActionResult.Toast("无效模型")
        key.startsWith("star:") -> toggleScoped(key.removePrefix("star:"))
        key.startsWith("default:") -> ModelRef.parse(key.removePrefix("default:"))?.let { setDefault(it) } ?: ActionResult.Toast("无效模型")
        else -> ActionResult.Toast("未知操作：$key")
    }

    /** 选模型：已配置 → `set_model`（生成中也发，提示「下一轮生效」）；未配置 → 记下待选，打开该服务商表单。 */
    suspend fun selectModel(ref: ModelRef): ActionResult {
        val entry = catalog.find(ref) ?: return ActionResult.Toast("目录里没有 $ref")
        if (entry.availability != ModelAvailability.READY) {
            _pending.value = ref
            return ActionResult.Overlay(providerForm(ref.provider))
        }
        return applyModel(entry)
    }

    private suspend fun applyModel(entry: CatalogEntry): ActionResult {
        val generating = isGenerating()
        return when (val r = engine.setModel(entry.model.provider, entry.model.id)) {
            is Outcome.Ok -> {
                catalog.markUsed(entry.model.ref)
                if (_pending.value == entry.model.ref) _pending.value = null
                val name = entry.model.name ?: entry.model.id
                ActionResult.Toast(if (generating) "已切换到 $name，$NEXT_TURN" else "已切换到 $name")
            }
            is Outcome.Err -> ActionResult.Toast("切换失败：${r.message}")
        }
    }

    /** 凭据刚配好：若有待选模型属于该服务商且已可用，自动选回。 */
    private suspend fun credentialsReady(provider: String): ActionResult? {
        onCredentialsChanged(provider)
        val p = _pending.value ?: return null
        if (p.provider != provider) return null
        val e = catalog.find(p) ?: return null
        if (e.availability != ModelAvailability.READY) return null
        return applyModel(e)
    }

    private suspend fun onProvidersEvent(key: String, value: String?): ActionResult = when {
        key == "query" -> ActionResult.Overlay(providerList(value.orEmpty()))
        key == "custom" -> ActionResult.Overlay(customProviderForm(null))
        key.startsWith("custom:") -> ActionResult.Overlay(customProviderForm(key.removePrefix("custom:")))
        key.startsWith("provider:") -> {
            val id = key.removePrefix("provider:")
            val p = provider(id)
            if (p != null && p.envVar == null && p.fields.isEmpty() && p.authKinds.any { it == AuthKind.OAUTH_PKCE || it == AuthKind.OAUTH_DEVICE }) {
                ActionResult.Overlay(oauthFlow(id))
            } else ActionResult.Overlay(providerForm(id))
        }
        else -> ActionResult.Toast("未知操作：$key")
    }

    private suspend fun onProviderFormEvent(providerId: String, key: String, value: String?): ActionResult = when (key) {
        "oauth" -> ActionResult.Overlay(oauthFlow(providerId))
        "clear" -> logout(providerId)
        "test" -> testProvider(providerId)
        "submit" -> {
            val p = provider(providerId)
            val values = parseValues(value)
            val rawKey = values[KEY_FIELD]?.takeIf { it.isNotBlank() }
            val extra = p?.let { ProviderFormLogic.fieldsToSave(it.fields, values, shown = emptySet()) } ?: emptyMap()
            when (val r = auth.setApiKey(providerId, rawKey, extra)) {
                is Outcome.Err -> ActionResult.Toast("保存失败：${r.message}")
                is Outcome.Ok -> credentialsReady(providerId) ?: ActionResult.Toast("已保存")
            }
        }
        else -> ActionResult.Toast("未知操作：$key")
    }

    private suspend fun onOAuthEvent(providerId: String, key: String, value: String?): ActionResult = when {
        key == "refresh" -> ActionResult.Overlay(oauthPanel(providerId))
        key == "code" -> {
            auth.submitCode(providerId, parseValues(value)["code"] ?: value.orEmpty())
            ActionResult.Overlay(oauthPanel(providerId))
        }
        key.startsWith("select:") -> {
            auth.submitSelection(providerId, key.removePrefix("select:"))
            ActionResult.Overlay(oauthPanel(providerId))
        }
        key == "cancel" -> {
            oauthJobs.remove(providerId)?.cancel()
            _oauth.update { it - providerId }
            ActionResult.Done
        }
        key == "done" -> {
            val ok = _oauth.value[providerId] is OAuthStep.Done
            if (ok) (pendingSelectedToast(providerId) ?: ActionResult.Done) else ActionResult.Overlay(oauthPanel(providerId))
        }
        else -> ActionResult.Toast("未知操作：$key")
    }

    /** OAuth 完成后自动选回已在 [credentialsReady] 里做；这里只把结果告诉形态。 */
    private fun pendingSelectedToast(providerId: String): ActionResult? =
        if (_pending.value == null) ActionResult.Toast("$providerId 已登录") else null

    private suspend fun onCustomEvent(providerId: String?, key: String, value: String?): ActionResult {
        val values = parseValues(value)
        val draftKey = providerId ?: ""
        if (values.isNotEmpty()) customDrafts[draftKey] = values
        val id = providerId ?: values["id"].orEmpty().trim()
        val api = ProviderApi.fromWire(values["api"]) ?: ProviderApi.OPENAI_COMPLETIONS
        val rawKey = values[KEY_FIELD]?.takeIf { it.isNotBlank() }
        return when (key) {
            "discover" -> when (val r = custom.discover(values["baseUrl"].orEmpty().trim(), api, rawKey, providerId)) {
                is Outcome.Ok -> {
                    customDrafts[draftKey] = values + ("models" to r.value.joinToString(",") { it.id })
                    ActionResult.Overlay(customProviderForm(providerId))
                }
                is Outcome.Err -> ActionResult.Toast("发现模型失败：${r.message}")
            }
            "submit", "test" -> {
                if (id.isEmpty()) return ActionResult.Toast("请填写名称")
                val cp = CustomProvider(
                    id = id,
                    baseUrl = values["baseUrl"].orEmpty().trim(),
                    api = api,
                    models = splitList(values["models"]).map { CustomModel(it) },
                    headers = splitList(values["headers"]).mapNotNull { h ->
                        val i = h.indexOf(':'); if (i <= 0) null else h.substring(0, i).trim() to h.substring(i + 1).trim()
                    }.toMap(),
                )
                when (val r = custom.upsert(cp, rawKey)) {
                    is Outcome.Err -> ActionResult.Toast("保存失败：${r.message}")
                    is Outcome.Ok -> {
                        customDrafts.remove(draftKey)
                        onCredentialsChanged(id)
                        if (key == "submit") ActionResult.Toast("已保存 $id")
                        else {
                            val m = cp.models.firstOrNull() ?: return ActionResult.Toast("已保存；没有模型可测试")
                            probeText(custom.test(ModelRef(id, m.id)))
                        }
                    }
                }
            }
            "remove" -> when (val r = custom.remove(id)) {
                is Outcome.Ok -> ActionResult.Toast("已删除 $id")
                is Outcome.Err -> ActionResult.Toast("删除失败：${r.message}")
            }
            else -> ActionResult.Toast("未知操作：$key")
        }
    }

    private suspend fun testProvider(providerId: String): ActionResult {
        val m = catalog.entries(providerId).okOr(emptyList()).firstOrNull() ?: return ActionResult.Toast("$providerId 没有可测试的模型")
        return probeText(custom.test(m.model.ref))
    }

    private fun probeText(r: Outcome<io.github.zr987459.pigo.contract.ProbeOutcome>): ActionResult = when (r) {
        is Outcome.Ok -> ActionResult.Toast((if (r.value.ok) "✓ " else "✗ ") + r.value.message + " · ${r.value.elapsedMs}ms")
        is Outcome.Err -> ActionResult.Toast("测试失败：${r.message}")
    }

    private suspend fun logout(providerId: String): ActionResult = when (val r = auth.clear(providerId)) {
        is Outcome.Ok -> { onCredentialsChanged(providerId); ActionResult.Toast("已退出 $providerId") }
        is Outcome.Err -> ActionResult.Toast("退出失败：${r.message}")
    }

    // ---------------------------------------------------------------- settings.json

    private fun scopedRefs(): List<String> =
        (piSettings?.values?.value?.get("enabledModels") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    private suspend fun toggleScoped(ref: String): ActionResult {
        val s = piSettings ?: return ActionResult.Toast("常用模型暂不可编辑")
        val cur = scopedRefs()
        val next = if (ref in cur) cur - ref else cur + ref
        return when (val r = s.set("enabledModels", JsonArray(next.map(::JsonPrimitive)))) {
            is Outcome.Ok -> ActionResult.Overlay(modelPicker(ModelPickerTab.SCOPED))
            is Outcome.Err -> ActionResult.Toast("保存失败：${r.message}")
        }
    }

    private suspend fun setDefault(ref: ModelRef): ActionResult {
        val s = piSettings ?: return ActionResult.Toast("默认模型暂不可编辑")
        val a = s.set("defaultProvider", JsonPrimitive(ref.provider))
        val b = s.set("defaultModel", JsonPrimitive(ref.id))
        return if (a is Outcome.Ok && b is Outcome.Ok) ActionResult.Toast("已设为默认：$ref")
        else ActionResult.Toast("保存失败")
    }

    // ---------------------------------------------------------------- 工具

    private suspend fun provider(id: String): ProviderInfo? = catalog.providers().okOr(emptyList()).firstOrNull { it.id == id }

    private fun authText(p: ProviderInfo, st: AuthState?): String = when (st) {
        is AuthState.ApiKey -> "Key ···${st.last4.orEmpty()}"
        is AuthState.OAuth -> "账号" + (st.accountLabel?.let { " · $it" } ?: "")
        is AuthState.CloudEnv -> if (st.configuredVars.isEmpty()) "未配置" else "已配置 ${st.configuredVars.size} 项"
        is AuthState.Error -> "错误：${st.message}"
        AuthState.None, null -> when {
            p.authKinds.contains(AuthKind.OAUTH_PKCE) || p.authKinds.contains(AuthKind.OAUTH_DEVICE) -> "账号 / Key"
            p.authKinds.contains(AuthKind.CLOUD_ENV) -> "区域 / Key"
            else -> "Key"
        }
    }

    private fun keyHelp(st: AuthState): String =
        if (st is AuthState.ApiKey && st.last4 != null) "已保存 ···${st.last4}，留空保持不变；可写 !命令" else "可写 !命令 从命令输出读取"

    companion object {
        const val ID_MODEL = "model.select"
        const val ID_PROVIDERS = "auth.login"
        const val ID_LOGOUT = "auth.logout"
        const val ID_CUSTOM = "provider.custom"
        const val KEY_FIELD = "apiKey"
        const val NEXT_TURN = "下一轮生效"

        val TAB_LABELS = linkedMapOf(ModelPickerTab.ALL to "全部", ModelPickerTab.SCOPED to "常用", ModelPickerTab.RECENT to "最近")

        /** 表单值：JSON 对象（字段 key → 字符串）。不是 JSON 时返回空。 */
        fun parseValues(value: String?): Map<String, String> {
            if (value.isNullOrBlank()) return emptyMap()
            return runCatching {
                (Json.parseToJsonElement(value) as JsonObject).mapValues { (_, v) ->
                    when (v) {
                        is JsonArray -> v.jsonArray.joinToString(",") { it.jsonPrimitive.content }
                        else -> v.jsonPrimitive.content
                    }
                }
            }.getOrDefault(emptyMap())
        }

        fun splitList(s: String?): List<String> = s.orEmpty().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

        private fun <T> Outcome<T>.okOr(default: T): T = (this as? Outcome.Ok)?.value ?: default
    }
}
