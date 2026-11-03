package io.github.zr987459.pigo.core.store

import io.github.zr987459.pigo.contract.LaunchEnvProvider
import io.github.zr987459.pigo.contract.ProviderConfigStore
import io.github.zr987459.pigo.contract.ProviderPreset
import io.github.zr987459.pigo.contract.SecretStore
import io.github.zr987459.pigo.contract.sanitizeApiKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 模型与 Key 的写入（DESIGN.md §3.10）。**Key 只存在宿主的 [SecretStore] 里，从不写进容器文件。**
 * （OAuth 订阅登录的凭据是唯一例外，由 pi 自己写进 auth.json，见 `:core:catalog`。）
 *
 * - 内置 Provider：引擎启动时由 [env] 把 `DEEPSEEK_API_KEY=…` 及多变量服务商的全部字段放进进程环境；
 *   共用 Key 变量的服务商在 `auth.json` 只写变量引用（[syncAuthReferences]），不写 Key。
 * - 自定义 OpenAI 兼容端点：合并写 `/root/.pi/agent/models.json`（权限 600），`apiKey` 写成 `$PIGO_KEY_<ID>`，
 *   其他 provider 条目、同一 provider 的其他字段、已有模型的附加字段都保留。
 * - 默认模型：合并写 `settings.json` 的 `defaultProvider` / `defaultModel` / `defaultThinkingLevel`，不动其他键。
 * - 配置文件不是合法 JSON 时拒绝写入并抛出 [JsonFiles.BrokenConfigException]（中文说明），绝不覆盖用户内容。
 */
