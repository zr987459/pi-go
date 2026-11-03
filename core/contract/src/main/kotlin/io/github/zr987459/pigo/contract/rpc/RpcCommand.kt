package io.github.zr987459.pigo.contract.rpc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 客户端 → pi 的命令（DESIGN.md §3.4）。字段名与 pi 1.5.0 `rpc-commands.md` 一致。
 * 除 [ExtensionUiResponse] 外都带客户端生成的 `id`（`c-<自增>`），响应按 id 关联。
 */
sealed interface RpcCommand {
    /** 命令的 `type`，也是响应里的 `command`。 */
    val type: String
    val id: String?

    fun toJson(): JsonObject

    data class Prompt(
        override val id: String,
        val message: String,
        /** 生成中必须带：`followUp` 或 `steer`；空闲时为 null。 */
        val streamingBehavior: String? = null,
    ) : RpcCommand {
        override val type = "prompt"
        override fun toJson() = obj(id, type) {
            put("message", message)
            streamingBehavior?.let { put("streamingBehavior", it) }
        }
    }

    data class ClearQueue(override val id: String) : RpcCommand {
        override val type = "clear_queue"
        override fun toJson() = obj(id, type)
    }

    data class Abort(override val id: String) : RpcCommand {
        override val type = "abort"
        override fun toJson() = obj(id, type)
    }

    data class AbortRetry(override val id: String) : RpcCommand {
        override val type = "abort_retry"
        override fun toJson() = obj(id, type)
    }

    data class GetState(override val id: String) : RpcCommand {
        override val type = "get_state"
        override fun toJson() = obj(id, type)
    }

    data class GetMessages(override val id: String) : RpcCommand {
        override val type = "get_messages"
        override fun toJson() = obj(id, type)
    }

    data class NewSession(override val id: String) : RpcCommand {
        override val type = "new_session"
        override fun toJson() = obj(id, type)
    }

    data class SwitchSession(override val id: String, val sessionPath: String) : RpcCommand {
        override val type = "switch_session"
        override fun toJson() = obj(id, type) { put("sessionPath", sessionPath) }
    }

    data class SetSessionName(override val id: String, val name: String) : RpcCommand {
        override val type = "set_session_name"
        override fun toJson() = obj(id, type) { put("name", name) }
    }

    data class GetAvailableModels(override val id: String) : RpcCommand {
        override val type = "get_available_models"
        override fun toJson() = obj(id, type)
    }

    data class SetModel(override val id: String, val provider: String, val modelId: String) : RpcCommand {
        override val type = "set_model"
        override fun toJson() = obj(id, type) {
            put("provider", provider)
            put("modelId", modelId)
        }
    }

    data class GetAvailableThinkingLevels(override val id: String) : RpcCommand {
        override val type = "get_available_thinking_levels"
        override fun toJson() = obj(id, type)
    }

    data class SetThinkingLevel(override val id: String, val level: String) : RpcCommand {
        override val type = "set_thinking_level"
        override fun toJson() = obj(id, type) { put("level", level) }
    }

    data class GetSessionStats(override val id: String) : RpcCommand {
        override val type = "get_session_stats"
        override fun toJson() = obj(id, type)
    }

    /** 扩展对话框应答：用请求自己的 id，pi 不回 response。 */
    data class ExtensionUiResponse(
        val requestId: String,
        val value: String? = null,
        val confirmed: Boolean? = null,
        val cancelled: Boolean = false,
    ) : RpcCommand {
        override val type = "extension_ui_response"
        override val id: String? get() = null
        override fun toJson() = buildJsonObject {
            put("type", type)
            put("id", requestId)
            when {
                cancelled -> put("cancelled", true)
                confirmed != null -> put("confirmed", confirmed)
                else -> put("value", value)
            }
        }
    }

    companion object {
        const val STREAMING_FOLLOW_UP = "followUp"
        const val STREAMING_STEER = "steer"
    }
}

private inline fun obj(
    id: String,
    type: String,
    crossinline extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
): JsonObject = buildJsonObject {
    put("id", id)
    put("type", type)
    extra()
}
