package io.github.zr987459.pigo.core.catalog

import io.github.zr987459.pigo.contract.AuthKind
import io.github.zr987459.pigo.contract.CatalogEntry
import io.github.zr987459.pigo.contract.CatalogMeta
import io.github.zr987459.pigo.contract.CatalogModel
import io.github.zr987459.pigo.contract.ModelAvailability
import io.github.zr987459.pigo.contract.ModelCatalog
import io.github.zr987459.pigo.contract.ModelCost
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.ModelRef
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.ProviderField
import io.github.zr987459.pigo.contract.ProviderInfo
import io.github.zr987459.pigo.core.store.BuiltinCatalog
import io.github.zr987459.pigo.core.store.ModelPrefsStore
import io.github.zr987459.pigo.core.store.PiProviderConfigStore
import io.github.zr987459.pigo.core.store.ProviderSpecs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ModelCatalog] 的实现（M1、M2）：目录（[CatalogRepository]）∪ `get_available_models`（[runtimeModels]）∪ models.json 自定义端点。
 * 可用性：引擎在跑时以 `get_available_models` 为准；引擎没起来时按「宿主有 Key 或 auth.json 有 OAuth」判断。
 */
class RealModelCatalog(
    private val repo: CatalogRepository,
    private val config: PiProviderConfigStore,
    private val auth: AuthService,
    private val prefs: ModelPrefsStore,
    /** `EngineClient.models()`；引擎没起来或失败返回 null。 */
    private val runtimeModels: suspend () -> List<ModelInfo>?,
    private val clock: () -> Long = System::currentTimeMillis,
) : ModelCatalog {

    private val _meta = MutableStateFlow<CatalogMeta?>(metaOf(repo.snapshot.value))
    override val meta: StateFlow<CatalogMeta?> = _meta.asStateFlow()

    private fun metaOf(s: BuiltinCatalog.Snapshot) = CatalogMeta(s.piVersion, s.providers.size, s.modelCount, clock())

    /** 选择页用的分组数据（比契约多带「服务商可用」与登录方式），供 `feature/models` 之外的调用方使用。 */
    suspend fun groups(): List<ModelIndex.Group> {
        val custom = config.customProviders()
        return ModelIndex.build(
            ModelIndex.Inputs(
                catalog = repo.snapshot.value,
                runtimeAvailable = runCatching { runtimeModels() }.getOrNull(),
                custom = custom,
                keyConfigured = config.configured().toSet(),
                oauthLoggedIn = auth.refreshOAuthStatus(),
            ),
        )
    }

    override suspend fun providers(): Outcome<List<ProviderInfo>> {
        val snap = repo.snapshot.value
        val builtIn = snap.providers.map { p ->
            val spec = ProviderSpecs.specFor(p.id, p.name, p.apiKeyAuth)
            ProviderInfo(
                id = p.id,
                displayName = spec.label,
                authKinds = auth.kindsOf(p).map { it.toContract() }.toSet(),
                envVar = spec.keyEnv,
                fields = spec.extra.map { ProviderField(it.envVar, it.label, it.secret, it.required, it.hint.ifEmpty { it.placeholder }.ifEmpty { null }) },
                defaultBaseUrl = p.baseUrl,
                note = spec.note.ifEmpty { null },
                builtIn = true,
                reachableInChina = reachableInChina(p.id),
            )
        }
        val custom = config.customProviders().values.map { c ->
            ProviderInfo(c.id, "自定义：${c.id}", setOf(AuthKind.API_KEY), envVar = c.envVar, defaultBaseUrl = c.baseUrl, builtIn = false)
        }
        return Outcome.Ok(builtIn + custom)
    }

    override suspend fun entries(provider: String?): Outcome<List<CatalogEntry>> {
        val gs = groups()
        return Outcome.Ok(gs.filter { provider == null || it.provider == provider }.flatMap { g -> g.models.map { it.toContract() } })
    }

    override suspend fun search(query: String): Outcome<List<CatalogEntry>> =
        Outcome.Ok(ModelIndex.search(groups(), query).flatMap { g -> g.models.map { it.toContract() } })

    override suspend fun find(ref: ModelRef): CatalogEntry? =
        groups().firstOrNull { it.provider == ref.provider }?.models?.firstOrNull { it.id == ref.id }?.toContract()

    override suspend fun refresh(): Outcome<CatalogMeta> = when (val r = repo.refresh(force = true)) {
        is Outcome.Ok -> Outcome.Ok(metaOf(r.value).also { _meta.value = it })
        is Outcome.Err -> r
    }

    override suspend fun recent(): List<ModelRef> = prefs.prefs.value.recent.mapNotNull { ModelRef.parse(it) }

    override suspend fun markUsed(ref: ModelRef) = prefs.touchRecent(ref.provider, ref.id)

    private fun ModelIndex.Entry.toContract() = CatalogEntry(
        CatalogModel(
            provider = provider,
            id = id,
            name = name,
            reasoning = reasoning,
            input = if (supportsImages) setOf("text", "image") else setOf("text"),
            contextWindow = contextWindow,
            maxTokens = maxTokens,
            cost = cost?.let { ModelCost(it.input, it.output, it.cacheRead, it.cacheWrite) },
        ),
        if (available) ModelAvailability.READY else ModelAvailability.NEEDS_AUTH,
    )

    companion object {
        private val CHINA = setOf(
            "deepseek", "moonshotai-cn", "minimax-cn", "zai-coding-cn", "qwen-token-plan-cn", "kimi-coding",
            "xiaomi", "xiaomi-token-plan-cn", "ant-ling",
        )

        fun reachableInChina(provider: String): Boolean? = if (provider in CHINA) true else null
    }
}

internal fun AuthService.Kind.toContract(): AuthKind = when (this) {
    AuthService.Kind.API_KEY -> AuthKind.API_KEY
    AuthService.Kind.OAUTH_DEVICE -> AuthKind.OAUTH_DEVICE
    AuthService.Kind.OAUTH_PKCE -> AuthKind.OAUTH_PKCE
    AuthService.Kind.CLOUD_ENV -> AuthKind.CLOUD_ENV
}
