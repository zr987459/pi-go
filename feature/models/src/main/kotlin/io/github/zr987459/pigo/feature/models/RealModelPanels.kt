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
import io.github.zr987459.pigo.contract.session.FlowState
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
import kotlinx.serialization.json.JsonElement
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
 * - 键帽（[KeyCap.actionId]）就是本面板的事件 key（`select` / `default` / `star` / `refresh` / `providers`），渲染器回传时以
 *   **当前高亮项的 key** 作 value（如 `model:anthropic/claude-sonnet-4-5`）；也接受带面板前缀的写法 `model.select:default`。
 * - [ID_MODEL]：`tab:<ALL|SCOPED|RECENT 或页签标签 全部|常用|最近>`（未知值回到 ALL，不抛异常）、`query`（value = 搜索词，保持当前页签）、`model:<provider/id>`（选择；未配置 → 服务商表单，
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
    /** 毫秒时钟（测试可替换）：OAuth「正在验证…」的超时判断用。 */
    private val clock: () -> Long = System::currentTimeMillis,
) : ModelPanels {

    /** 点了未配置模型后等待配置完成再自动选回的模型（W5 PendingModel 思路）。 */
    private val _pending = MutableStateFlow<ModelRef?>(null)
    val pending: StateFlow<ModelRef?> = _pending.asStateFlow()

    private val _oauth = MutableStateFlow<Map<String, OAuthStep>>(emptyMap())
    /** 每个服务商最近一次的授权链接：NeedCode 紧跟 OpenUrl 到来，表单里要能再打开（PR #21 审查第 1 轮 Important 4，r3/app 最小改动）。 */
    private val oauthUrls = java.util.concurrent.ConcurrentHashMap<String, String>()
    val oauthSteps: StateFlow<Map<String, OAuthStep>> = _oauth.asStateFlow()
    private val oauthJobs = mutableMapOf<String, Job>()

    /**
     * 已提交代码 / 选择、还在等登录会话推进的服务商 → 提交时刻（审查第 3 轮 Important）。
     * 在途时面板显示「正在验证…」（Text，渲染器会持续 refresh），重复提交直接忽略；登录流发出任何新步骤即清除。
     */
    private val oauthSubmitted = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 名称或接口地址变了、之前填的 Key 被丢弃的草稿：表单与结果消息里提示重新填写（审查第 3 轮 Nit 3）。 */
    private val keyDropped = mutableSetOf<String>()

    /** 自定义服务商表单的已填值（发现模型后回填）；**不含 API Key**。 */
    private val customDrafts = mutableMapOf<String, Map<String, String>>()

    /** 发现到的模型（含推理 / 看图等能力），保存时用它们而不是只按 id 重建。 */
    private val discoveredModels = mutableMapOf<String, List<CustomModel>>()

    /** 表单里填了但还没保存的 Key：只在内存里，不进 [OverlayModel.Form.values]，也就不进 OverlayStack。 */
    private val pendingKeys = mutableMapOf<String, String>()

    /** [pendingKeys] 填写时的「名称|接口地址」：之后两者任一变化就丢弃该 Key。 */
    private val pendingKeyOwner = mutableMapOf<String, String>()

    /** 模型面板当前页签（搜索时保持）。 */
    @Volatile private var modelTab: ModelPickerTab = ModelPickerTab.ALL
    @Volatile private var modelQuery: String = ""

    // ---------------------------------------------------------------- 模型面板

    override suspend fun modelPicker(tab: ModelPickerTab, query: String): OverlayModel {
        modelTab = tab
        modelQuery = query
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
                KeyCap("Enter", "选择", CAP_SELECT),
                KeyCap("Ctrl+S", "设为默认", CAP_DEFAULT),
                KeyCap("Space", "常用", CAP_STAR),
                KeyCap("R", "刷新", CAP_REFRESH),
                KeyCap("P", "管理服务商", CAP_PROVIDERS),
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
            keyCaps = listOf(KeyCap("Enter", "配置", CAP_SELECT)),
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
                add(FormField(KEY_FIELD, "API Key", PiSettingType.Str, help = keyHelp(st), secret = true))
            }
            p.fields.forEach { add(FormField(it.envVar, it.label, PiSettingType.Str, help = it.hint)) }
        }
        val oauth = p.authKinds.any { it == AuthKind.OAUTH_PKCE || it == AuthKind.OAUTH_DEVICE }
        return OverlayModel.Form(
            id = "provider.form:$providerId",
            title = p.displayName,
            fields = fields,
            values = emptyMap(), // 密文不回填；只显示末四位（help）
            submitLabel = "保存",
            actions = providerFormActions(oauth = oauth, configured = st.configured, hasKeyFields = fields.isNotEmpty()),
        )
    }

    override suspend fun oauthFlow(providerId: String): OverlayModel {
        if (oauthJobs[providerId]?.isActive != true) {
            // 新的登录流：上一次的授权链接已失效，不能再出现在新流程的表单里（PR #21 审查第 2 轮 Nit 4）。
            oauthUrls.remove(providerId)
            _oauth.update { it + (providerId to OAuthStep.Starting) }
            oauthJobs[providerId] = scope.launch {
                auth.login(providerId).collect { step ->
                    when (step) {
                        is OAuthStep.OpenUrl -> oauthUrls[providerId] = step.url
                        is OAuthStep.Done, is OAuthStep.Failed -> oauthUrls.remove(providerId) // 流程结束
                        else -> Unit
                    }
                    _oauth.update { it + (providerId to step) }
                    oauthSubmitted.remove(providerId)
                    if (step is OAuthStep.Done) credentialsReady(providerId)
                }
            }
        }
        return oauthPanel(providerId)
    }

    private fun oauthPanel(providerId: String): OverlayModel {
        val id = "oauth:$providerId"
        val title = "登录 $providerId"
        val submittedAt = oauthSubmitted[providerId]
        if (submittedAt != null) {
            if (clock() - submittedAt < OAUTH_VERIFY_TIMEOUT_MS) return OverlayModel.Text(id, title, OAUTH_VERIFYING, flow = FlowState.RUNNING)
            oauthSubmitted.remove(providerId) // 登录会话一直没有回应：回到当前步骤，允许再提交
        }
        return when (val s = _oauth.value[providerId] ?: OAuthStep.Starting) {
            OAuthStep.Starting -> OverlayModel.Text(id, title, "正在开始登录…", flow = FlowState.RUNNING)
            is OAuthStep.OpenUrl -> OverlayModel.Text(id, title, listOfNotNull("在浏览器中打开：", s.url, s.instructions).joinToString("\n"), flow = FlowState.RUNNING)
            is OAuthStep.DeviceCode -> OverlayModel.Text(id, title, "打开 ${s.verificationUri}\n输入代码：${s.userCode}", flow = FlowState.RUNNING)
            is OAuthStep.NeedCode -> OverlayModel.Form(
                id, title,
                listOf(FormField("code", s.prompt, PiSettingType.Str, help = oauthUrls[providerId]?.let { "授权链接：$it" })),
                submitLabel = "提交",
            )
            is OAuthStep.Select -> OverlayModel.SelectList(id, s.prompt, listOf(SelectGroup(null, s.options.map { SelectItem("select:$it", it) })))
            is OAuthStep.Progress -> OverlayModel.Text(id, title, s.message, flow = FlowState.RUNNING)
            is OAuthStep.Done -> OverlayModel.Text(id, title, "已登录" + ((s.state as? AuthState.OAuth)?.accountLabel?.let { "：$it" } ?: ""), flow = FlowState.DONE)
            is OAuthStep.Failed -> OverlayModel.Text(id, title, "登录失败：${s.message}\n可点「重试」重新登录", flow = FlowState.FAILED)
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
        val values = (customDrafts[draftKey] ?: existing?.let { c ->
            mapOf(
                "id" to c.id,
                "baseUrl" to c.baseUrl,
                "api" to c.api.wire,
                "models" to c.models.joinToString(",") { it.id },
                "headers" to c.headers.entries.joinToString(",") { "${it.key}: ${it.value}" },
            )
        } ?: mapOf("api" to ProviderApi.OPENAI_COMPLETIONS.wire)) - KEY_FIELD
        val keyHelpText = when {
            pendingKeys.containsKey(draftKey) -> "已填写（隐藏，保存后生效）；可写 !命令"
            draftKey in keyDropped -> KEY_DROPPED
            else -> "留空保持原值；可写 !命令"
        }
        return OverlayModel.Form(
            id = if (providerId == null) ID_CUSTOM else "$ID_CUSTOM:$providerId",
            title = if (providerId == null) "添加自定义服务商" else providerId,
            fields = listOf(
                FormField("id", "名称（id）", PiSettingType.Str, enabled = providerId == null),
                FormField("baseUrl", "接口地址", PiSettingType.Str, help = "如 http://10.0.0.2:8000/v1"),
                FormField("api", "接口类型", PiSettingType.Enum(ProviderApi.entries.map { it.wire })),
                FormField(KEY_FIELD, "API Key", PiSettingType.Str, help = keyHelpText, secret = true),
                FormField("models", "模型", PiSettingType.StrList, help = "「发现模型」自动填；也可手动加"),
                FormField("headers", "请求头", PiSettingType.StrList, help = "Name: value"),
            ),
            values = values,
            submitLabel = "保存",
            actions = listOfNotNull(
                KeyCap("", "获取模型", "discover"),
                KeyCap("", "测试", "test"),
                if (existing != null) KeyCap("", "删除", "remove") else null,
            ),
        )
    }

    // ---------------------------------------------------------------- 事件

    override suspend fun onPanelEvent(overlayId: String, key: String, value: String?): ActionResult =
        dispatch(overlayId, key.removePrefix("$overlayId:"), value)

    private suspend fun dispatch(overlayId: String, key: String, value: String?): ActionResult = when {
        overlayId == ID_MODEL -> onModelEvent(key, value)
        overlayId == ID_PROVIDERS && key == CAP_SELECT ->
            value?.takeIf { it.isNotBlank() }?.let { onProvidersEvent(it, null) } ?: fail("先选中一个服务商")
        overlayId == ID_PROVIDERS -> onProvidersEvent(key, value)
        overlayId == ID_LOGOUT && key.startsWith("provider:") -> ActionResult.Overlay(clearConfirm(key.removePrefix("provider:"), CONFIRM_LOGOUT))
        // 确认框的「关闭 / 取消」等同取消整个表单：清掉已填未存的 Key、草稿和该服务商的待选模型（PR #19 定向复核 Nit 3），
        // 并回到清理后的表单（设置页关闭时不接这个结果；UI / 终端形态用它刷新栈里的表单，不再显示「已填写」）。
        overlayId.startsWith(CONFIRM_REMOVE) -> if (key == "confirm") removeCustom(overlayId.removePrefix(CONFIRM_REMOVE)) else {
            val id = overlayId.removePrefix(CONFIRM_REMOVE)
            cancelForm(id, draftKey = id)
            ActionResult.Overlay(customProviderForm(id))
        }
        overlayId.startsWith(CONFIRM_CLEAR) -> if (key == "confirm") {
            val p = overlayId.removePrefix(CONFIRM_CLEAR)
            when (val r = logout(p)) { is ActionResult.Toast -> if (!r.failed) ActionResult.Overlay(providerForm(p)) else r; else -> r }
        } else {
            val p = overlayId.removePrefix(CONFIRM_CLEAR)
            cancelForm(p, draftKey = null)
            ActionResult.Overlay(providerForm(p))
        }
        overlayId.startsWith(CONFIRM_LOGOUT) -> if (key == "confirm") {
            when (val r = logout(overlayId.removePrefix(CONFIRM_LOGOUT))) { is ActionResult.Toast -> if (!r.failed) ActionResult.Overlay(logoutPicker()) else r; else -> r }
        } else ActionResult.Done
        overlayId.startsWith("provider.form:") -> onProviderFormEvent(overlayId.removePrefix("provider.form:"), key, value)
        overlayId.startsWith("oauth:") -> onOAuthEvent(overlayId.removePrefix("oauth:"), key, value)
        overlayId == ID_CUSTOM || overlayId.startsWith("$ID_CUSTOM:") ->
            onCustomEvent(overlayId.removePrefix(ID_CUSTOM).removePrefix(":").ifEmpty { null }, key, value)
        else -> fail("未知面板操作：$overlayId/$key")
    }

    private suspend fun onModelEvent(key: String, value: String?): ActionResult = when {
        key.startsWith("tab:") -> ActionResult.Overlay(modelPicker(tabOf(key.removePrefix("tab:")), value.orEmpty()))
        key == "query" -> ActionResult.Overlay(modelPicker(modelTab, value.orEmpty()))
        key == CAP_REFRESH -> when (val r = catalog.refresh()) {
            is Outcome.Ok -> ActionResult.Overlay(modelPicker(modelTab))
            is Outcome.Err -> fail("刷新失败：${r.message}")
        }
        key == CAP_PROVIDERS -> ActionResult.Overlay(providerList())
        key == CAP_SELECT -> selectedRef(value)?.let { selectModel(it) } ?: fail("先选中一个模型")
        key == CAP_DEFAULT -> (selectedRef(value) ?: currentRef())?.let { setDefault(it) } ?: fail("先选中一个模型")
        key == CAP_STAR -> selectedRef(value)?.let { toggleScoped(it.toString()) } ?: fail("先选中一个模型")
        key.startsWith("model:") -> ModelRef.parse(key.removePrefix("model:"))?.let { selectModel(it) } ?: fail("无效模型")
        key.startsWith("star:") -> toggleScoped(key.removePrefix("star:"))
        key.startsWith("default:") -> ModelRef.parse(key.removePrefix("default:"))?.let { setDefault(it) } ?: fail("无效模型")
        else -> fail("未知操作：$key")
    }

    /** 选模型：已配置 → `set_model`（生成中也发，提示「下一轮生效」）；未配置 → 记下待选，打开该服务商表单。 */
    suspend fun selectModel(ref: ModelRef): ActionResult {
        val entry = catalog.find(ref) ?: return fail("目录里没有 $ref")
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
            is Outcome.Err -> fail("切换失败：${r.message}")
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
        // 每次「＋ 添加」都是全新表单：上一次没保存的草稿与 Key 一律丢弃（审查第 2 轮 Nit 2）。
        key == "custom" -> { forgetDraft(""); ActionResult.Overlay(customProviderForm(null)) }
        key.startsWith("custom:") -> ActionResult.Overlay(customProviderForm(key.removePrefix("custom:")))
        key.startsWith("provider:") -> {
            val id = key.removePrefix("provider:")
            val p = provider(id)
            if (p != null && p.envVar == null && p.fields.isEmpty() && p.authKinds.any { it == AuthKind.OAUTH_PKCE || it == AuthKind.OAUTH_DEVICE }) {
                ActionResult.Overlay(oauthFlow(id))
            } else ActionResult.Overlay(providerForm(id))
        }
        else -> fail("未知操作：$key")
    }

    private suspend fun onProviderFormEvent(providerId: String, key: String, value: String?): ActionResult = when (key) {
        "oauth" -> ActionResult.Overlay(oauthFlow(providerId))
        "clear" -> ActionResult.Overlay(clearConfirm(providerId, CONFIRM_CLEAR))
        "test" -> testProvider(providerId)
        "submit" -> {
            val p = provider(providerId)
            val values = parseValues(value)
            val rawKey = values[KEY_FIELD]?.takeIf { it.isNotBlank() }
            val extra = p?.let { ProviderFormLogic.fieldsToSave(it.fields, values, shown = emptySet()) } ?: emptyMap()
            when (val r = auth.setApiKey(providerId, rawKey, extra)) {
                is Outcome.Err -> fail("保存失败：${r.message}")
                is Outcome.Ok -> credentialsReady(providerId) ?: ActionResult.Toast("已保存")
            }
        }
        else -> fail("未知操作：$key")
    }

    private suspend fun onOAuthEvent(providerId: String, key: String, value: String?): ActionResult = when {
        key == "refresh" -> ActionResult.Overlay(oauthPanel(providerId))
        key == "code" || key == "submit" -> submitOAuth(providerId) {
            auth.submitCode(providerId, parseValues(value)["code"] ?: value.orEmpty())
        }
        key.startsWith("select:") -> submitOAuth(providerId) {
            auth.submitSelection(providerId, key.removePrefix("select:"))
        }
        key == "retry" -> {
            oauthJobs.remove(providerId)?.cancel()
            oauthSubmitted.remove(providerId)
            oauthUrls.remove(providerId)
            _oauth.update { it - providerId }
            ActionResult.Overlay(oauthFlow(providerId))
        }
        key == "cancel" -> {
            oauthJobs.remove(providerId)?.cancel()
            oauthUrls.remove(providerId)
            _oauth.update { it - providerId }
            ActionResult.Done
        }
        key == "done" -> {
            val ok = _oauth.value[providerId] is OAuthStep.Done
            if (ok) (pendingSelectedToast(providerId) ?: ActionResult.Done) else ActionResult.Overlay(oauthPanel(providerId))
        }
        else -> fail("未知操作：$key")
    }

    /**
     * 提交 OAuth 代码或选择：只在当前步骤确实在等输入时提交一次；随即返回「正在验证…」，
     * 由渲染器的 refresh 拉到成功 / 失败 / 下一步。在途时重复点击不再提交。
     */
    private suspend fun submitOAuth(providerId: String, submit: suspend () -> Unit): ActionResult {
        val step = _oauth.value[providerId]
        val awaiting = step is OAuthStep.NeedCode || step is OAuthStep.Select || step is OAuthStep.OpenUrl
        if (oauthSubmitted.containsKey(providerId) || !awaiting) return ActionResult.Overlay(oauthPanel(providerId))
        if (oauthSubmitted.putIfAbsent(providerId, clock()) != null) return ActionResult.Overlay(oauthPanel(providerId))
        submit()
        return ActionResult.Overlay(oauthPanel(providerId))
    }

    /** OAuth 完成后自动选回已在 [credentialsReady] 里做；这里只把结果告诉形态。 */
    private fun pendingSelectedToast(providerId: String): ActionResult? =
        if (_pending.value == null) ActionResult.Toast("$providerId 已登录") else null

    private suspend fun onCustomEvent(providerId: String?, key: String, value: String?): ActionResult {
        val values = parseValues(value)
        val draftKey = providerId ?: ""
        val owner = values["id"].orEmpty().trim().let { providerId ?: it } + "|" + values["baseUrl"].orEmpty().trim()
        val typedKey = values[KEY_FIELD]?.takeIf { it.isNotBlank() }
        if (typedKey != null) {
            pendingKeys[draftKey] = typedKey
            pendingKeyOwner[draftKey] = owner
        } else if (values.isNotEmpty() && pendingKeyOwner[draftKey] != null && pendingKeyOwner[draftKey] != owner) {
            // 名称或接口地址变了：之前填的 Key 不属于这个服务器，丢弃（不会把 A 的 Key 发给 B）。
            pendingKeys.remove(draftKey); pendingKeyOwner.remove(draftKey)
            keyDropped += draftKey
        }
        if (typedKey != null) keyDropped -= draftKey
        val r = onCustomEventInner(providerId, key, values, draftKey)
        // 处理**之后**再判断：保存成功 / 取消 / 删除时 forgetDraft 已清掉丢弃标记，成功提示不再要求重新填 Key（PR #19 定向复核 Nit 1）。
        return withKeyNotice(draftKey in keyDropped, r)
    }

    private fun withKeyNotice(dropped: Boolean, r: ActionResult): ActionResult =
        if (dropped && r is ActionResult.Toast) r.copy(text = r.text + "\n" + KEY_DROPPED) else r

    private suspend fun onCustomEventInner(providerId: String?, key: String, values: Map<String, String>, draftKey: String): ActionResult {
        if (values.isNotEmpty()) customDrafts[draftKey] = values - KEY_FIELD
        val id = providerId ?: values["id"].orEmpty().trim()
        val api = ProviderApi.fromWire(values["api"]) ?: ProviderApi.OPENAI_COMPLETIONS
        val baseUrl = values["baseUrl"].orEmpty().trim()
        val rawKey = pendingKeys[draftKey]
        return when (key) {
            "discover" -> when (val r = custom.discover(baseUrl, api, rawKey, providerId)) {
                is Outcome.Ok -> {
                    discoveredModels[draftKey] = r.value
                    customDrafts[draftKey] = (values - KEY_FIELD) + ("models" to r.value.joinToString(",") { it.id })
                    ActionResult.Overlay(customProviderForm(providerId))
                }
                is Outcome.Err -> fail("发现模型失败：${r.message}")
            }
            // 测试连接**不落盘**：表单与已存配置不同（新建、改了地址 / 协议、填了新 Key）时用表单值临时探测；
            // 与已存一致时才用已存配置做完整测试。只有「保存」才写 models.json / Key。
            "test" -> {
                if (id.isEmpty()) return fail("请填写名称")
                val saved = providerId?.let { pid -> custom.list().firstOrNull { it.id == pid } }
                val sameAsSaved = saved != null && rawKey == null && saved.baseUrl == baseUrl && saved.api == api
                if (sameAsSaved) {
                    val m = splitList(values["models"]).firstOrNull() ?: saved!!.models.firstOrNull()?.id
                        ?: return fail("没有模型可测试")
                    probeText(custom.test(ModelRef(id, m)))
                } else when (val r = custom.discover(baseUrl, api, rawKey, providerId)) {
                    is Outcome.Ok -> ActionResult.Toast("✓ 连接成功，接口返回 ${r.value.size} 个模型（未保存）")
                    is Outcome.Err -> fail("✗ 测试失败（未保存）：${r.message}")
                }
            }
            "submit" -> {
                if (id.isEmpty()) return fail("请填写名称")
                val existing = providerId?.let { pid -> custom.list().firstOrNull { it.id == pid } }
                val cp = CustomProvider(
                    id = id,
                    baseUrl = baseUrl,
                    api = api,
                    models = mergeModels(splitList(values["models"]), existing?.models.orEmpty(), discoveredModels[draftKey].orEmpty()),
                    headers = splitList(values["headers"]).mapNotNull { h ->
                        val i = h.indexOf(':'); if (i <= 0) null else h.substring(0, i).trim() to h.substring(i + 1).trim()
                    }.toMap(),
                )
                when (val r = custom.upsert(cp, rawKey)) {
                    is Outcome.Err -> fail("保存失败：${r.message}")
                    is Outcome.Ok -> {
                        forgetDraft(draftKey)
                        onCredentialsChanged(id)
                        ActionResult.Toast("已保存 $id")
                    }
                }
            }
            "cancel" -> { forgetDraft(draftKey); ActionResult.Done }
            // 删除连同 Key 一起删：先确认（审查第 3 轮 Nit 1）。
            "remove" -> if (id.isEmpty()) fail("请填写名称") else ActionResult.Overlay(
                OverlayModel.Confirm("$CONFIRM_REMOVE$id", "删除自定义服务商", "删除 $id 及其 API Key？此操作不能撤销。", "删除", dangerous = true),
            )
            else -> fail("未知操作：$key")
        }
    }

    private fun clearConfirm(providerId: String, prefix: String): OverlayModel =
        OverlayModel.Confirm("$prefix$providerId", "清除凭据", "清除 $providerId 已保存的凭据（Key 或账号登录）？", "清除", dangerous = true)

    /** 确认后删除：成功即关闭面板（返回 Done），草稿与 Key 一并丢弃，表单不会再被「保存」重建。 */
    private suspend fun removeCustom(id: String): ActionResult = when (val r = custom.remove(id)) {
        is Outcome.Ok -> { forgetDraft(id); onCredentialsChanged(id); ActionResult.Done }
        is Outcome.Err -> fail("删除失败：${r.message}")
    }

    /** 关闭 / 取消表单：丢弃 [draftKey] 的草稿与已填 Key；该服务商的待选模型也不再自动选回。 */
    private fun cancelForm(providerId: String, draftKey: String?) {
        draftKey?.let(::forgetDraft)
        if (_pending.value?.provider == providerId) _pending.value = null
    }

    private fun forgetDraft(draftKey: String) {
        keyDropped.remove(draftKey)
        customDrafts.remove(draftKey)
        discoveredModels.remove(draftKey)
        pendingKeys.remove(draftKey)
        pendingKeyOwner.remove(draftKey)
    }

    private suspend fun testProvider(providerId: String): ActionResult {
        val m = catalog.entries(providerId).okOr(emptyList()).firstOrNull() ?: return fail("$providerId 没有可测试的模型")
        return probeText(custom.test(m.model.ref))
    }

    private fun probeText(r: Outcome<io.github.zr987459.pigo.contract.ProbeOutcome>): ActionResult = when (r) {
        is Outcome.Ok -> ActionResult.Toast((if (r.value.ok) "✓ " else "✗ ") + r.value.message + " · ${r.value.elapsedMs}ms", failed = !r.value.ok)
        is Outcome.Err -> fail("测试失败：${r.message}")
    }

    private suspend fun logout(providerId: String): ActionResult = when (val r = auth.clear(providerId)) {
        is Outcome.Ok -> { onCredentialsChanged(providerId); ActionResult.Toast("已退出 $providerId") }
        is Outcome.Err -> fail("退出失败：${r.message}")
    }

    // ---------------------------------------------------------------- settings.json

    private fun selectedRef(value: String?): ModelRef? =
        value?.trim()?.removePrefix("model:")?.removePrefix("star:")?.removePrefix("default:")?.takeIf { it.isNotEmpty() }?.let(ModelRef::parse)

    private fun currentRef(): ModelRef? = engine.chat.value.model?.let { ModelRef(it.provider, it.id) }

    private fun scopedRefs(): List<String> =
        (piSettings?.values?.value?.get("enabledModels") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    private suspend fun toggleScoped(ref: String): ActionResult {
        val s = piSettings ?: return fail("常用模型暂不可编辑")
        val cur = scopedRefs()
        val next = if (ref in cur) cur - ref else cur + ref
        return when (val r = s.set("enabledModels", JsonArray(next.map(::JsonPrimitive)))) {
            is Outcome.Ok -> ActionResult.Overlay(modelPicker(modelTab, modelQuery))
            is Outcome.Err -> fail("保存失败：${r.message}")
        }
    }

    /**
     * 「设为默认」= pi 的 Ctrl+S：一次写 defaultProvider、defaultModel、defaultThinkingLevel 三个键。
     * 任何一步失败就把已写的键恢复原值，保证不会出现「provider 已改、model 还是旧的」的半写状态。
     */
    private suspend fun setDefault(ref: ModelRef): ActionResult {
        val s = piSettings ?: return fail("默认模型暂不可编辑")
        val thinking = engine.chat.value.thinkingLevel
        val writes = linkedMapOf<String, JsonElement?>(
            "defaultProvider" to JsonPrimitive(ref.provider),
            "defaultModel" to JsonPrimitive(ref.id),
            "defaultThinkingLevel" to thinking?.let(::JsonPrimitive),
        ).filterValues { it != null }
        return when (val r = setAllOrRollback(s, writes)) {
            is Outcome.Ok -> ActionResult.Toast("已设为默认：$ref" + (thinking?.let { " · $it" } ?: ""))
            is Outcome.Err -> fail("保存失败：${r.message}")
        }
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
        const val OAUTH_VERIFYING = "正在验证…"
        const val OAUTH_VERIFY_TIMEOUT_MS = 60_000L
        const val KEY_DROPPED = "接口地址或名称已变更，之前填的 Key 已清除，请重新填写"
        const val CONFIRM_REMOVE = "confirm.remove:"
        const val CONFIRM_CLEAR = "confirm.clear:"
        const val CONFIRM_LOGOUT = "confirm.logout:"

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

        const val CAP_SELECT = "select"
        const val CAP_DEFAULT = "default"
        const val CAP_STAR = "star"
        const val CAP_REFRESH = "refresh"
        const val CAP_PROVIDERS = "providers"

        /** 页签 key → 枚举：接受枚举名（ALL）和显示标签（全部）；未知值回到 ALL，不抛异常。 */
        fun tabOf(raw: String): ModelPickerTab =
            ModelPickerTab.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: TAB_LABELS.entries.firstOrNull { it.value == raw }?.key
                ?: ModelPickerTab.ALL

        /**
         * 保存自定义服务商时的模型列表：按表单里的 id 顺序，优先用已存的模型（保留推理 / 看图 / 上下文等能力），
         * 其次用「发现模型」得到的，都没有才新建只有 id 的模型（能力未指定，由存储层写成「不写该字段」）。
         */
        /** 服务商表单上「保存」之外的按钮：测试、账号登录（支持 OAuth 时）、清除（已配置时）。 */
        fun providerFormActions(oauth: Boolean, configured: Boolean, hasKeyFields: Boolean): List<KeyCap> = listOfNotNull(
            if (hasKeyFields) KeyCap("", "测试", "test") else null,
            if (oauth) KeyCap("", "账号登录", "oauth") else null,
            if (configured) KeyCap("", "清除", "clear") else null,
        )

        fun mergeModels(ids: List<String>, existing: List<CustomModel>, discovered: List<CustomModel>): List<CustomModel> {
            val old = existing.associateBy { it.id }
            val found = discovered.associateBy { it.id }
            return ids.distinct().map { old[it] ?: found[it] ?: CustomModel(it) }
        }

        /** 依次写入；某一步失败时把已写的键恢复为写之前的值（原来没有的键删除）。 */
        suspend fun setAllOrRollback(s: PiSettings, writes: Map<String, JsonElement?>): Outcome<Unit> {
            val before = s.values.value
            val done = ArrayList<String>()
            for ((k, v) in writes) {
                when (val r = s.set(k, v)) {
                    is Outcome.Ok -> done += k
                    is Outcome.Err -> {
                        done.asReversed().forEach { s.set(it, before[it]) }
                        return r
                    }
                }
            }
            return Outcome.Ok(Unit)
        }

        /** 失败提示：带结构化标志 [ActionResult.Toast.failed]，SessionHost 据此决定是否把表单压回（PR #24 审查 Nit 1）。 */
        private fun fail(text: String) = ActionResult.Toast(text, failed = true)

        private fun <T> Outcome<T>.okOr(default: T): T = (this as? Outcome.Ok)?.value ?: default
    }
}
