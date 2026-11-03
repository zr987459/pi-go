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
 *
 * - 内置 Provider：不写 `auth.json`；引擎启动时由 [env] 把 `DEEPSEEK_API_KEY=…` 等放进进程环境。
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
    }

    /** 删除内置 Provider 的 Key（不在接口里，供设置页「清除」使用时由 scaffold 决定是否加进接口）。 */
    suspend fun clearBuiltInKey(provider: String) = secrets.remove(ProviderPresets.builtInAlias(provider))

    override suspend fun upsertCustom(id: String, baseUrl: String, rawKey: String, modelIds: List<String>) {
        val cid = ProviderPresets.normalizeCustomId(id)
        require(cid.isNotEmpty()) { "请给这个服务起一个英文 id，例如 bailian" }
        require(cid != ProviderPresets.OTHER_CUSTOM) { "「custom」是保留名，请换一个 id" }
        require(ProviderPresets.BUILT_IN.none { it.provider == cid }) { "「$cid」和内置服务商重名，请换一个 id" }
        val url = baseUrl.trim().trimEnd('/')
        require(url.startsWith("https://") || url.startsWith("http://")) { "接口地址要以 https:// 开头" }
        val models = modelIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(models.isNotEmpty()) { "请至少填写一个模型 id" }

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
                val old = providers[cid] as? JsonObject ?: JsonObject(emptyMap())
                val oldModels = (old["models"] as? JsonArray).orEmpty()
                    .filterIsInstance<JsonObject>()
                    .associateBy { (it["id"] as? JsonPrimitive)?.content }
                val newModels = JsonArray(
                    models.map { mid -> oldModels[mid] ?: JsonObject(mapOf("id" to JsonPrimitive(mid))) },
                )
                val entry = JsonObject(
                    old + mapOf(
                        "baseUrl" to JsonPrimitive(url),
                        "api" to JsonPrimitive("openai-completions"),
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
            if (secrets.get(ProviderPresets.builtInAlias(p.provider)) != null) out += p.provider
        }
        for ((id, _) in customProviders()) {
            if (secrets.get(ProviderPresets.customAlias(id)) != null) out += id
        }
        return out
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
            val models = (o["models"] as? JsonArray).orEmpty().mapNotNull { m ->
                ((m as? JsonObject)?.get("id") as? JsonPrimitive)?.content
            }
            id to CustomProvider(
                id = id,
                baseUrl = (o["baseUrl"] as? JsonPrimitive)?.content.orEmpty(),
                envVar = apiKey.removePrefix("$"),
                modelIds = models,
            )
        }.toMap()
    }

    data class CustomProvider(val id: String, val baseUrl: String, val envVar: String, val modelIds: List<String>)

    /** [LaunchEnvProvider]：引擎启动 pi 时注入的环境变量（含明文 Key，只进进程环境，不落盘、不打日志）。 */
    override suspend fun env(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (p in ProviderPresets.BUILT_IN) {
            val v = p.envVar ?: continue
            secrets.get(ProviderPresets.builtInAlias(p.provider))?.let { out[v] = it }
        }
        for ((id, c) in customProviders()) {
            secrets.get(ProviderPresets.customAlias(id))?.let { out[c.envVar] = it }
        }
        return out
    }

    /**
     * 诊断信息（可复制导出）：只列出配置了哪些服务、Key 末 4 位与配置文件状态，**从不包含完整 Key**；
     * 末尾再用全部已知 Key 做一次兜底脱敏。
     */
    suspend fun diagnostics(): String {
        val known = env().values
        val sb = StringBuilder()
        sb.appendLine("模型与 Key：")
        for (p in ProviderPresets.BUILT_IN) {
            val k = secrets.get(ProviderPresets.builtInAlias(p.provider)) ?: continue
            sb.appendLine("- ${p.label}（${p.provider}）：${KeyRedaction.mask(k)}")
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
        return KeyRedaction.redact(sb.toString(), known)
    }
}
