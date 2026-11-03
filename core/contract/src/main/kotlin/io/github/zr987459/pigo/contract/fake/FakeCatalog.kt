package io.github.zr987459.pigo.contract.fake

import io.github.zr987459.pigo.contract.AppSettingsStore
import io.github.zr987459.pigo.contract.AuthKind
import io.github.zr987459.pigo.contract.AuthManager
import io.github.zr987459.pigo.contract.AuthState
import io.github.zr987459.pigo.contract.CatalogEntry
import io.github.zr987459.pigo.contract.CatalogMeta
import io.github.zr987459.pigo.contract.CatalogModel
import io.github.zr987459.pigo.contract.CostDisplaySettings
import io.github.zr987459.pigo.contract.CustomModel
import io.github.zr987459.pigo.contract.CustomProvider
import io.github.zr987459.pigo.contract.CustomProviderStore
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ModelAvailability
import io.github.zr987459.pigo.contract.ModelCatalog
import io.github.zr987459.pigo.contract.ModelCost
import io.github.zr987459.pigo.contract.ModelRef
import io.github.zr987459.pigo.contract.NetworkSettings
import io.github.zr987459.pigo.contract.OAuthStep
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.ProbeOutcome
import io.github.zr987459.pigo.contract.ProviderApi
import io.github.zr987459.pigo.contract.ProviderField
import io.github.zr987459.pigo.contract.ProviderInfo
import io.github.zr987459.pigo.contract.sanitizeApiKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/** 假目录：5 个服务商（含 OAuth 与云凭据类），可用性由 [auth] 决定。 */
class FakeModelCatalog(
    private val auth: FakeAuthManager = FakeAuthManager(),
    var providerList: List<ProviderInfo> = FIXTURE_PROVIDERS,
    var models: List<CatalogModel> = FIXTURE_MODELS,
) : ModelCatalog {
    private val _meta = MutableStateFlow<CatalogMeta?>(null)
    override val meta: StateFlow<CatalogMeta?> = _meta.asStateFlow()
    private val recents = ArrayDeque<ModelRef>()

    override suspend fun providers(): Outcome<List<ProviderInfo>> = Outcome.Ok(providerList)

    override suspend fun entries(provider: String?): Outcome<List<CatalogEntry>> =
        Outcome.Ok(models.filter { provider == null || it.provider == provider }
            .sortedWith(compareBy({ it.provider }, { it.name ?: it.id }))
            .map { CatalogEntry(it, availability(it)) })

    override suspend fun search(query: String): Outcome<List<CatalogEntry>> {
        val q = query.trim().lowercase()
        val all = (entries() as Outcome.Ok).value
        if (q.isEmpty()) return Outcome.Ok(all)
        return Outcome.Ok(all.filter { e ->
            listOfNotNull(e.model.id, e.model.name, e.model.provider).any { it.lowercase().contains(q) }
        })
    }

    override suspend fun find(ref: ModelRef): CatalogEntry? =
        models.firstOrNull { it.ref == ref }?.let { CatalogEntry(it, availability(it)) }

    override suspend fun refresh(): Outcome<CatalogMeta> {
        val m = CatalogMeta("1.5.0", providerList.size, models.size, System.currentTimeMillis())
        _meta.value = m
        return Outcome.Ok(m)
    }

    override suspend fun recent(): List<ModelRef> = recents.toList()

    override suspend fun markUsed(ref: ModelRef) {
        recents.remove(ref)
        recents.addFirst(ref)
        while (recents.size > 5) recents.removeLast()
    }

    private suspend fun availability(m: CatalogModel) =
        if (auth.state(m.provider).configured) ModelAvailability.READY else ModelAvailability.NEEDS_AUTH

    companion object {
        val FIXTURE_PROVIDERS = listOf(
            ProviderInfo("deepseek", "DeepSeek", setOf(AuthKind.API_KEY), envVar = "DEEPSEEK_API_KEY", reachableInChina = true),
            ProviderInfo("qwen-token-plan-cn", "通义 Token Plan（中国）", setOf(AuthKind.API_KEY), envVar = "QWEN_TOKEN_PLAN_CN_API_KEY", note = "需要套餐 Key", reachableInChina = true),
            ProviderInfo("anthropic", "Anthropic", setOf(AuthKind.API_KEY, AuthKind.OAUTH_PKCE), envVar = "ANTHROPIC_API_KEY", note = "国内网络可能需要代理", reachableInChina = false),
            ProviderInfo("github-copilot", "GitHub Copilot", setOf(AuthKind.OAUTH_DEVICE), envVar = "COPILOT_GITHUB_TOKEN", reachableInChina = false),
            ProviderInfo(
                "amazon-bedrock", "Amazon Bedrock", setOf(AuthKind.CLOUD_ENV),
                fields = listOf(
                    ProviderField("AWS_ACCESS_KEY_ID", "Access Key ID", secret = true),
                    ProviderField("AWS_SECRET_ACCESS_KEY", "Secret Access Key", secret = true),
                    ProviderField("AWS_REGION", "区域", secret = false, hint = "例如 us-east-1"),
                ),
                reachableInChina = false,
            ),
        )

        val FIXTURE_MODELS = listOf(
            CatalogModel("deepseek", "deepseek-chat", "DeepSeek V3", contextWindow = 128_000, cost = ModelCost(0.27, 1.1)),
            CatalogModel("deepseek", "deepseek-reasoner", "DeepSeek R1", reasoning = true, contextWindow = 128_000),
            CatalogModel("qwen-token-plan-cn", "qwen3-coder-plus", "Qwen3 Coder Plus", contextWindow = 1_000_000),
            CatalogModel("qwen-token-plan-cn", "qwen3-vl-plus", "Qwen3 VL Plus", input = setOf("text", "image"), contextWindow = 256_000),
            CatalogModel("anthropic", "claude-sonnet-4-5", "Claude Sonnet 4.5", reasoning = true, input = setOf("text", "image"), contextWindow = 200_000, cost = ModelCost(3.0, 15.0, 0.3, 3.75)),
            CatalogModel("github-copilot", "gpt-4.1", "GPT-4.1 (Copilot)", input = setOf("text", "image"), contextWindow = 128_000),
            CatalogModel("amazon-bedrock", "anthropic.claude-sonnet-4-5", "Claude Sonnet 4.5 (Bedrock)", reasoning = true, contextWindow = 200_000),
        )
    }
}

