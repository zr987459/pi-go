package io.github.zr987459.pigo.core.catalog

import io.github.zr987459.pigo.contract.CustomModel
import io.github.zr987459.pigo.contract.CustomProvider
import io.github.zr987459.pigo.contract.CustomProviderStore
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ModelCost
import io.github.zr987459.pigo.contract.ModelRef
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.ProbeOutcome
import io.github.zr987459.pigo.contract.ProviderApi
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.SecretStore
import io.github.zr987459.pigo.core.store.BuiltinCatalog
import io.github.zr987459.pigo.core.store.ModelDiscovery
import io.github.zr987459.pigo.core.store.PiProviderConfigStore
import io.github.zr987459.pigo.core.store.ProviderPresets
import io.github.zr987459.pigo.core.store.ProviderSpecs
import io.github.zr987459.pigo.core.store.ProviderTester

/** [CustomProviderStore] 的实现（M6、M7）：完整的 provider/model 结构、四种协议、获取模型列表回填详情。 */
class RealCustomProviderStore(
    private val config: PiProviderConfigStore,
    private val secrets: SecretStore,
    private val runtime: RuntimeManager?,
    private val clock: () -> Long = System::currentTimeMillis,
) : CustomProviderStore {

    override suspend fun list(): List<CustomProvider> = config.customProviders().values.map { c ->
        CustomProvider(
            id = c.id,
            baseUrl = c.baseUrl,
            api = ProviderApi.fromWire(c.api.value) ?: ProviderApi.OPENAI_COMPLETIONS,
            models = c.models.map { m ->
                CustomModel(
                    id = m.id,
                    name = m.name,
                    contextWindow = m.contextWindow,
                    maxTokens = m.maxTokens,
                    reasoning = m.reasoning ?: false,
                    input = if (m.supportsImages == true) setOf("text", "image") else setOf("text"),
                    cost = m.cost?.let { ModelCost(it.input, it.output, it.cacheRead, it.cacheWrite) },
                )
            },
            headers = c.headers,
        )
    }

    override suspend fun upsert(provider: CustomProvider, rawKey: String?): Outcome<Unit> = try {
        val old = config.customProviders()[provider.id]?.models.orEmpty().associateBy { it.id }
        config.upsertCustomProvider(
            id = provider.id,
            baseUrl = provider.baseUrl,
            api = PiProviderConfigStore.CustomApi.of(provider.api.wire) ?: PiProviderConfigStore.CustomApi.OPENAI_COMPLETIONS,
            rawKey = rawKey.orEmpty(),
            models = provider.models.map { m ->
                PiProviderConfigStore.CustomModel(
                    id = m.id,
                    name = m.name,
                    reasoning = capability(m.reasoning, old[m.id]?.reasoning),
                    supportsImages = capability("image" in m.input, old[m.id]?.supportsImages),
                    contextWindow = m.contextWindow,
                    maxTokens = m.maxTokens,
                    cost = m.cost?.let { BuiltinCatalog.Cost(it.input, it.output, it.cacheRead, it.cacheWrite) },
                )
            },
            headers = provider.headers.takeIf { it.isNotEmpty() },
        )
        Outcome.Ok(Unit)
    } catch (e: IllegalArgumentException) {
        Outcome.Err(ErrCode.PROVIDER, e.message ?: "保存失败")
    } catch (e: IllegalStateException) {
        Outcome.Err(ErrCode.PROVIDER, e.message ?: "保存失败", e)
    }

    /**
     * 能力字段的「未指定」语义：契约里 `CustomModel.reasoning` / `input` 不能为空，false / 只有 text 既可能是
     * 「明确关闭」也可能是「没填」。原来就有值（true 或 false）时照写新值；原来没写、新值又是 false 时写 null
     * （不写该字段），避免每次保存都把未指定的能力写成关闭（PR #19 审查 Important 4）。
     */
    private fun capability(new: Boolean, old: Boolean?): Boolean? = when {
        new -> true
        old != null -> false
        else -> null
    }

    override suspend fun remove(id: String): Outcome<Unit> = try {
        config.removeCustom(id); Outcome.Ok(Unit)
    } catch (e: IllegalArgumentException) {
        Outcome.Err(ErrCode.PROVIDER, e.message ?: "删除失败")
    }

    /** 「获取模型列表」走 M9 的代理（配了的话）；地址无法解析时直连，由请求本身报错。 */
    private suspend fun proxyFor(baseUrl: String): java.net.Proxy =
        runCatching { io.github.zr987459.pigo.core.store.HostProxy.select(config.proxy(), ModelDiscovery.modelsUrl(baseUrl)) }
            .getOrDefault(java.net.Proxy.NO_PROXY)

    override suspend fun discover(baseUrl: String, api: ProviderApi, rawKey: String?, providerId: String?): Outcome<List<CustomModel>> {
        val key = rawKey?.takeIf { it.isNotBlank() }
            ?: providerId?.let { secrets.get(ProviderPresets.customAlias(ProviderPresets.normalizeCustomId(it))) }
            ?: return Outcome.Err(ErrCode.PROVIDER, "请先填写 API Key")
        return when (val r = ModelDiscovery.discoverDetailed(baseUrl, key, api.wire, proxyFor(baseUrl))) {
            is Outcome.Ok -> Outcome.Ok(
                r.value.map { d ->
                    CustomModel(
                        id = d.id,
                        name = d.name,
                        contextWindow = d.contextWindow,
                        maxTokens = d.maxTokens,
                        reasoning = d.reasoning ?: false,
                        input = if (d.supportsImages == true) setOf("text", "image") else setOf("text"),
                    )
                },
            )
            is Outcome.Err -> r
        }
    }

    override suspend fun test(ref: ModelRef): Outcome<ProbeOutcome> {
        val start = clock()
        val custom = config.customProviders()[ref.provider]
        val r = if (custom != null) {
            if (custom.api != PiProviderConfigStore.CustomApi.OPENAI_COMPLETIONS) {
                return Outcome.Ok(ProbeOutcome(false, "目前只能测试 OpenAI 兼容协议的端点，其他协议请直接发一条消息试试"))
            }
            val key = secrets.get(ProviderPresets.customAlias(custom.id)).orEmpty()
            ProviderTester.testOpenAiCompatible(custom.baseUrl, key, ref.id)
        } else {
            val rt = runtime ?: return Outcome.Err(ErrCode.ENGINE_START, "运行环境未就绪")
            val spec = ProviderSpecs.get(ref.provider) ?: return Outcome.Err(ErrCode.PROVIDER, "不认识的服务商")
            val env = config.env()
            val envVar = if (ProviderSpecs.usesAuthReference(spec)) ProviderSpecs.privateKeyEnv(spec.provider) else spec.keyEnv
            val key = envVar?.let { env[it] }.orEmpty()
            if (envVar == null) return Outcome.Err(ErrCode.PROVIDER, "这个服务商只能登录后直接使用")
            ProviderTester.testBuiltIn(rt, ref.provider, envVar, key, ref.id, extraEnv = env)
        }
        return Outcome.Ok(ProbeOutcome(r.ok, r.message, r.httpStatus, r.elapsedMs.takeIf { it > 0 } ?: (clock() - start)))
    }
}
