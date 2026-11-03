package io.github.zr987459.pigo.core.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * pi 模型目录（`pigo-catalog.mjs` 的输出，schema 1）。
 *
 * - APK 内置一份 pi 1.5.0 的快照（`resources/pigo/catalog-1.5.0.json`），运行环境未就绪时也能浏览全部服务商与模型；
 * - 运行环境就绪后在容器里重新导出，按 pi 版本缓存在宿主（见 `:core:catalog`），pi 升级后自动换新。
 */
object BuiltinCatalog {
    const val SCHEMA = 1
    const val BUNDLED_PI_VERSION = "1.5.0"
    private const val RESOURCE = "/pigo/catalog-1.5.0.json"

    data class Cost(val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double)

    data class Model(
        val provider: String,
        val id: String,
        val name: String,
        val api: String?,
        val reasoning: Boolean,
        val input: List<String>,
        val contextWindow: Long?,
        val maxTokens: Long?,
        /** 美元 / 百万 token。 */
        val cost: Cost?,
        val thinkingLevels: List<String>?,
    ) {
        val supportsImages: Boolean get() = "image" in input
    }

    data class Provider(
        val id: String,
        val name: String,
        val baseUrl: String?,
        val apiKeyAuth: Boolean,
        val oauth: Boolean,
        val oauthName: String?,
        val subscription: Boolean,
        val models: List<Model>,
    )

    data class Snapshot(val piVersion: String, val providers: List<Provider>) {
        fun provider(id: String): Provider? = providers.firstOrNull { it.id == id }
        val modelCount: Int get() = providers.sumOf { it.models.size }
    }

    class CatalogFormatException(message: String) : IllegalArgumentException(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var bundledCache: Snapshot? = null

    /** APK 内置快照。 */
    fun bundled(): Snapshot = bundledCache ?: synchronized(this) {
        bundledCache ?: run {
            val text = BuiltinCatalog::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw CatalogFormatException("缺少内置模型目录 $RESOURCE")
            parse(text).also { bundledCache = it }
        }
    }

    /** 解析导出脚本的输出；不认识的 schema 或缺字段时抛 [CatalogFormatException]。只取最后一个非空行（脚本前面可能有警告）。 */
    fun parse(text: String): Snapshot {
        val line = text.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") }
            ?: throw CatalogFormatException("目录输出为空")
        val root = try {
            json.parseToJsonElement(line) as? JsonObject
        } catch (e: Exception) {
            throw CatalogFormatException("目录不是有效的 JSON：${e.message}")
        } ?: throw CatalogFormatException("目录不是 JSON 对象")
        val schema = root.long("schema") ?: throw CatalogFormatException("目录缺少 schema")
        if (schema.toInt() != SCHEMA) throw CatalogFormatException("不支持的目录版本 $schema")
        val providers = (root["providers"] as? JsonArray ?: throw CatalogFormatException("目录缺少 providers")).mapNotNull { el ->
            val p = el as? JsonObject ?: return@mapNotNull null
            val id = p.str("id") ?: return@mapNotNull null
            val auth = p["auth"] as? JsonObject
            Provider(
                id = id,
                name = p.str("name") ?: id,
                baseUrl = p.str("baseUrl"),
                apiKeyAuth = auth?.bool("apiKey") ?: true,
                oauth = auth?.bool("oauth") ?: false,
                oauthName = auth?.str("oauthName"),
                subscription = auth?.bool("subscription") ?: false,
                models = (p["models"] as? JsonArray).orEmpty().mapNotNull { m -> model(id, m) },
            )
        }
        return Snapshot(root.str("piVersion") ?: "?", providers)
    }

    private fun model(provider: String, el: JsonElement): Model? {
        val m = el as? JsonObject ?: return null
        val id = m.str("id") ?: return null
        val c = m["cost"] as? JsonObject
        return Model(
            provider = provider,
            id = id,
            name = m.str("name") ?: id,
            api = m.str("api"),
            reasoning = m.bool("reasoning") ?: false,
            input = (m["input"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: listOf("text"),
            contextWindow = m.long("contextWindow"),
            maxTokens = m.long("maxTokens"),
            cost = c?.let { Cost(it.dbl("input") ?: 0.0, it.dbl("output") ?: 0.0, it.dbl("cacheRead") ?: 0.0, it.dbl("cacheWrite") ?: 0.0) },
            thinkingLevels = (m["thinkingLevels"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content },
        )
    }

    private fun JsonObject.prim(k: String) = (this[k] as? JsonPrimitive)?.takeIf { this[k] !is JsonNull }
    private fun JsonObject.str(k: String) = prim(k)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(k: String) = prim(k)?.booleanOrNull
    private fun JsonObject.long(k: String) = prim(k)?.longOrNull ?: prim(k)?.doubleOrNull?.toLong()
    private fun JsonObject.dbl(k: String) = prim(k)?.doubleOrNull
}
