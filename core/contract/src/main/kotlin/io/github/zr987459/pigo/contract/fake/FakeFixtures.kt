package io.github.zr987459.pigo.contract.fake

import io.github.zr987459.pigo.contract.rpc.AssistantMessageEvent
import io.github.zr987459.pigo.contract.rpc.EngineEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 内置夹具：Fake 引擎回放用（DESIGN.md §6 scaffold 验收）。
 * 一段完整的回合：流式文本 → 两个工具卡（bash、read）→ 一次排队（followUp）→ 排队消息作为下一回合送达 → `agent_settled`。
 * 字段名与 pi 1.5.0 的 `json.md` 一致，便于 W2 用同一份数据对照真实 ChatReducer。
 */
object FakeFixtures {

    const val QUEUED_TEXT = "做完再跑一遍测试"

    fun demoTurn(userText: String = "列出当前目录并读 README"): List<EngineEvent> = buildList {
        add(EngineEvent.AgentStart(ev("agent_start")))
        add(EngineEvent.TurnStart(ev("turn_start")))
        add(messageStart(userMessage(userText)))

        // 第一条 assistant：流式文本 + 两个 toolCall
        add(messageStart(assistantMessage()))
        add(update(AssistantMessageEvent.Start, "start"))
        add(update(AssistantMessageEvent.TextStart(0), "text_start", 0))
        for (d in listOf("我先看一下", "当前目录，", "再读 README。")) {
            add(update(AssistantMessageEvent.TextDelta(0, d), "text_delta", 0, delta = d))
        }
        add(update(AssistantMessageEvent.TextEnd(0, "我先看一下当前目录，再读 README。"), "text_end", 0))
        add(update(AssistantMessageEvent.ToolcallStart(1, "call-bash-1", "bash"), "toolcall_start", 1))
        add(update(AssistantMessageEvent.ToolcallEnd(1, toolCall("call-bash-1", "bash", "command", "ls")), "toolcall_end", 1))
        add(update(AssistantMessageEvent.ToolcallStart(2, "call-read-1", "read"), "toolcall_start", 2))
        add(update(AssistantMessageEvent.ToolcallEnd(2, toolCall("call-read-1", "read", "path", "README.md")), "toolcall_end", 2))
        add(update(AssistantMessageEvent.Done("toolUse"), "done"))
        add(messageEnd(assistantMessage("我先看一下当前目录，再读 README。", stopReason = "toolUse")))

        // 工具卡 1：bash
        add(toolStart("call-bash-1", "bash", "command", "ls"))
        add(EngineEvent.ToolExecutionUpdate("call-bash-1", textResult("README.md\n"), ev("tool_execution_update")))
        add(toolEnd("call-bash-1", "README.md\nsrc\n", isError = false, durationMs = 120))

        // 生成中用户排队了一条消息
        add(queue(followUp = listOf(QUEUED_TEXT)))

        // 工具卡 2：read
        add(toolStart("call-read-1", "read", "path", "README.md"))
        add(toolEnd("call-read-1", "# demo\n示例项目。\n", isError = false, durationMs = 35))
        add(EngineEvent.TurnEnd(null, null, ev("turn_end")))

        // 第二条 assistant：最终回答
        add(EngineEvent.TurnStart(ev("turn_start")))
        add(messageStart(assistantMessage()))
        add(update(AssistantMessageEvent.TextStart(0), "text_start", 0))
        for (d in listOf("目录里有 README.md 和 src。", "README 说明这是一个示例项目。")) {
            add(update(AssistantMessageEvent.TextDelta(0, d), "text_delta", 0, delta = d))
        }
        add(update(AssistantMessageEvent.TextEnd(0, "目录里有 README.md 和 src。README 说明这是一个示例项目。"), "text_end", 0))
        add(messageEnd(assistantMessage("目录里有 README.md 和 src。README 说明这是一个示例项目。")))
        add(EngineEvent.TurnEnd(null, null, ev("turn_end")))

        // 排队消息送达：队列清空 → 作为下一回合的 user 消息
        add(queue(followUp = emptyList()))
        add(EngineEvent.TurnStart(ev("turn_start")))
        addAll(shortReply(QUEUED_TEXT, "好的，测试全部通过。", withUser = true))
        add(EngineEvent.TurnEnd(null, null, ev("turn_end")))

        add(EngineEvent.AgentEnd(JsonArray(emptyList()), willRetry = false, raw = ev("agent_end")))
        add(EngineEvent.AgentSettled(aborted = false, raw = ev("agent_settled")))
    }

