package io.github.zr987459.pigo.core.store

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.sanitizeApiKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * 自定义 OpenAI 兼容端点的「获取模型列表」（piweb-study G2）。在宿主侧直接请求上游 `GET {baseUrl}/models`，
 * Key 留在宿主、不经过容器。
 *
 * 移植自 agegr/pi-web@025e24e `lib/model-discovery.ts:1-135`（列表 URL 构造、OpenAI / Anthropic / Gemini 三种
 * 列表格式解析、去重排序）与 `app/api/models-config/discover/route.ts:13-99`（鉴权头、错误分类），MIT。
 * 只用 JDK 的 [HttpURLConnection]，不新增依赖。
 */
object ModelDiscovery {
    const val TIMEOUT_MS = 15_000

    private val json = Json { ignoreUnknownKeys = true }

    /** `https://x/v1` → `https://x/v1/models`；已经以 `/models` 结尾则原样返回。 */
    fun modelsUrl(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        require(b.startsWith("http://") || b.startsWith("https://")) { "接口地址要以 http:// 或 https:// 开头" }
        return if (b.endsWith("/models")) b else "$b/models"
    }

    /**
     * 解析上游模型列表，返回去重、排序后的模型 id：
     * - OpenAI / 兼容端点：`{"data":[{"id":"qwen-plus"}]}`
     * - Anthropic：`{"data":[{"id":"claude-…","display_name":"…"}]}`
     * - Gemini：`{"models":[{"name":"models/gemini-…"}]}`（去掉 `models/` 前缀）
     * - 少数端点直接返回数组：`[{"id":"…"}]`
     */
    fun parse(body: String): List<String> {
        val root: JsonElement = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val list: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: return emptyList()
            else -> return emptyList()
        }
        return list.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> el.contentOrNull
                is JsonObject -> (el["id"] as? JsonPrimitive)?.contentOrNull
                    ?: (el["name"] as? JsonPrimitive)?.contentOrNull?.removePrefix("models/")
                else -> null
            }
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    }

    /** 「获取模型列表」带回的详情（M7 回填用），上游不给的字段为 null。 */
    data class Discovered(
        val id: String,
        val name: String? = null,
        val contextWindow: Long? = null,
        val maxTokens: Long? = null,
        val reasoning: Boolean? = null,
        val supportsImages: Boolean? = null,
    )

    /**
     * 解析详情（W2 / M7）。识别常见字段：
     * - OpenRouter：`context_length`、`top_provider.max_completion_tokens`、`architecture.input_modalities`、`supported_parameters` 含 `reasoning`；
     * - Gemini：`displayName`、`inputTokenLimit`、`outputTokenLimit`、`thinking`；
     * - Anthropic：`display_name`；
     * - vLLM / 其他兼容端点：`max_model_len`、`context_window`、`max_context_length`、`max_tokens`。
     */
    fun parseDetailed(body: String): List<Discovered> {
        val root: JsonElement = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val list: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: return emptyList()
            else -> return emptyList()
        }
        fun JsonObject.p(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.l(vararg ks: String) = ks.firstNotNullOfOrNull { p(it)?.toDoubleOrNull()?.toLong() }
        val out = LinkedHashMap<String, Discovered>()
        for (el in list) {
            val d = when (el) {
                is JsonPrimitive -> el.contentOrNull?.let { Discovered(it.trim()) }
                is JsonObject -> {
                    val id = (el.p("id") ?: el.p("name")?.removePrefix("models/"))?.trim()
                    if (id.isNullOrEmpty()) null else {
                        val arch = el["architecture"] as? JsonObject
                        val modalities = (arch?.get("input_modalities") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        val params = (el["supported_parameters"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        val top = el["top_provider"] as? JsonObject
                        Discovered(
                            id = id,
                            name = el.p("display_name") ?: el.p("displayName") ?: el.p("name")?.takeIf { !it.startsWith("models/") && it != id },
                            contextWindow = el.l("context_length", "inputTokenLimit", "context_window", "max_context_length", "max_model_len"),
                            maxTokens = top?.l("max_completion_tokens") ?: el.l("outputTokenLimit", "max_output_tokens", "max_tokens"),
                            reasoning = when {
                                params != null -> "reasoning" in params || "include_reasoning" in params
                                el.p("thinking") != null -> el.p("thinking") == "true"
                                else -> null
                            },
                            supportsImages = modalities?.let { "image" in it },
                        )
                    }
                }
                else -> null
            }
            if (d != null && d.id.isNotEmpty() && d.id !in out) out[d.id] = d
        }
        return out.values.sortedBy { it.id }
    }

    /** 带详情的模型列表；[api] 决定鉴权头（Gemini 用 `x-goog-api-key`，Anthropic 加 `anthropic-version`）。 */
    suspend fun discoverDetailed(baseUrl: String, rawKey: String, api: String? = null): Outcome<List<Discovered>> =
        withContext(Dispatchers.IO) {
            val key = sanitizeApiKey(rawKey)
            val url = try {
                modelsUrl(baseUrl)
            } catch (e: IllegalArgumentException) {
                return@withContext Outcome.Err(ErrCode.PROVIDER, e.message ?: "接口地址不正确")
            }
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("Accept", "application/json")
                    if (key.isNotEmpty()) {
                        when (api) {
                            "google-generative-ai" -> setRequestProperty("x-goog-api-key", key)
                            "anthropic-messages" -> {
                                setRequestProperty("x-api-key", key)
                                setRequestProperty("anthropic-version", "2023-06-01")
                                setRequestProperty("Authorization", "Bearer $key")
                            }
                            else -> {
                                setRequestProperty("Authorization", "Bearer $key")
                                setRequestProperty("x-api-key", key)
                            }
                        }
                    }
                }
                try {
                    val code = conn.responseCode
                    val body = HttpText.read(conn, code)
                    if (code !in 200..299) return@withContext Outcome.Err(HttpText.errCode(code), HttpText.statusReason(code, body, key))
                    val list = parseDetailed(body)
                    if (list.isEmpty()) Outcome.Err(ErrCode.PROVIDER, "服务返回了空的模型列表，请手动填写模型 id")
                    else Outcome.Ok(list)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Outcome.Err(ErrCode.NETWORK, HttpText.networkReason(e))
            }
        }

    /** 请求模型列表。失败时返回中文原因（不含 Key）。 */
    suspend fun discover(baseUrl: String, rawKey: String): Outcome<List<String>> = withContext(Dispatchers.IO) {
        val key = sanitizeApiKey(rawKey)
        val url = try {
            modelsUrl(baseUrl)
        } catch (e: IllegalArgumentException) {
            return@withContext Outcome.Err(ErrCode.PROVIDER, e.message ?: "接口地址不正确")
        }
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                if (key.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $key")
                    // Anthropic 风格端点用 x-api-key；OpenAI 兼容端点会忽略它。
                    setRequestProperty("x-api-key", key)
                }
            }
            try {
                val code = conn.responseCode
                val body = HttpText.read(conn, code)
                if (code !in 200..299) return@withContext Outcome.Err(HttpText.errCode(code), HttpText.statusReason(code, body, key))
                val ids = parse(body)
                if (ids.isEmpty()) Outcome.Err(ErrCode.PROVIDER, "服务返回了空的模型列表，请手动填写模型 id")
                else Outcome.Ok(ids)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Outcome.Err(ErrCode.NETWORK, HttpText.networkReason(e))
        }
    }
}