/**
 * 假凭据：Key 存内存；OAuth 设备码流程固定给 `ABCD-1234`，Done 状态的账号为 `fake@example.com`；
 * PKCE 流程发出 [OAuthStep.OpenUrl] 与 [OAuthStep.NeedCode]，提交 code `ok` 成功，其他失败。
 */
class FakeAuthManager(initial: Map<String, AuthState> = mapOf("deepseek" to AuthState.ApiKey("1234"))) : AuthManager {
    private val states = MutableStateFlow(initial)
    private val pendingCode = mutableMapOf<String, CompletableDeferred<String>>()

    override fun observe(): Flow<Map<String, AuthState>> = states
    override suspend fun state(provider: String): AuthState = states.value[provider] ?: AuthState.None

    override suspend fun setApiKey(provider: String, rawKey: String?, extra: Map<String, String>): Outcome<Unit> {
        val key = rawKey?.let(::sanitizeApiKey)
        val st = when {
            !key.isNullOrEmpty() -> AuthState.ApiKey(key.takeLast(4))
            extra.isNotEmpty() -> AuthState.CloudEnv(extra.filterValues { it.isNotBlank() }.keys)
            else -> return Outcome.Err(ErrCode.PROVIDER, "Key 不能为空")
        }
        states.update { it + (provider to st) }
        return Outcome.Ok(Unit)
    }

    override suspend fun clear(provider: String): Outcome<Unit> {
        states.update { it - provider }
        return Outcome.Ok(Unit)
    }

