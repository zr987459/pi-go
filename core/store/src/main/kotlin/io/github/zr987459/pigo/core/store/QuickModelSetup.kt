package io.github.zr987459.pigo.core.store

import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.sanitizeApiKey

/**
 * 设置 › 模型 的极简配置（r4/model-config）：选服务商 → 填 Key → 选模型 → 测试连接 → 保存并使用。
 *
 * 流程参照 agegr/pi-web@025e24e 的模型配置（MIT）：服务商 + Key、上游 `/models` 自动拉列表
 * （`lib/model-discovery.ts`、`app/api/models-config/discover/route.ts`）、一条「Reply with OK only.」测试连接
 * （`app/api/models-config/test/route.ts`）。不同点：Key 仍只进 Keystore + 进程环境（DESIGN.md §3.10），
 * 所以「保存」之后**重启 pi 进程**（保留当前会话）让新 Key 进环境、重读 models.json，再对当前会话发 `set_model`。
 *
 * @param catalogModels pi 内置服务商的模型 id（来自 pi 的模型目录）
 * @param restartEngine 重启 pi（续接当前会话）；新进程启动时由 [PiProviderConfigStore.env] 注入 Key
 * @param setModel 当前会话切到该模型（RPC `set_model`）
 */
class QuickModelSetup(
    private val config: PiProviderConfigStore,
    private val catalogModels: suspend (provider: String) -> List<String>,
    private val restartEngine: suspend (reason: String) -> Outcome<Unit>,
    private val setModel: suspend (provider: String, modelId: String) -> Outcome<*>,
    private val discover: suspend (baseUrl: String, key: String) -> Outcome<List<String>> = ModelDiscovery::discover,
    private val probe: suspend (baseUrl: String, key: String, model: String) -> ProbeResult = { b, k, m -> ProviderTester.testOpenAiCompatible(b, k, m) },
) {
    /**
     * 一个常用服务商。[builtIn] 为 true 时用 pi 内置服务商（[id] 是 pi 的 provider id，Key 经它的环境变量注入）；
     * 否则写成 models.json 里的 OpenAI 兼容端点（[id] 即 provider id）。[baseUrl] 用于拉模型列表与测试连接。
     */
    data class Preset(val id: String, val label: String, val baseUrl: String, val builtIn: Boolean, val keyUrl: String = "", val hint: String = "")

    companion object {
        const val CUSTOM = "custom-openai"

        /** 国内小白常用的几家（其余服务商、账号登录、代理在「高级」里）。 */
        val PRESETS: List<Preset> = listOf(
            Preset("deepseek", "DeepSeek", "https://api.deepseek.com", builtIn = true, keyUrl = "https://platform.deepseek.com/api_keys"),
            Preset("bailian", "通义千问（阿里云百炼）", "https://dashscope.aliyuncs.com/compatible-mode/v1", builtIn = false, keyUrl = "https://bailian.console.aliyun.com/?apiKey=1"),
            Preset("moonshotai-cn", "Kimi（月之暗面）", "https://api.moonshot.cn/v1", builtIn = true, keyUrl = "https://platform.moonshot.cn/console/api-keys"),
            Preset("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", builtIn = false, keyUrl = "https://open.bigmodel.cn/usercenter/apikeys"),
            Preset("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", builtIn = false, keyUrl = "https://cloud.siliconflow.cn/account/ak"),
            Preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", builtIn = true, keyUrl = "https://openrouter.ai/keys", hint = "国内网络可能需要代理（在「高级」里设置）"),
            Preset(CUSTOM, "其他（OpenAI 兼容）", "", builtIn = false, hint = "填服务商给的接口地址（Base URL）和 Key"),
        )

        fun preset(id: String): Preset? = PRESETS.firstOrNull { it.id == id }

        /** 自定义端点的 provider id：取接口地址的主机名（`api.example.com` → `example`）。 */
        fun customIdFor(baseUrl: String): String {
            val host = runCatching { java.net.URI(baseUrl.trim()).host }.getOrNull().orEmpty().lowercase()
            val parts = host.split('.').filter { it.isNotEmpty() && it != "www" && it != "api" }
            val core = when {
                parts.size >= 2 -> parts[parts.size - 2]
                parts.size == 1 -> parts[0]
                else -> "custom"
            }
            val id = ProviderPresets.normalizeCustomId(core).ifEmpty { "custom" }
            val taken = id == ProviderPresets.OTHER_CUSTOM || ProviderSpecs.get(id) != null || ProviderPresets.BUILT_IN.any { it.provider == id }
            return if (taken) "$id-api" else id
        }
    }

    /** 测试连接 / 拉模型列表的结果分类。 */
    enum class Verdict { OK, BAD_KEY, NETWORK, MODEL_NOT_FOUND, QUOTA, BAD_URL, OTHER }

    data class Check(val verdict: Verdict, val message: String)

    /** HTTP 状态 + 返回内容 → 分类与中文提示（[detail] 已脱敏）。 */
    fun classify(r: ProbeResult, model: String): Check {
        if (r.ok) return Check(Verdict.OK, "连接成功，可以用 $model 聊天了（${r.elapsedMs} ms）")
        val code = r.httpStatus
        val text = r.message.lowercase()
        val modelMissing = code == 404 || (code == 400 && ("model" in text && ("not exist" in text || "not found" in text || "does not exist" in text || "invalid model" in text || "不存在" in text)))
        return when {
            code == null && ("地址" in r.message && "开头" in r.message) -> Check(Verdict.BAD_URL, "接口地址不对：要以 https:// 开头")
            code == null -> Check(Verdict.NETWORK, "网络不通：${r.message}")
            code == 401 || code == 403 -> Check(Verdict.BAD_KEY, "Key 不对或没有权限，请重新复制 Key（HTTP $code）")
            modelMissing -> Check(Verdict.MODEL_NOT_FOUND, "模型不存在：$model，请换一个模型（HTTP $code）")
            code == 402 || code == 429 -> Check(Verdict.QUOTA, "额度不足或请求太频繁（HTTP $code）")
            else -> Check(Verdict.OTHER, "连接失败：${r.message}")
        }
    }

    private fun baseUrlFor(presetId: String, customBaseUrl: String?): String =
        if (presetId == CUSTOM) customBaseUrl.orEmpty().trim().trimEnd('/') else preset(presetId)?.baseUrl.orEmpty()

    /** 可选的模型。内置服务商：pi 目录里的模型（不用 Key）；OpenAI 兼容：用 Key 请求 `{baseUrl}/models`。 */
    suspend fun models(presetId: String, rawKey: String, customBaseUrl: String? = null): Outcome<List<String>> {
        val p = preset(presetId) ?: return Outcome.Err(io.github.zr987459.pigo.contract.ErrCode.PROVIDER, "不认识的服务商")
        if (p.builtIn) {
            val ids = catalogModels(p.id)
            return if (ids.isEmpty()) Outcome.Err(io.github.zr987459.pigo.contract.ErrCode.PROVIDER, "模型目录还没准备好，请稍后再试") else Outcome.Ok(ids)
        }
        val key = sanitizeApiKey(rawKey)
        if (key.isEmpty()) return Outcome.Err(io.github.zr987459.pigo.contract.ErrCode.PROVIDER, "先填写 API Key")
        return when (val r = discover(baseUrlFor(presetId, customBaseUrl), key)) {
            is Outcome.Ok -> Outcome.Ok(r.value)
            is Outcome.Err -> Outcome.Err(r.code, r.message)
        }
    }

    /** 测试连接：对选中的模型发一条最短请求（宿主侧直连，不经容器）。 */
    suspend fun test(presetId: String, rawKey: String, model: String, customBaseUrl: String? = null): Check {
        val key = sanitizeApiKey(rawKey)
        if (key.isEmpty()) return Check(Verdict.BAD_KEY, "先填写 API Key")
        if (model.isBlank()) return Check(Verdict.MODEL_NOT_FOUND, "先选一个模型")
        val url = baseUrlFor(presetId, customBaseUrl)
        if (!url.startsWith("https://") && !url.startsWith("http://")) return Check(Verdict.BAD_URL, "接口地址不对：要以 https:// 开头")
        return classify(probe(url, key, model.trim()), model.trim())
    }

    /** 保存的结果：最终用的 provider/model，以及给用户的一句话。 */
    data class Saved(val provider: String, val model: String, val message: String)

    /**
     * 保存并使用：写配置（Key 进 Keystore；OpenAI 兼容端点写 models.json；默认模型写 settings.json）→ 重启 pi
     * （新 Key 进环境、重读配置，续接当前会话）→ 当前会话 `set_model`。任何一步失败返回中文原因。
     *
     * @param models 列表里拉到的模型（OpenAI 兼容端点一起写进 models.json，之后 `/model` 里也能切）；必含 [model]
     */
    suspend fun save(presetId: String, rawKey: String, model: String, models: List<String> = emptyList(), customBaseUrl: String? = null): Outcome<Saved> {
        val p = preset(presetId) ?: return err("不认识的服务商")
        val m = model.trim()
        if (m.isEmpty()) return err("先选一个模型")
        val provider = try {
            if (p.builtIn) {
                val spec = ProviderSpecs.get(p.id) ?: return err("不认识的服务商：${p.id}")
                val env = spec.keyEnv ?: return err("${spec.label} 只能用账号登录")
                config.setProviderFields(p.id, mapOf(env to rawKey))
                p.id
            } else {
                val url = baseUrlFor(presetId, customBaseUrl)
                val id = if (presetId == CUSTOM) customIdFor(url) else p.id
                val all = (listOf(m) + models.map { it.trim() }).filter { it.isNotEmpty() }.distinct().take(200)
                config.upsertCustomProvider(id, url, PiProviderConfigStore.CustomApi.OPENAI_COMPLETIONS, rawKey, all.map { PiProviderConfigStore.CustomModel(it) })
                id
            }
        } catch (e: IllegalArgumentException) {
            return err(e.message ?: "保存失败")
        } catch (e: JsonFiles.BrokenConfigException) {
            return err(e.message ?: "配置文件格式有误")
        }
        try {
            config.setDefault(provider, m, null)
        } catch (e: Exception) {
            return err("Key 已保存，但默认模型没写进去：${e.message}")
        }
        // 根因修复：Key 只在 pi 进程启动时经环境变量注入、models.json 也只在启动时读，所以必须重启 pi 才用得上。
        when (val r = restartEngine("模型配置已更新")) {
            is Outcome.Err -> return err("已保存，但 pi 没能重启：${r.message}")
            is Outcome.Ok -> Unit
        }
        // 续接的会话里记着旧模型（pi 恢复会话时用会话里的模型，不看默认值），所以再对当前会话发一次 set_model。
        return when (val r = setModel(provider, m)) {
            is Outcome.Ok -> Outcome.Ok(Saved(provider, m, "已保存，现在用 ${p.label.takeIf { presetId != CUSTOM } ?: provider} · $m 聊天"))
            is Outcome.Err -> err("已保存，但切换到 $m 失败：${r.message}")
        }
    }

    private fun err(msg: String) = Outcome.Err(io.github.zr987459.pigo.contract.ErrCode.PROVIDER, msg)
}