    /** 一个最短回合：用户消息 + 一句回复（Fake 引擎处理排队消息时用）。 */
    fun simpleTurn(userText: String, reply: String = "收到：$userText"): List<EngineEvent> = buildList {
        add(EngineEvent.AgentStart(ev("agent_start")))
        addAll(shortReply(userText, reply, withUser = true))
        add(EngineEvent.AgentEnd(JsonArray(emptyList()), willRetry = false, raw = ev("agent_end")))
        add(EngineEvent.AgentSettled(aborted = false, raw = ev("agent_settled")))
    }

    private fun shortReply(userText: String, reply: String, withUser: Boolean): List<EngineEvent> = buildList {
        if (withUser) add(messageStart(userMessage(userText)))
        add(messageStart(assistantMessage()))
        add(update(AssistantMessageEvent.TextStart(0), "text_start", 0))
        add(update(AssistantMessageEvent.TextDelta(0, reply), "text_delta", 0, delta = reply))
        add(update(AssistantMessageEvent.TextEnd(0, reply), "text_end", 0))
        add(messageEnd(assistantMessage(reply)))
    }

    // ---- JSON 构造 ----

    private fun ev(type: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject {
            put("type", type)
            extra()
        }

    fun userMessage(text: String): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
    }

    fun assistantMessage(text: String? = null, stopReason: String? = null): JsonObject = buildJsonObject {
        put("role", "assistant")
        put(
            "content",
            buildJsonArray { if (text != null) add(buildJsonObject { put("type", "text"); put("text", text) }) },
        )
        put("provider", "fake")
        put("model", "fake-model")
        if (stopReason != null) put("stopReason", stopReason) else if (text != null) put("stopReason", "stop")
    }

    private fun toolCall(id: String, name: String, argKey: String, argValue: String): JsonObject = buildJsonObject {
        put("type", "toolCall")
        put("id", id)
        put("name", name)
        put("arguments", buildJsonObject { put(argKey, argValue) })
    }

    private fun textResult(text: String): JsonObject = buildJsonObject {
        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
    }

    private fun messageStart(message: JsonObject) =
        EngineEvent.MessageStart(message, ev("message_start") { put("message", message) })

    private fun messageEnd(message: JsonObject) =
        EngineEvent.MessageEnd(message, ev("message_end") { put("message", message) })

    private fun update(
        event: AssistantMessageEvent,
        type: String,
        contentIndex: Int? = null,
        delta: String? = null,
    ) = EngineEvent.MessageUpdate(
        message = null,
        event = event,
        usage = null,
        raw = ev("message_update") {
            put(
                "assistantMessageEvent",
                buildJsonObject {
                    put("type", type)
                    contentIndex?.let { put("contentIndex", it) }
                    delta?.let { put("delta", it) }
                },
            )
        },
    )

    private fun toolStart(id: String, name: String, argKey: String, argValue: String): EngineEvent.ToolExecutionStart {
        val args = buildJsonObject { put(argKey, argValue) }
        return EngineEvent.ToolExecutionStart(
            toolCallId = id,
            toolName = name,
            args = args,
            parentToolCallId = null,
            raw = ev("tool_execution_start") {
                put("toolCallId", id)
                put("toolName", name)
                put("args", args)
            },
        )
    }

    private fun toolEnd(id: String, text: String, isError: Boolean, durationMs: Long): EngineEvent.ToolExecutionEnd {
        val result = textResult(text)
        return EngineEvent.ToolExecutionEnd(
            toolCallId = id,
            result = result,
            isError = isError,
            durationMs = durationMs,
            raw = ev("tool_execution_end") {
                put("toolCallId", id)
                put("result", result)
                put("isError", isError)
                put("durationMs", durationMs)
            },
        )
    }

    private fun queue(steering: List<String> = emptyList(), followUp: List<String>) = EngineEvent.QueueUpdate(
        steering = steering,
        followUp = followUp,
        raw = ev("queue_update") {
            put("steering", JsonArray(steering.map(::JsonPrimitive)))
            put("followUp", JsonArray(followUp.map(::JsonPrimitive)))
        },
    )
}