    override fun login(provider: String): Flow<OAuthStep> = flow {
        emit(OAuthStep.Starting)
        if (provider == "github-copilot") {
            emit(OAuthStep.DeviceCode("ABCD-1234", "https://github.com/login/device", 900))
            emit(OAuthStep.Progress("等待授权…"))
            val st = AuthState.OAuth("fake@example.com")
            states.update { it + (provider to st) }
            emit(OAuthStep.Done(st))
            return@flow
        }
        val code = CompletableDeferred<String>().also { pendingCode[provider] = it }
        emit(OAuthStep.OpenUrl("https://example.com/oauth/authorize?provider=$provider", "在浏览器里完成授权", "pigo://oauth"))
        emit(OAuthStep.NeedCode("粘贴授权完成后页面上的代码"))
        val c = code.await()
        pendingCode.remove(provider)
        if (c == "ok") {
            val st = AuthState.OAuth("fake@example.com", System.currentTimeMillis() + 3_600_000)
            states.update { it + (provider to st) }
            emit(OAuthStep.Done(st))
        } else {
            emit(OAuthStep.Failed(ErrCode.PROVIDER, "授权码无效"))
        }
    }

    override suspend fun submitCode(provider: String, code: String) {
        pendingCode[provider]?.complete(code.trim())
    }

    override suspend fun submitSelection(provider: String, value: String) {
        pendingCode[provider]?.complete(value)
    }

    override suspend fun check(provider: String): Outcome<AuthState> = Outcome.Ok(state(provider))
}

/** 假自定义端点：发现返回两个模型；测试对 `bad` 服务商返回 401。 */
class FakeCustomProviderStore : CustomProviderStore {
    private val providers = linkedMapOf<String, CustomProvider>()
    private val keys = mutableMapOf<String, String>()

    override suspend fun list(): List<CustomProvider> = providers.values.toList()

    override suspend fun upsert(provider: CustomProvider, rawKey: String?): Outcome<Unit> {
        if (!provider.baseUrl.startsWith("http")) return Outcome.Err(ErrCode.PROVIDER, "接口地址需以 http(s):// 开头")
        providers[provider.id] = provider
        rawKey?.let { keys[provider.id] = sanitizeApiKey(it) }
        return Outcome.Ok(Unit)
    }

    override suspend fun remove(id: String): Outcome<Unit> {
        providers.remove(id); keys.remove(id)
        return Outcome.Ok(Unit)
    }

    override suspend fun discover(baseUrl: String, api: ProviderApi, rawKey: String?, providerId: String?): Outcome<List<CustomModel>> {
        val key = rawKey ?: providerId?.let { keys[it] }
        if (key.isNullOrBlank()) return Outcome.Err(ErrCode.PROVIDER, "缺少 Key")
        return Outcome.Ok(listOf(CustomModel("qwen-plus", contextWindow = 131_072), CustomModel("qwen-max", reasoning = true)))
    }

    override suspend fun test(ref: ModelRef): Outcome<ProbeOutcome> =
        Outcome.Ok(if (ref.provider == "bad") ProbeOutcome(false, "Key 错误", 401, 120) else ProbeOutcome(true, "连接正常", 200, 350))
}

class FakeAppSettingsStore(
    network: NetworkSettings = NetworkSettings(),
    cost: CostDisplaySettings = CostDisplaySettings(),
) : AppSettingsStore {
    private val _network = MutableStateFlow(network)
    private val _cost = MutableStateFlow(cost)
    override val network: StateFlow<NetworkSettings> = _network.asStateFlow()
    override val costDisplay: StateFlow<CostDisplaySettings> = _cost.asStateFlow()
    override suspend fun setNetwork(value: NetworkSettings) { _network.value = value }
    override suspend fun setCostDisplay(value: CostDisplaySettings) {
        require(value.usdToCny > 0) { "汇率必须大于 0" }
        _cost.value = value
    }
}
