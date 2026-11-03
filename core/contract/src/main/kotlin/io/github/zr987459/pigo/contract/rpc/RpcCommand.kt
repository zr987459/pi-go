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
                value != null -> put("value", value)
                // 三者都没给：按“用户关闭对话框”处理，避免发出 value:null 让 pi 误解
                else -> put("cancelled", true)
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

// =====================================================================================================
// 以下为 feat/engine-rpc（W1）追加：pi 1.5.0 `dist/modes/rpc/rpc-types.d.ts` 中其余命令。只追加，不改上面已有类型。
// 放在 [RpcCommands] 里以免与上面的嵌套类型重名；它们仍是密封接口 [RpcCommand] 的实现（同包同模块）。
// =====================================================================================================

/** `ImageContent`（pi-ai）：`{"type":"image","data":<base64>,"mimeType":"image/png"}`。 */
data class RpcImage(val data: String, val mimeType: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("type", "image")
        put("data", data)
        put("mimeType", mimeType)
    }
}

object RpcCommands {
    /** 带图片的 `prompt`；`images` 为空时不写该字段，与 [RpcCommand.Prompt] 等价。 */
    data class PromptWithImages(
        override val id: String,
        val message: String,
        val images: List<RpcImage>,
        val streamingBehavior: String? = null,
    ) : RpcCommand {
        override val type = "prompt"
        override fun toJson() = obj(id, type) {
            put("message", message)
            putImages(images)
            streamingBehavior?.let { put("streamingBehavior", it) }
        }
    }

    /** `steer`：生成中插话（可带图片）。响应 `data.disposition`。 */
    data class Steer(override val id: String, val message: String, val images: List<RpcImage> = emptyList()) : RpcCommand {
        override val type = "steer"
        override fun toJson() = obj(id, type) {
            put("message", message)
            putImages(images)
        }
    }

    /** `follow_up`：排到本轮之后（可带图片）。响应 `data.disposition`。 */
    data class FollowUp(override val id: String, val message: String, val images: List<RpcImage> = emptyList()) : RpcCommand {
        override val type = "follow_up"
        override fun toJson() = obj(id, type) {
            put("message", message)
            putImages(images)
        }
    }

    /** `new_session`，带父会话（`parentSession`）。 */
    data class NewSessionWithParent(override val id: String, val parentSession: String) : RpcCommand {
        override val type = "new_session"
        override fun toJson() = obj(id, type) { put("parentSession", parentSession) }
    }

    data class CycleModel(override val id: String) : RpcCommand {
        override val type = "cycle_model"
        override fun toJson() = obj(id, type)
    }

    data class CycleThinkingLevel(override val id: String) : RpcCommand {
        override val type = "cycle_thinking_level"
        override fun toJson() = obj(id, type)
    }

    /** `mode`：`all` 或 `one-at-a-time`。 */
    data class SetSteeringMode(override val id: String, val mode: String) : RpcCommand {
        override val type = "set_steering_mode"
        override fun toJson() = obj(id, type) { put("mode", mode) }
    }

    /** `mode`：`all` 或 `one-at-a-time`。 */
    data class SetFollowUpMode(override val id: String, val mode: String) : RpcCommand {
        override val type = "set_follow_up_mode"
        override fun toJson() = obj(id, type) { put("mode", mode) }
    }

    /** 手动压缩；响应 `data` 为 `CompactionResult`，之前会先发 `compaction_start`/`compaction_end` 事件。 */
    data class Compact(override val id: String, val customInstructions: String? = null) : RpcCommand {
        override val type = "compact"
        override fun toJson() = obj(id, type) { customInstructions?.let { put("customInstructions", it) } }
    }

    data class SetAutoCompaction(override val id: String, val enabled: Boolean) : RpcCommand {
        override val type = "set_auto_compaction"
        override fun toJson() = obj(id, type) { put("enabled", enabled) }
    }

