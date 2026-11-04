package io.github.zr987459.pigo.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/*
 * W0（feat/modules）新增的契约：模型目录、凭据与 OAuth、自定义端点、网络与费用显示设置。
 * W2（feat/models）实现，docs/feature-gap.md §2.1 M1–M9。定稿后冻结。
 *
 * 凭据规则（DESIGN.md §3.10）：API Key 只放宿主 Keystore 与 pi 进程环境；OAuth 凭据例外，
 * 写入容器内 pi 的 `auth.json`（权限 600，排除在备份、导出、分享之外，日志脱敏）。
 */

/** 一个模型的引用。 */
data class ModelRef(val provider: String, val id: String) {
    override fun toString() = "$provider/$id"

    companion object {
        /** 解析 `provider/id`；id 本身可以含 `/`（如 openrouter）。 */
        fun parse(s: String): ModelRef? {
            val i = s.indexOf('/')
            if (i <= 0 || i == s.length - 1) return null
            return ModelRef(s.substring(0, i), s.substring(i + 1))
        }
    }
}

enum class AuthKind {
    /** 单个环境变量 Key。 */
    API_KEY,

    /** OAuth 设备码（如 GitHub Copilot）。 */
    OAUTH_DEVICE,

    /** OAuth 浏览器授权 + PKCE（如 Anthropic、OpenAI Codex），可能需要粘贴回调 code。 */
    OAUTH_PKCE,

    /** 云平台凭据，多个环境变量（Bedrock、Vertex、Azure、Cloudflare）。 */
    CLOUD_ENV,

    /** 无需凭据（本地端点等）。 */
    NONE,
}

/** 服务商配置表单里的一个字段。 */
data class ProviderField(
    val envVar: String,
    val label: String,
    val secret: Boolean,
    val required: Boolean = true,
    val hint: String? = null,
)

/** pi 内置（或用户自定义）的服务商。 */
data class ProviderInfo(
    val id: String,
    val displayName: String,
    val authKinds: Set<AuthKind>,
    /** 主 Key 环境变量（API_KEY 时）。 */
    val envVar: String? = null,
    /** 需要额外配置的字段（CLOUD_ENV、Azure 的 resource 等）。 */
    val fields: List<ProviderField> = emptyList(),
    val defaultBaseUrl: String? = null,
    /** 中文说明：套餐 Key、是否需要代理等。 */
    val note: String? = null,
    /** 是否为 pi 内置（否则是 `models.json` 自定义）。 */
    val builtIn: Boolean = true,
    /** 国内可直连（用于排序与“可能需要代理”提示）；未知为 null。 */
    val reachableInChina: Boolean? = null,
)

/** 每百万 token 的美元单价（pi 原样）。 */
data class ModelCost(
    val input: Double = 0.0,
    val output: Double = 0.0,
    val cacheRead: Double = 0.0,
    val cacheWrite: Double = 0.0,
)

/** 目录中的一个模型（来自 pi-ai 的内置目录、`get_available_models` 或 `models.json`）。 */
data class CatalogModel(
    val provider: String,
    val id: String,
    val name: String? = null,
    val reasoning: Boolean = false,
    /** `text`、`image` 等。 */
    val input: Set<String> = setOf("text"),
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val cost: ModelCost? = null,
    /** `openai-completions` / `anthropic-messages` 等。 */
    val api: String? = null,
    val baseUrl: String? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    val ref: ModelRef get() = ModelRef(provider, id)
    val supportsImages: Boolean get() = "image" in input
}

enum class ModelAvailability {
    /** 凭据已配置，`get_available_models` 中可见。 */
    READY,

    /** 目录中存在但缺凭据，点击应引导去配置。 */
    NEEDS_AUTH,
}

data class CatalogEntry(val model: CatalogModel, val availability: ModelAvailability)

data class CatalogMeta(
    val piVersion: String,
    val providerCount: Int,
    val modelCount: Int,
    val generatedAtMillis: Long,
)

/** 模型目录 = pi-ai 内置目录 ∪ `get_available_models` ∪ `models.json` 自定义项（M1、M2）。 */
interface ModelCatalog {
    val meta: StateFlow<CatalogMeta?>

    suspend fun providers(): Outcome<List<ProviderInfo>>

    /** [provider] 为 null 时返回全部；按服务商、名称排序。 */
    suspend fun entries(provider: String? = null): Outcome<List<CatalogEntry>>

    /** 按 id、名称、服务商模糊搜索。 */
    suspend fun search(query: String): Outcome<List<CatalogEntry>>

    suspend fun find(ref: ModelRef): CatalogEntry?

    /** 重新从容器导出目录（pi 升级后或用户手动刷新）。 */
    suspend fun refresh(): Outcome<CatalogMeta>

    /** 最近使用（最多 5 个，新的在前）。 */
    suspend fun recent(): List<ModelRef>
    suspend fun markUsed(ref: ModelRef)
}

// ---------------- 凭据与 OAuth（M1、M3） ----------------

sealed interface AuthState {
    data object None : AuthState
    data class ApiKey(val last4: String?) : AuthState
    data class OAuth(val accountLabel: String? = null, val expiresAtMillis: Long? = null) : AuthState
    data class CloudEnv(val configuredVars: Set<String>) : AuthState
    data class Error(val message: String) : AuthState

    val configured: Boolean get() = this is ApiKey || this is OAuth || (this is CloudEnv && configuredVars.isNotEmpty())
}

/** OAuth 登录的一步。界面按步骤展示，用户输入经 [AuthManager.submitCode] / [AuthManager.submitSelection] 回传。 */
sealed interface OAuthStep {
    data object Starting : OAuthStep

