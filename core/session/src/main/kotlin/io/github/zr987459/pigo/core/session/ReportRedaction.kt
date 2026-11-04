package io.github.zr987459.pigo.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 问题报告（`/bug`）里的 settings.json 打码（审查第 2 轮 N2）：报告是要分享出去的文件。
 * - URL 里的 `user:pass@` 一律换成 `***@`（如 `httpProxy`、`https_proxy`、镜像地址）；
 * - 键名看起来像机密（key、token、secret、password、auth、credential、cookie、header）的值整个换成 `***`；
 * - 对象与数组逐层处理；其余值原样保留，方便排查。
 */
object ReportRedaction {
    const val MASK = "***"
    private val SECRET_KEY = Regex("(?i)(api[-_]?key|key$|token|secret|passw|pwd|auth|credential|cookie|header|bearer|private)")
    private val URL_USERINFO = Regex("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s:]+(?::[^/@\\s]*)?@")

    fun isSecretKey(key: String) = SECRET_KEY.containsMatchIn(key)

    fun redactText(s: String): String = URL_USERINFO.replace(s) { it.groupValues[1] + MASK + "@" }

    fun redact(key: String, v: JsonElement): JsonElement = when {
        v is JsonNull -> v
        isSecretKey(key) -> JsonPrimitive(MASK)
        v is JsonPrimitive -> if (v.isString) JsonPrimitive(redactText(v.content)) else v
        v is JsonObject -> JsonObject(v.mapValues { (k, e) -> redact(k, e) })
        v is JsonArray -> JsonArray(v.map { redact(key.removeSuffix("s"), it) })
        else -> v
    }

    /** settings.json 的每一行：`键 = 打码后的值`。 */
    fun settingsLines(values: Map<String, JsonElement>): List<String> = values.map { (k, v) -> "$k = ${redact(k, v)}" }
}