    data class SetAutoRetry(override val id: String, val enabled: Boolean) : RpcCommand {
        override val type = "set_auto_retry"
        override fun toJson() = obj(id, type) { put("enabled", enabled) }
    }

    /**
     * 用户直接执行 shell（`!cmd`）。输出经 `bash_execution_update{id,delta}` 流式推送，`id` 就是本命令的 id；
     * 响应 `data` 为 `BashResult`。`excludeFromContext`（`!!cmd`）为 true 时才写出该字段。
     */
    data class Bash(override val id: String, val command: String, val excludeFromContext: Boolean = false) : RpcCommand {
        override val type = "bash"
        override fun toJson() = obj(id, type) {
            put("command", command)
            if (excludeFromContext) put("excludeFromContext", true)
        }
    }

    data class AbortBash(override val id: String) : RpcCommand {
        override val type = "abort_bash"
        override fun toJson() = obj(id, type)
    }

    /** 响应 `data.path`。`outputPath` 为容器内路径；null 时 pi 自己选路径。 */
    data class ExportHtml(override val id: String, val outputPath: String? = null) : RpcCommand {
        override val type = "export_html"
        override fun toJson() = obj(id, type) { outputPath?.let { put("outputPath", it) } }
    }

    /** 从某条用户消息分叉出新会话；响应 `data{text,cancelled}`，`text` 为该用户消息原文（回填输入框）。 */
    data class Fork(override val id: String, val entryId: String) : RpcCommand {
        override val type = "fork"
        override fun toJson() = obj(id, type) { put("entryId", entryId) }
    }

    /** 复制当前会话（在当前叶子处分叉）；响应 `data{cancelled}`。 */
    data class Clone(override val id: String) : RpcCommand {
        override val type = "clone"
        override fun toJson() = obj(id, type)
    }

    /** 响应 `data.messages: [{entryId,text}]`。 */
    data class GetForkMessages(override val id: String) : RpcCommand {
        override val type = "get_fork_messages"
        override fun toJson() = obj(id, type)
    }

    /** 增量取会话条目：`since` 为上次最后一条的 id（不含它本身）；响应 `data{entries,leafId}`。 */
    data class GetEntries(override val id: String, val since: String? = null) : RpcCommand {
        override val type = "get_entries"
        override fun toJson() = obj(id, type) { since?.let { put("since", it) } }
    }

    /** 响应 `data{tree: SessionTreeNode[], leafId}`。 */
    data class GetTree(override val id: String) : RpcCommand {
        override val type = "get_tree"
        override fun toJson() = obj(id, type)
    }

    /** 响应 `data.text`（可为 null）。 */
    data class GetLastAssistantText(override val id: String) : RpcCommand {
        override val type = "get_last_assistant_text"
        override fun toJson() = obj(id, type)
    }

    /** 响应 `data.commands: RpcSlashCommand[]`（扩展命令、提示模板、技能）。 */
    data class GetCommands(override val id: String) : RpcCommand {
        override val type = "get_commands"
        override fun toJson() = obj(id, type)
    }

    /** 1.5.0 的全部命令 `type`（含上面已有的），供快照测试核对。 */
    val ALL_TYPES: List<String> = listOf(
        "prompt", "steer", "follow_up", "abort", "clear_queue", "new_session", "get_state", "set_model", "cycle_model",
        "get_available_models", "set_thinking_level", "cycle_thinking_level", "get_available_thinking_levels",
        "set_steering_mode", "set_follow_up_mode", "compact", "set_auto_compaction", "set_auto_retry", "abort_retry",
        "bash", "abort_bash", "get_session_stats", "export_html", "switch_session", "fork", "clone", "get_fork_messages",
        "get_entries", "get_tree", "get_last_assistant_text", "set_session_name", "get_messages", "get_commands",
    )

    const val MODE_ALL = "all"
    const val MODE_ONE_AT_A_TIME = "one-at-a-time"
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putImages(images: List<RpcImage>) {
    if (images.isNotEmpty()) put("images", kotlinx.serialization.json.JsonArray(images.map { it.toJson() }))
}
