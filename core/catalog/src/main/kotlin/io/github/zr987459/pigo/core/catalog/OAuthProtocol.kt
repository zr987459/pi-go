package io.github.zr987459.pigo.core.catalog

import io.github.zr987459.pigo.core.store.KeyRedaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** `pigo-auth.mjs` 输出的一条消息（见脚本头部的协议说明）。 */
sealed interface LoginEvent {
    /** 需要用户在浏览器里完成授权（PKCE 回调类：Claude、ChatGPT/Codex 等）。 */
    data class AuthUrl(val url: String, val instructions: String?) : LoginEvent

    /** 设备码（GitHub Copilot 等）：让用户在 [verificationUri] 输入 [userCode]。 */
    data class DeviceCode(val userCode: String, val verificationUri: String, val expiresInSeconds: Long?) : LoginEvent

    data class Info(val message: String, val links: List<String>) : LoginEvent
    data class Progress(val message: String) : LoginEvent

    /** 需要用户输入：`text`（如企业域名）、`secret`、`manual_code`（粘贴授权码）、`select`。 */
    data class Prompt(
        val id: String,
        val kind: String,
        val message: String,
        val placeholder: String?,
        val options: List<Option>,
    ) : LoginEvent {
        data class Option(val id: String, val label: String, val description: String?)
    }

    data class Done(val provider: String, val credential: String) : LoginEvent
    data class Failed(val message: String, val cancelled: Boolean) : LoginEvent
}

object OAuthProtocol {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 解析一行；不是协议消息（脚本的警告、node 的输出）返回 null。所有文本都过一遍脱敏。 */
    fun parse(line: String): LoginEvent? {
        val t = line.trim()
        if (!t.startsWith("{")) return null
        val o = runCatching { json.parseToJsonElement(t) as? JsonObject }.getOrNull() ?: return null
        return when (o.s("type")) {
            "event" -> event(o["event"] as? JsonObject ?: return null)
            "prompt" -> {
                val p = o["prompt"] as? JsonObject ?: return null
                LoginEvent.Prompt(
                    id = o.s("id") ?: return null,
                    kind = p.s("type") ?: "text",
                    message = clean(p.s("message").orEmpty()),
                    placeholder = p.s("placeholder"),
                    options = (p["options"] as? JsonArray).orEmpty().mapNotNull { el ->
                        val x = el as? JsonObject ?: return@mapNotNull null
                        LoginEvent.Prompt.Option(x.s("id") ?: return@mapNotNull null, x.s("label") ?: x.s("id")!!, x.s("description"))
                    },
                )
            }
            "done" -> LoginEvent.Done(o.s("provider").orEmpty(), o.s("credential").orEmpty())
            "error" -> LoginEvent.Failed(clean(o.s("message").orEmpty()), (o["cancelled"] as? JsonPrimitive)?.content == "true")
            else -> null
        }
    }

    private fun event(e: JsonObject): LoginEvent? {
        return when (e.s("type")) {
        "auth_url" -> LoginEvent.AuthUrl(e.s("url") ?: return null, e.s("instructions"))
        "device_code" -> LoginEvent.DeviceCode(
            e.s("userCode") ?: return null,
            e.s("verificationUri") ?: return null,
            (e["expiresInSeconds"] as? JsonPrimitive)?.longOrNull,
        )
        "info" -> LoginEvent.Info(
            clean(e.s("message").orEmpty()),
            (e["links"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.s("url") },
        )
        "progress" -> LoginEvent.Progress(clean(e.s("message").orEmpty()))
        else -> null
        }
    }

    fun answer(id: String, value: String): String = buildJsonObject {
        put("type", "answer"); put("id", id); put("value", value)
    }.toString()

    fun cancel(): String = """{"type":"cancel"}"""

    /** 日志与界面文本统一脱敏（令牌形态、Bearer、sk-）。 */
    fun clean(s: String): String = KeyRedaction.redact(s)

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