    /** 用 Custom Tab 打开授权页；[redirectPrefix] 非空时宿主可拦截该前缀的回调自动提交。 */
    data class OpenUrl(val url: String, val instructions: String? = null, val redirectPrefix: String? = null) : OAuthStep

    /** 设备码：显示 [userCode]，引导打开 [verificationUri]。 */
    data class DeviceCode(val userCode: String, val verificationUri: String, val expiresInSec: Int? = null) : OAuthStep

    /** 需要用户粘贴回调 URL 或授权码。 */
    data class NeedCode(val prompt: String) : OAuthStep

    /** 需要用户选择（如企业域名、账号类型）。 */
    data class Select(val prompt: String, val options: List<String>) : OAuthStep

    data class Progress(val message: String) : OAuthStep
    data class Done(val state: AuthState) : OAuthStep
    data class Failed(val code: ErrCode, val message: String) : OAuthStep
}

interface AuthManager {
    /** 所有服务商的凭据状态（provider id → 状态）。 */
    fun observe(): Flow<Map<String, AuthState>>
    suspend fun state(provider: String): AuthState

    /** 保存 API Key（去空格、去 `Bearer `）以及额外字段（CLOUD_ENV 的 region 等）。保存后需要重启引擎才生效，由调用方决定时机。 */
    suspend fun setApiKey(provider: String, rawKey: String?, extra: Map<String, String> = emptyMap()): Outcome<Unit>

    /** 删除该服务商的 Key 或 OAuth 凭据。 */
    suspend fun clear(provider: String): Outcome<Unit>

    /** 开始 OAuth 登录；冷流，以 [OAuthStep.Done] 或 [OAuthStep.Failed] 结束。取消收集即取消登录。 */
    fun login(provider: String): Flow<OAuthStep>
    suspend fun submitCode(provider: String, code: String)
    suspend fun submitSelection(provider: String, value: String)

    /** `pi auth check --provider <id> --json`。 */
    suspend fun check(provider: String): Outcome<AuthState>
}

// ---------------- 自定义端点（M6、M7） ----------------

/** `models.json` 的 `api` 取值。 */
enum class ProviderApi(val wire: String) {
    OPENAI_COMPLETIONS("openai-completions"),
    OPENAI_RESPONSES("openai-responses"),
    ANTHROPIC_MESSAGES("anthropic-messages"),
    GOOGLE_GENERATIVE_AI("google-generative-ai");

    companion object {
        fun fromWire(s: String?): ProviderApi? = entries.firstOrNull { it.wire == s }
    }
}

data class CustomModel(
    val id: String,
    val name: String? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val reasoning: Boolean = false,
    val input: Set<String> = setOf("text"),
    val cost: ModelCost? = null,
    /**
     * 能力的三态（PR #19 审查）：null = 未指定（写盘时不写该字段，保留文件原值）；true / false = 用户显式开 / 关，照写。
     * 为 null 时实现按 [reasoning] / [input] 推断（只有「开」会写入）。
     */
    val reasoningExplicit: Boolean? = null,
    val imagesExplicit: Boolean? = null,
)

data class CustomProvider(
    val id: String,
    val baseUrl: String,
    val api: ProviderApi = ProviderApi.OPENAI_COMPLETIONS,
    val models: List<CustomModel> = emptyList(),
    /** 额外请求头（不含鉴权）。 */
    val headers: Map<String, String> = emptyMap(),
)

/** 一次“测试连接”的结果；[message] 为中文、已脱敏。 */
data class ProbeOutcome(val ok: Boolean, val message: String, val httpStatus: Int? = null, val elapsedMs: Long = 0)

interface CustomProviderStore {
    suspend fun list(): List<CustomProvider>

    /** 合并写 `models.json`；[rawKey] 为 null 时保留已存 Key。 */
    suspend fun upsert(provider: CustomProvider, rawKey: String?): Outcome<Unit>
    suspend fun remove(id: String): Outcome<Unit>

    /** 上游 `/models`；[rawKey] 为 null 时用已存 Key。 */
    suspend fun discover(baseUrl: String, api: ProviderApi, rawKey: String?, providerId: String? = null): Outcome<List<CustomModel>>

    /** 内置或自定义服务商的连接测试（20 s，不重试）。 */
    suspend fun test(ref: ModelRef): Outcome<ProbeOutcome>
}

// ---------------- 设置：代理、费用显示（M9、决策 3） ----------------

data class NetworkSettings(
    /** 如 `http://127.0.0.1:7890`；null 表示不设代理。注入为 `HTTP_PROXY`/`HTTPS_PROXY`。 */
    val proxyUrl: String? = null,
    val noProxy: String = "localhost,127.0.0.1",
)

/** 费用默认按 pi 给的美元显示；可选同时显示人民币，汇率可配置（默认 7.1）。 */
data class CostDisplaySettings(
    val showCny: Boolean = false,
    val usdToCny: Double = DEFAULT_USD_TO_CNY,
) {
    /** 例：`$0.45` 或 `$0.45（约 ¥3.20）`。 */
    fun format(usd: Double?): String {
        if (usd == null) return "—"
        val base = "$" + fmt(usd)
        return if (showCny) "$base（约 ¥${String.format(java.util.Locale.ROOT, "%.2f", usd * usdToCny)}）" else base
    }

    private fun fmt(v: Double): String =
        if (v != 0.0 && kotlin.math.abs(v) < 0.01) String.format(java.util.Locale.ROOT, "%.4f", v)
        else String.format(java.util.Locale.ROOT, "%.2f", v)

    companion object {
        const val DEFAULT_USD_TO_CNY = 7.1
    }
}

interface AppSettingsStore {
    val network: StateFlow<NetworkSettings>
    val costDisplay: StateFlow<CostDisplaySettings>
    suspend fun setNetwork(value: NetworkSettings)
    suspend fun setCostDisplay(value: CostDisplaySettings)
}