/** HTTP 状态与网络异常 → 中文原因（DESIGN.md §3.9 的口径），ModelDiscovery 与 ProviderTester 共用。 */
internal object HttpText {
    fun read(conn: HttpURLConnection, code: Int): String = runCatching {
        val s = if (code >= 400) conn.errorStream else conn.inputStream
        s?.bufferedReader()?.use { it.readText().take(64 * 1024) }.orEmpty()
    }.getOrDefault("")

    fun errCode(code: Int): ErrCode = when (code) {
        401, 403, 402, 404, 429 -> ErrCode.PROVIDER
        else -> if (code >= 500) ErrCode.PROVIDER else ErrCode.UNKNOWN
    }

    fun statusReason(code: Int, body: String, key: String): String {
        val head = when (code) {
            401, 403 -> "Key 错误或没有权限"
            402 -> "额度不足"
            404 -> "接口地址或模型不存在"
            429 -> "请求太频繁或额度用完"
            in 500..599 -> "模型服务出错"
            else -> "请求失败"
        }
        val detail = KeyRedaction.redact(body.replace(Regex("\\s+"), " ").trim(), listOf(key)).take(200)
        return "$head（HTTP $code）" + if (detail.isNotEmpty()) "：$detail" else ""
    }

    fun networkReason(e: Exception): String = when (e) {
        is UnknownHostException -> "找不到服务器，请检查接口地址和手机网络"
        is SocketTimeoutException -> "连接超时，模型服务没有回应"
        is javax.net.ssl.SSLException -> "安全连接失败（证书或代理问题）"
        is java.net.ConnectException -> "连不上模型服务，请检查接口地址和手机网络"
        is IOException -> "网络错误：${e.message ?: e::class.simpleName}"
        else -> "请求失败：${e.message ?: e::class.simpleName}"
    }
}