class PiProviderConfigStore(
    private val secrets: SecretStore,
    private val paths: GuestPaths,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ProviderConfigStore, LaunchEnvProvider {

    private val mutex = Mutex()

    companion object {
        const val DEFAULT_NO_PROXY = "localhost,127.0.0.1,::1"
        private const val ALIAS_PROXY_URL = "proxy.url"
        private const val ALIAS_NO_PROXY = "proxy.noproxy"
    }

    /** 契约里的 [sanitizeApiKey]，再把只粘了「Bearer」本身的情况视为空。 */
    private fun cleanKey(raw: String): String =
        sanitizeApiKey(raw).let { if (it.equals("Bearer", ignoreCase = true)) "" else it }

    override fun presets(): List<ProviderPreset> = ProviderPresets.ALL

    override suspend fun setBuiltInKey(provider: String, rawKey: String) {
        val preset = ProviderPresets.BUILT_IN.firstOrNull { it.provider == provider }
        requireNotNull(preset) { "不认识的服务商：$provider" }
        val key = cleanKey(rawKey)
        require(key.isNotEmpty()) { "请填写 API Key" }
        require(key.none { it.isWhitespace() }) { "Key 中间不能有空格或换行，请检查是否粘贴完整" }
        secrets.put(ProviderPresets.builtInAlias(provider), key)
        if (ProviderSpecs.usesAuthReference(ProviderSpecs.specFor(provider, preset.label))) syncAuthReferences()
    }

    /** 删除内置 Provider 的 Key（不在接口里，供设置页「清除」使用时由 scaffold 决定是否加进接口）。 */
    suspend fun clearBuiltInKey(provider: String) = clearProvider(provider)

    override suspend fun upsertCustom(id: String, baseUrl: String, rawKey: String, modelIds: List<String>) =
        upsertCustomProvider(
            id = id,
            baseUrl = baseUrl,
            api = CustomApi.OPENAI_COMPLETIONS,
            rawKey = rawKey,
            models = modelIds.map { CustomModel(it) },
        )

    /** 自定义端点支持的四种协议（pi models.json 的 `api`）。 */
    enum class CustomApi(val value: String, val label: String) {
        OPENAI_COMPLETIONS("openai-completions", "OpenAI Chat Completions"),
        OPENAI_RESPONSES("openai-responses", "OpenAI Responses"),
        ANTHROPIC_MESSAGES("anthropic-messages", "Anthropic Messages"),
        GOOGLE_GENERATIVE_AI("google-generative-ai", "Google Gemini"),
        ;

        companion object {
            fun of(value: String?): CustomApi? = entries.firstOrNull { it.value == value }
        }
    }

    /** 自定义模型（M7）。为 null 的字段：新建时不写，编辑时保留 models.json 里原有的值。 */
    data class CustomModel(
        val id: String,
        val name: String? = null,
        val reasoning: Boolean? = null,
        val supportsImages: Boolean? = null,
        val contextWindow: Long? = null,
        val maxTokens: Long? = null,
        /** 美元 / 百万 token：input、output、cacheRead、cacheWrite。 */
        val cost: BuiltinCatalog.Cost? = null,
    )

    /**
     * 新增或修改自定义端点（完整结构，M7）。合并写 `models.json`：
     * - 同一 provider 的其他字段（headers、compat 等用户手写内容）保留；
     * - 模型按 id 合并：已有模型的未知字段保留，给出的字段覆盖，未给出的字段不动；不在 [models] 里的模型删除；
     * - `apiKey` 写成 `$PIGO_KEY_<ID>`，Key 本身只进 Keystore。
     */
    suspend fun upsertCustomProvider(
        id: String,
        baseUrl: String,
        api: CustomApi,
        rawKey: String,
        models: List<CustomModel>,
        /** 额外请求头（不含鉴权）；null 表示不改动已有 headers。 */
        headers: Map<String, String>? = null,
    ) {
        val cid = ProviderPresets.normalizeCustomId(id)
        require(cid.isNotEmpty()) { "请给这个服务起一个英文 id，例如 bailian" }
        require(cid != ProviderPresets.OTHER_CUSTOM) { "「custom」是保留名，请换一个 id" }
        require(ProviderPresets.BUILT_IN.none { it.provider == cid } && ProviderSpecs.get(cid) == null) { "「$cid」和内置服务商重名，请换一个 id" }
        val url = baseUrl.trim().trimEnd('/')
        require(url.startsWith("https://") || url.startsWith("http://")) { "接口地址要以 https:// 开头" }
        val cleanModels = models.map { it.copy(id = it.id.trim()) }.filter { it.id.isNotEmpty() }.distinctBy { it.id }
        require(cleanModels.isNotEmpty()) { "请至少填写一个模型 id" }
        for (m in cleanModels) {
            m.contextWindow?.let { require(it > 0) { "「${m.id}」的上下文窗口要大于 0" } }
            m.maxTokens?.let { require(it > 0) { "「${m.id}」的最大输出要大于 0" } }
            m.cost?.let { c -> require(listOf(c.input, c.output, c.cacheRead, c.cacheWrite).all { it >= 0 }) { "「${m.id}」的单价不能为负数" } }
        }

        val key = cleanKey(rawKey)
        val alias = ProviderPresets.customAlias(cid)
        if (key.isEmpty()) {
            requireNotNull(secrets.get(alias)) { "请填写 API Key" } // 编辑已有配置时留空 = 保持原 Key
        } else {
            require(key.none { it.isWhitespace() }) { "Key 中间不能有空格或换行，请检查是否粘贴完整" }
        }

        val envVar = ProviderPresets.customEnvVar(cid)
        mutex.withLock {
            withContext(io) {
                val file = paths.modelsJson
                val root = JsonFiles.readObject(file)
                val providers = root["providers"] as? JsonObject ?: JsonObject(emptyMap())
                // 不同 id 可能规范化成同一个环境变量（如 my-api 与 my_api 都是 PIGO_KEY_MY_API），
                // 两个端点会互相顶掉 Key，必须拒绝。
                val clash = conflictingId(providers, cid, envVar)
                require(clash == null) {
                    "「$cid」和已添加的「$clash」会用同一个环境变量 $envVar，请换一个 id，或直接修改「$clash」"
                }
                val old = providers[cid] as? JsonObject ?: JsonObject(emptyMap())
                val oldModels = (old["models"] as? JsonArray).orEmpty()
                    .filterIsInstance<JsonObject>()
                    .associateBy { (it["id"] as? JsonPrimitive)?.content }
                val newModels = JsonArray(cleanModels.map { m -> mergeModel(oldModels[m.id], m) })
                val headerPatch: Map<String, JsonElement> = if (headers == null) emptyMap() else {
                    val bad = headers.keys.firstOrNull { it.equals("authorization", true) || it.equals("x-api-key", true) }
                    require(bad == null) { "鉴权请填在 API Key 里，不要写在请求头 $bad 中" }
                    mapOf("headers" to JsonObject(headers.mapValues { JsonPrimitive(it.value) }))
                }
                val entry = JsonObject(
                    old + headerPatch + mapOf(
                        "baseUrl" to JsonPrimitive(url),
                        "api" to JsonPrimitive(api.value),
                        "apiKey" to JsonPrimitive("$$envVar"),
                        "models" to newModels,
                    ),
                )
                val newRoot = JsonObject(root + ("providers" to JsonObject(providers + (cid to entry))))
                JsonFiles.writeObject(file, newRoot)
            }
            // 文件写成功后再存 Key，保证不会出现「Key 存了但 models.json 没有」时被当成已配置。
            if (key.isNotEmpty()) secrets.put(alias, key)
        }
    }

    internal fun mergeModel(old: JsonObject?, m: CustomModel): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(old ?: emptyMap())
        out["id"] = JsonPrimitive(m.id)
        m.name?.trim()?.takeIf { it.isNotEmpty() }?.let { out["name"] = JsonPrimitive(it) }
        m.reasoning?.let { out["reasoning"] = JsonPrimitive(it) }
        m.supportsImages?.let { img ->
            out["input"] = JsonArray(if (img) listOf(JsonPrimitive("text"), JsonPrimitive("image")) else listOf(JsonPrimitive("text")))
        }
        m.contextWindow?.let { out["contextWindow"] = JsonPrimitive(it) }
        m.maxTokens?.let { out["maxTokens"] = JsonPrimitive(it) }
        m.cost?.let { c ->
            val oldCost = old?.get("cost") as? JsonObject ?: JsonObject(emptyMap())
            out["cost"] = JsonObject(
                oldCost + mapOf(
                    "input" to JsonPrimitive(c.input),
                    "output" to JsonPrimitive(c.output),
                    "cacheRead" to JsonPrimitive(c.cacheRead),
                    "cacheWrite" to JsonPrimitive(c.cacheWrite),
                ),
            )
        }
        return JsonObject(out)
    }

    /** 删除一个 pi-go 写的自定义端点（models.json 条目与 Key）。不是 pi-go 写的条目拒绝删除。 */
    suspend fun removeCustom(id: String) {
        val cid = ProviderPresets.normalizeCustomId(id)
        mutex.withLock {
            withContext(io) {
                val file = paths.modelsJson
                val root = JsonFiles.readObject(file)
                val providers = root["providers"] as? JsonObject ?: return@withContext
                val entry = providers[cid] as? JsonObject ?: return@withContext
                val apiKey = (entry["apiKey"] as? JsonPrimitive)?.content.orEmpty()
                require(apiKey.startsWith("\$PIGO_KEY_")) { "「$cid」不是在 pi-go 里添加的，请手动编辑 models.json" }
                JsonFiles.writeObject(file, JsonObject(root + ("providers" to JsonObject(providers - cid))))
            }
            secrets.remove(ProviderPresets.customAlias(cid))
        }
    }

    /** models.json 里除 [cid] 外，`apiKey` 也是 `$envVar` 的 provider id；没有则为 null。 */
    internal fun conflictingId(providers: JsonObject, cid: String, envVar: String): String? =
        providers.entries.firstOrNull { (id, v) ->
            id != cid && ((v as? JsonObject)?.get("apiKey") as? JsonPrimitive)?.content == "$$envVar"
        }?.key

    override suspend fun setDefault(provider: String, modelId: String, thinking: String?) {
        require(provider.isNotBlank() && modelId.isNotBlank()) { "请选择模型" }
        mutex.withLock {
            withContext(io) {
                val file = paths.settingsJson
                val root = JsonFiles.readObject(file)
                val patch = buildMap<String, JsonElement> {
                    put("defaultProvider", JsonPrimitive(provider))
                    put("defaultModel", JsonPrimitive(modelId))
                    if (thinking != null) put("defaultThinkingLevel", JsonPrimitive(thinking))
                }
                JsonFiles.writeObject(file, JsonObject(root + patch))
            }
        }
    }

    override suspend fun configured(): List<String> {
        val out = ArrayList<String>()
        for (p in ProviderPresets.BUILT_IN) {
            if (isBuiltInConfigured(p.provider)) out += p.provider
        }
        for ((id, _) in customProviders()) {
            if (secrets.get(ProviderPresets.customAlias(id)) != null) out += id
        }
        return out
    }

    // ---------------------------------------------------------------- 多字段服务商（W2 / M1）

    /** 额外字段的 SecretStore 别名。 */
    internal fun fieldAlias(provider: String, envVar: String) = "field.$provider.$envVar"

    private suspend fun storedValue(spec: ProviderSpecs.Spec, envVar: String): String? =
        if (envVar == spec.keyEnv) secrets.get(ProviderPresets.builtInAlias(spec.provider))
        else secrets.get(fieldAlias(spec.provider, envVar))

    /** 已保存的全部字段（明文，仅供注入环境与校验，不得显示或打日志）。 */
    private suspend fun storedValues(spec: ProviderSpecs.Spec): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (f in spec.allFields) storedValue(spec, f.envVar)?.takeIf { it.isNotEmpty() }?.let { out[f.envVar] = it }
        return out
    }

    /** 主 Key（或 Bedrock 这类「Key 可选」服务商的替代组合）与全部必填字段都已保存。 */
    suspend fun isBuiltInConfigured(provider: String): Boolean {
        val spec = ProviderSpecs.get(provider) ?: ProviderPresets.BUILT_IN.firstOrNull { it.provider == provider }
            ?.let { ProviderSpecs.specFor(it.provider, it.label) } ?: return false
        if (spec.keyEnv == null) return false
        val v = storedValues(spec)
        return missingFields(spec, v) == null
    }

    /** 校验一组字段值，返回中文错误说明；全部合格返回 null。 */
    internal fun missingFields(spec: ProviderSpecs.Spec, v: Map<String, String>): String? {
        for (f in spec.allFields) {
            if (f.required && v[f.envVar].isNullOrBlank()) return "请填写「${f.label}」"
        }
        return spec.validate?.invoke(v)
    }

    /** 设置页预填：非密文字段给明文，密文字段只给 `••••末4位`（未保存为 null）。 */
    suspend fun providerFields(provider: String): Map<String, String?> {
        val spec = ProviderSpecs.specFor(provider)
        return spec.allFields.associate { f ->
            val v = storedValue(spec, f.envVar)
            f.envVar to if (v == null) null else if (f.secret) KeyRedaction.mask(v) else v
        }
    }

    /**
     * 一次保存一个服务商的全部字段（[values] 以环境变量名为键）。密文字段留空 = 保持原值；非密文字段留空 = 清除。
     * 校验失败抛 [IllegalArgumentException]（中文说明），任何字段都不会被写入。
     */
    suspend fun setProviderFields(provider: String, values: Map<String, String>) {
        val spec = ProviderSpecs.get(provider)
            ?: ProviderPresets.BUILT_IN.firstOrNull { it.provider == provider }?.let { ProviderSpecs.specFor(it.provider, it.label) }
        requireNotNull(spec) { "不认识的服务商：$provider" }
        requireNotNull(spec.keyEnv) { "${spec.label} 只能用账号登录，不能填 Key" }
        val old = storedValues(spec)
        val merged = LinkedHashMap<String, String>()
        for (f in spec.allFields) {
            val raw = values[f.envVar]
            val cleaned = when {
                raw == null -> old[f.envVar]
                f.secret -> cleanKey(raw).ifEmpty { old[f.envVar] }
                else -> raw.trim().ifEmpty { null }
            }
            if (cleaned != null) {
                if (f.secret) require(cleaned.none { it.isWhitespace() }) { "「${f.label}」中间不能有空格或换行，请检查是否粘贴完整" }
                merged[f.envVar] = cleaned
            }
        }
        missingFields(spec, merged)?.let { throw IllegalArgumentException(it) }
        mutex.withLock {
            for (f in spec.allFields) {
                val alias = if (f.envVar == spec.keyEnv) ProviderPresets.builtInAlias(provider) else fieldAlias(provider, f.envVar)
                val v = merged[f.envVar]
                if (v == null) secrets.remove(alias) else secrets.put(alias, v)
            }
        }
        syncAuthReferences()
    }

    /** 清除一个内置服务商的全部字段（OAuth 凭据不在这里，见 `:core:catalog` 的登出）。 */
    suspend fun clearProvider(provider: String) {
        val spec = ProviderSpecs.specFor(provider)
        mutex.withLock {
            secrets.remove(ProviderPresets.builtInAlias(provider))
            for (f in spec.extra) secrets.remove(fieldAlias(provider, f.envVar))
        }
        syncAuthReferences()
    }

    // ---------------------------------------------------------------- 共用环境变量的服务商：auth.json 只写变量引用

    /**
     * 让共用同一个 Key 变量的服务商（[ProviderSpecs.sharedKeyEnv]）各用各的 Key：在 `auth.json` 写
     * `{"type":"api_key","key":"$PIGO_PK_<ID>","env":{非密文字段}}`。**auth.json 里只有变量名，没有 Key**；
     * 只增删 pi-go 自己写的这类条目（key 以 `$PIGO_PK_` 开头），OAuth 凭据与用户自己写的条目一律不动。
     * 写入时按 pi 的锁约定（`auth.json.lock` 目录）加锁，权限 600。
     */
    suspend fun syncAuthReferences() {
        val wanted = LinkedHashMap<String, JsonObject>()
        for (p in ProviderPresets.BUILT_IN) {
            val spec = ProviderSpecs.specFor(p.provider, p.label)
            if (!ProviderSpecs.usesAuthReference(spec) || !isBuiltInConfigured(p.provider)) continue
            val v = storedValues(spec)
            val env = spec.extra.filter { !it.secret }.mapNotNull { f -> v[f.envVar]?.let { f.envVar to JsonPrimitive(it) } }.toMap()
            wanted[p.provider] = JsonObject(
                buildMap {
                    put("type", JsonPrimitive("api_key"))
                    put("key", JsonPrimitive("$" + ProviderSpecs.privateKeyEnv(p.provider)))
                    if (env.isNotEmpty()) put("env", JsonObject(env))
                },
            )
        }
        mutex.withLock {
            withContext(io) {
                val file = paths.authJson
                if (!file.exists() && wanted.isEmpty()) return@withContext
                AuthJsonLock.withLock(file) {
                    val root = JsonFiles.readObject(file)
                    val kept = root.filterNot { (_, v) -> isOurReference(v) }
                    val next = JsonObject(kept + wanted)
                    if (next != root) JsonFiles.writeObject(file, next)
                }
            }
        }
    }

    private fun isOurReference(v: JsonElement): Boolean {
        val o = v as? JsonObject ?: return false
        return (o["type"] as? JsonPrimitive)?.content == "api_key" &&
            (o["key"] as? JsonPrimitive)?.content?.startsWith("\$PIGO_PK_") == true
    }

    // ---------------------------------------------------------------- 代理（W2 / M9）

    data class ProxySettings(val url: String, val noProxy: String = DEFAULT_NO_PROXY)

    suspend fun proxy(): ProxySettings? {
        val url = secrets.get(ALIAS_PROXY_URL)?.takeIf { it.isNotBlank() } ?: return null
        return ProxySettings(url, secrets.get(ALIAS_NO_PROXY)?.takeIf { it.isNotBlank() } ?: DEFAULT_NO_PROXY)
    }

    /** 保存代理；[url] 为空表示关闭。地址可带用户名密码（`http://user:pass@host:port`），所以也存 Keystore。 */
    suspend fun setProxy(url: String?, noProxy: String? = null) {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) {
            secrets.remove(ALIAS_PROXY_URL); secrets.remove(ALIAS_NO_PROXY); return
        }
        val uri = try { java.net.URI(u) } catch (e: Exception) { throw IllegalArgumentException("代理地址格式不对，例如 http://127.0.0.1:7890") }
        require(uri.scheme == "http" || uri.scheme == "https") { "代理地址要以 http:// 或 https:// 开头（pi 不支持 socks）" }
        require(!uri.host.isNullOrBlank() && uri.port > 0) { "代理地址要包含主机和端口，例如 http://127.0.0.1:7890" }
        secrets.put(ALIAS_PROXY_URL, u)
        val np = noProxy?.trim().orEmpty()
        if (np.isEmpty()) secrets.remove(ALIAS_NO_PROXY) else secrets.put(ALIAS_NO_PROXY, np)
    }

    /** 已配置的自定义端点：id → 详情（读 models.json；文件损坏时视为没有）。 */
    suspend fun customProviders(): Map<String, CustomProvider> = withContext(io) {
        val root = try {
            JsonFiles.readObject(paths.modelsJson)
        } catch (_: JsonFiles.BrokenConfigException) {
            return@withContext emptyMap()
        }
        val providers = root["providers"] as? JsonObject ?: return@withContext emptyMap()
        providers.mapNotNull { (id, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val apiKey = (o["apiKey"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            if (!apiKey.startsWith("\$PIGO_KEY_")) return@mapNotNull null // 不是 pi-go 写的条目，不接管
            val models = (o["models"] as? JsonArray).orEmpty().mapNotNull { m -> parseCustomModel(m as? JsonObject) }
            id to CustomProvider(
                id = id,
                baseUrl = (o["baseUrl"] as? JsonPrimitive)?.content.orEmpty(),
                envVar = apiKey.removePrefix("$"),
                modelIds = models.map { it.id },
                api = CustomApi.of((o["api"] as? JsonPrimitive)?.content) ?: CustomApi.OPENAI_COMPLETIONS,
                models = models,
                headers = (o["headers"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }?.toMap().orEmpty(),
            )
        }.toMap()
    }

    data class CustomProvider(
        val id: String,
        val baseUrl: String,
        val envVar: String,
        val modelIds: List<String>,
        val api: CustomApi = CustomApi.OPENAI_COMPLETIONS,
        val models: List<CustomModel> = modelIds.map { CustomModel(it) },
        val headers: Map<String, String> = emptyMap(),
    )

    private fun parseCustomModel(o: JsonObject?): CustomModel? {
        o ?: return null
        val id = (o["id"] as? JsonPrimitive)?.content ?: return null
        fun p(k: String) = o[k] as? JsonPrimitive
        val c = o["cost"] as? JsonObject
        fun cp(k: String) = (c?.get(k) as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
        return CustomModel(
            id = id,
            name = p("name")?.content,
            reasoning = p("reasoning")?.content?.toBooleanStrictOrNull(),
            supportsImages = (o["input"] as? JsonArray)?.any { (it as? JsonPrimitive)?.content == "image" },
            contextWindow = p("contextWindow")?.content?.toDoubleOrNull()?.toLong(),
            maxTokens = p("maxTokens")?.content?.toDoubleOrNull()?.toLong(),
            cost = c?.let { BuiltinCatalog.Cost(cp("input"), cp("output"), cp("cacheRead"), cp("cacheWrite")) },
        )
    }

    /**
     * [LaunchEnvProvider]：引擎启动 pi 时注入的环境变量（含明文 Key，只进进程环境，不落盘、不打日志）。
     *
     * - 每个已配置的内置服务商：主 Key 与全部额外字段一次注入（Bedrock 的 AK/SK/区域、Azure 的地址与版本等）；
     * - 共用 Key 变量的服务商注入私有变量 `PIGO_PK_<ID>`（auth.json 里引用它），不注入共用变量，避免互相覆盖；
     * - 自定义端点：`PIGO_KEY_<ID>`；
     * - 代理：`HTTP(S)_PROXY`/`NO_PROXY`（大小写两套）与 `NODE_USE_ENV_PROXY=1`（让 Node 自带 fetch 也走代理）。
     */
    override suspend fun env(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (p in ProviderPresets.BUILT_IN) {
            val spec = ProviderSpecs.specFor(p.provider, p.label)
            val v = storedValues(spec)
            if (missingFields(spec, v) != null) continue
            if (ProviderSpecs.usesAuthReference(spec)) {
                v[spec.keyEnv]?.let { out[ProviderSpecs.privateKeyEnv(p.provider)] = it }
                // 非密文字段在 auth.json 的 env 里；密文额外字段（目前共用组里没有）仍按原名注入
                for (f in spec.extra) if (f.secret) v[f.envVar]?.let { out[f.envVar] = it }
            } else {
                for ((k, value) in v) out[k] = value
            }
        }
        for ((id, c) in customProviders()) {
            secrets.get(ProviderPresets.customAlias(id))?.let { out[c.envVar] = it }
        }
        proxy()?.let { px ->
            out["HTTPS_PROXY"] = px.url; out["HTTP_PROXY"] = px.url
            out["https_proxy"] = px.url; out["http_proxy"] = px.url
            out["NO_PROXY"] = px.noProxy; out["no_proxy"] = px.noProxy
            out["NODE_USE_ENV_PROXY"] = "1"
        }
        return out
    }

    /**
     * 诊断信息（可复制导出）：只列出配置了哪些服务、Key 末 4 位与配置文件状态，**从不包含完整 Key**；
     * 末尾再用全部已知 Key 做一次兜底脱敏。
     */
    suspend fun diagnostics(): String {
        val known = env().filterKeys { it !in ProviderSpecs.PROXY_ENVS && it != "NODE_USE_ENV_PROXY" }.values
        val sb = StringBuilder()
        sb.appendLine("模型与 Key：")
        for (p in ProviderPresets.BUILT_IN) {
            val k = secrets.get(ProviderPresets.builtInAlias(p.provider)) ?: continue
            val extra = ProviderSpecs.specFor(p.provider, p.label).extra.mapNotNull { f ->
                secrets.get(fieldAlias(p.provider, f.envVar))?.let { "${f.label} ${if (f.secret) KeyRedaction.mask(it) else "已填"}" }
            }
            sb.appendLine("- ${p.label}（${p.provider}）：${KeyRedaction.mask(k)}" + if (extra.isEmpty()) "" else "，" + extra.joinToString("，"))
        }
        for ((id, c) in customProviders()) {
            val k = secrets.get(ProviderPresets.customAlias(id))
            sb.appendLine("- 自定义 $id：${c.baseUrl}，模型 ${c.modelIds.joinToString()}，Key ${KeyRedaction.mask(k)}")
        }
        val modelsState = withContext(io) {
            val f = paths.modelsJson
            when {
                !f.exists() -> "不存在"
                else -> try {
                    JsonFiles.readObject(f); "正常"
                } catch (_: JsonFiles.BrokenConfigException) {
                    "格式有误"
                }
            }
        }
        sb.appendLine("models.json：$modelsState")
        proxy()?.let { sb.appendLine("代理：已设置（${KeyRedaction.redactUrlCredentials(it.url)}）") }
        return KeyRedaction.redact(sb.toString(), known)
    }
}
