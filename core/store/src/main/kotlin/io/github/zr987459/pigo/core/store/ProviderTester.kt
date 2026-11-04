package io.github.zr987459.pigo.core.store

import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.sanitizeApiKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/** 一次「测试连接」的结果。[message] 是给用户看的中文原因，已脱敏。 */
data class ProbeResult(val ok: Boolean, val message: String, val httpStatus: Int? = null, val elapsedMs: Long = 0)

/**
 * 设置页的「测试连接」（piweb-study G3）：Key / 模型 / 地址错了，不用发第一条消息才知道。
 *
 * - 自定义 OpenAI 兼容端点：宿主侧 `POST {baseUrl}/chat/completions`，提示词「Reply with OK only.」、`max_tokens 16`、
 *   20 s 超时、不重试；
 * - 内置 Provider：在容器里跑一次 `pi --print --no-session --provider <id> [--model <id>]`，Key 只经进程环境变量传入：
 *   `:core:runtime` 把容器内变量放进 proot 进程的环境，argv 里只有 `env -u <名字>`，Key 不出现在任何进程的 cmdline 里
 *   （见 `ProotLauncher`，审查 R1 #1）。
 *
 * 移植自 agegr/pi-web@025e24e `app/api/models-config/test/route.ts:11`、`:28-121`（提示词、maxTokens 16、20 s、
 * 结果结构：成功/失败原因、HTTP 状态、耗时），MIT。
 */
object ProviderTester {
    const val PROMPT = "Reply with OK only."
    const val HTTP_TIMEOUT_MS = 20_000
    /** 容器里冷启动 node + pi 比直连慢，给 45 s。 */
    const val PI_TIMEOUT_MS = 45_000L

    fun chatUrl(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        require(b.startsWith("http://") || b.startsWith("https://")) { "接口地址要以 http:// 或 https:// 开头" }
        return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
    }

    fun requestBody(model: String): String = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", PROMPT) }) })
        put("max_tokens", 16)
        put("stream", false)
    }.toString()

    /** 测试自定义 OpenAI 兼容端点。 */
    suspend fun testOpenAiCompatible(
        baseUrl: String,
        rawKey: String,
        model: String,
        clock: () -> Long = System::currentTimeMillis,
        /** M9 的代理（[HostProxy.select]），与 pi 进程的 HTTPS_PROXY 一致；默认直连。 */
        proxy: java.net.Proxy = java.net.Proxy.NO_PROXY,
    ): ProbeResult =
        withContext(Dispatchers.IO) {
            val key = sanitizeApiKey(rawKey)
            if (model.isBlank()) return@withContext ProbeResult(false, "先填写或选择一个模型 id")
            val url = try {
                chatUrl(baseUrl)
            } catch (e: IllegalArgumentException) {
                return@withContext ProbeResult(false, e.message ?: "接口地址不正确")
            }
            val t0 = clock()
            try {
                val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    if (key.isNotEmpty()) setRequestProperty("Authorization", "Bearer $key")
                }
                try {
                    conn.outputStream.use { it.write(requestBody(model.trim()).toByteArray()) }
                    val code = conn.responseCode
                    val body = HttpText.read(conn, code)
                    val ms = clock() - t0
                    if (code in 200..299) ProbeResult(true, "连接正常（模型 ${model.trim()}），耗时 $ms ms", code, ms)
                    else ProbeResult(false, HttpText.statusReason(code, body, key), code, ms)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                ProbeResult(false, HttpText.networkReason(e), null, clock() - t0)
            }
        }

    fun piArgs(provider: String, model: String?): List<String> = buildList {
        add("pi"); add("--print"); add("--no-session"); add("--provider"); add(provider)
        if (!model.isNullOrBlank()) { add("--model"); add(model.trim()) }
        add(PROMPT)
    }

    /** 测试内置 Provider：要求运行环境已就绪。[envVar] 为该 Provider 的 Key 环境变量名（如 DEEPSEEK_API_KEY）。 */
    suspend fun testBuiltIn(
        runtime: RuntimeManager,
        provider: String,
        envVar: String,
        rawKey: String,
        model: String? = null,
        clock: () -> Long = System::currentTimeMillis,
        /** 其余要一起注入的变量（多变量服务商的区域/账户、代理等，W2）。 */
        extraEnv: Map<String, String> = emptyMap(),
    ): ProbeResult {
        val key = sanitizeApiKey(rawKey)
        if (key.isEmpty()) return ProbeResult(false, "还没有填写 Key")
        val t0 = clock()
        val r = try {
            runtime.exec(piArgs(provider, model), env = extraEnv + (envVar to key), timeoutMs = PI_TIMEOUT_MS)
        } catch (e: Exception) {
            return ProbeResult(false, "运行环境不可用：" + KeyRedaction.redact(e.message ?: e::class.simpleName.orEmpty(), listOf(key) + extraEnv.values))
        }
        val ms = clock() - t0
        if (r.timedOut) return ProbeResult(false, "超过 ${PI_TIMEOUT_MS / 1000} 秒没有回应：模型服务可能连不上", null, ms)
        if (r.exitCode == 0 && r.stdout.isNotBlank()) return ProbeResult(true, "连接正常，耗时 $ms ms", null, ms)
        val raw = (r.stderr.ifBlank { r.stdout }).lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
        return ProbeResult(false, KeyRedaction.redact(classifyPiError(raw, key), extraEnv.values), statusIn(raw), ms)
    }

    private val status = Regex("""(?<![\d.])(4\d\d|5\d\d)(?![\d.])""")

    private fun statusIn(raw: String): Int? = status.find(raw)?.value?.toIntOrNull()

    /** pi 报错文本 → 中文原因（口径同 DESIGN §3.9 / 聊天错误卡）。 */
    fun classifyPiError(raw: String, key: String = ""): String {
        val lower = raw.lowercase()
        val code = statusIn(raw)
        val head = when {
            code == 401 || code == 403 || "unauthorized" in lower || "invalid api key" in lower ||
                "authentication" in lower || ("api key" in lower && "invalid" in lower) -> "Key 错误或没有权限"
            code == 402 || code == 429 || "quota" in lower || "insufficient" in lower || "rate limit" in lower -> "额度不足或请求太频繁"
            code == 404 || "model not found" in lower || "model_not_found" in lower || "unknown model" in lower -> "模型不存在"
            "enotfound" in lower || "econnrefused" in lower || "fetch failed" in lower || "network" in lower ||
                "etimedout" in lower || "timeout" in lower -> "连不上模型服务"
            "no api key" in lower || "no models" in lower || "not configured" in lower -> "这个服务还没有可用的 Key 或模型"
            else -> "测试失败"
        }
        val detail = KeyRedaction.redact(raw, listOf(key)).take(200)
        return head + (code?.let { "（HTTP $it）" } ?: "") + if (detail.isNotEmpty()) "：$detail" else ""
    }
}
