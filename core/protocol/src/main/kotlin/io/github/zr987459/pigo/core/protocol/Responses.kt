package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.PiState
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.SessionStats
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 把各命令 `response.data` 解析成 `:core:contract` 的数据类（字段名取自 pi 1.5.0 `rpc-commands.md`）。宽松，不抛异常。 */
object Responses {

    /** pi 未选模型时返回 `provider:"unknown"` 的占位 Model；视为没有模型。 */
    fun model(e: JsonElement?): ModelInfo? {
        val o = e as? JsonObject ?: return null
        val provider = o.str("provider") ?: return null
        val id = o.str("id") ?: return null
        if (provider == "unknown" && id == "unknown") return null
        return ModelInfo(
            provider = provider,
            id = id,
            name = o.str("name"),
            reasoning = o.bool("reasoning") ?: false,
            contextWindow = o.long("contextWindow")?.takeIf { it > 0 },
            raw = o,
        )
    }

    fun state(data: JsonElement?): PiState {
        val o = data as? JsonObject ?: return PiState()
        return PiState(
            sessionFile = o.str("sessionFile"),
            sessionId = o.str("sessionId"),
            sessionName = o.str("sessionName"),
            model = model(o["model"]),
            thinkingLevel = o.str("thinkingLevel"),
            isStreaming = o.bool("isStreaming") ?: false,
            steeringMode = o.str("steeringMode"),
            followUpMode = o.str("followUpMode"),
            pendingMessageCount = o.long("pendingMessageCount")?.toInt() ?: 0,
            raw = o,
        )
    }

    fun disposition(data: JsonElement?): Disposition = when ((data as? JsonObject)?.str("disposition")) {
        "queued" -> Disposition.QUEUED
        "handled" -> Disposition.HANDLED
        else -> Disposition.STARTED
    }

    fun queue(data: JsonElement?): QueueSnapshot {
        val o = data as? JsonObject ?: return QueueSnapshot.EMPTY
        return QueueSnapshot(o.strings("steering"), o.strings("followUp"))
    }

    /** `new_session` / `switch_session` 被扩展拦截时 `data.cancelled == true`。 */
    fun cancelled(data: JsonElement?): Boolean = (data as? JsonObject)?.bool("cancelled") ?: false

    fun models(data: JsonElement?): List<ModelInfo> {
        val arr = when (data) {
            is JsonArray -> data
            is JsonObject -> data.arr("models")
            else -> null
        } ?: return emptyList()
        return arr.mapNotNull { model(it) }
    }

    fun thinkingLevels(data: JsonElement?): List<String> {
        val arr = when (data) {
            is JsonArray -> data
            is JsonObject -> data.arr("levels")
            else -> null
        } ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }

    fun stats(data: JsonElement?): SessionStats {
        val o = data as? JsonObject ?: return SessionStats(null, null, null)
        val ctx = o.obj("contextUsage")
        val tokens = o.obj("tokens")
        return SessionStats(
            contextPercent = ctx?.double("percent"),
            totalTokens = tokens?.long("total"),
            cost = o.double("cost"),
            raw = o,
        )
    }

    fun messages(data: JsonElement?): JsonArray = when (data) {
        is JsonArray -> data
        is JsonObject -> data.arr("messages") ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
}
