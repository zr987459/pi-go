package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.rpc.EngineEvent
import io.github.zr987459.pigo.contract.rpc.RpcRecord
import io.github.zr987459.pigo.contract.rpc.RpcResponse
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/** 夹具读取与快照比对（docs/protocol/fixtures.md）。 */
object Fixtures {
    /** 夹具一行：pi 的 stdout 记录，或客户端命令（以 `//> ` 开头的注释行）。 */
    sealed interface Line {
        data class Out(val raw: String, val record: RpcRecord) : Line
        data class Cmd(val json: JsonObject) : Line
    }

    fun load(name: String): List<Line> {
        val text = Fixtures::class.java.getResourceAsStream("/fixtures/$name.jsonl")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("缺少夹具 fixtures/$name.jsonl")
        // 只按 LF 切
        return text.split('\n').map { it.removeSuffix("\r") }.filter { it.isNotBlank() }.mapNotNull { l ->
            when {
                // 客户端命令行可能故意是坏 JSON（例如 protocol-errors 的 `not json`），这种只作注释
                l.startsWith("//> ") -> (runCatching { RecordCodec.json.parseToJsonElement(l.removePrefix("//> ").trim()) }.getOrNull() as? JsonObject)?.let { Line.Cmd(it) }
                l.startsWith("//") -> null
                else -> when (val d = RecordCodec.decode(l)) {
                    is RecordCodec.Decoded.Record -> Line.Out(l, d.record)
                    is RecordCodec.Decoded.Malformed -> error("夹具 $name 有坏行：$l")
                }
            }
        }
    }

    fun events(lines: List<Line>): List<EngineEvent> = lines.mapNotNull { (it as? Line.Out)?.record as? EngineEvent }

    fun responses(lines: List<Line>): List<RpcResponse> = lines.mapNotNull { (it as? Line.Out)?.record as? RpcResponse }

    /** 回放：每条记录推进 100 ms 的假时钟；返回最终状态与状态变化轨迹。 */
    fun replay(events: List<EngineEvent>, reducer: ChatReducer = ChatReducer(), start: ChatState = ChatState()): Pair<ChatState, List<String>> {
        var s = start
        var now = 1_000_000L
        val trace = mutableListOf<String>()
        events.forEachIndexed { i, e ->
            now += 100
            val before = s
            s = reducer.reduce(s, e, now)
            val type = (e.raw["type"] as? JsonPrimitive)?.content ?: "?"
            if (before.running != s.running) trace += "#$i $type running=${s.running}"
            if (before.queue != s.queue) trace += "#$i $type queue=steer${s.queue.steering.map(::q)} followUp${s.queue.followUp.map(::q)}"
            if (before.retry != s.retry) trace += "#$i $type retry=${s.retry?.let { "${it.attempt}/${it.maxAttempts} ${it.delayMs}ms ${q(it.errorMessage)}" }}"
            if (before.statusLine != s.statusLine) trace += "#$i $type status=${q(s.statusLine)}"
            if (before.sessionName != s.sessionName) trace += "#$i $type name=${q(s.sessionName)}"
            if (before.thinkingLevel != s.thinkingLevel) trace += "#$i $type thinking=${s.thinkingLevel}"
        }
        return s to trace
    }

    fun render(state: ChatState, trace: List<String> = emptyList()): String = buildString {
        appendLine("running=${state.running} queue=steer${state.queue.steering.map(::q)} followUp${state.queue.followUp.map(::q)} retry=${state.retry}")
        appendLine("session=${q(state.sessionName)} model=${state.model?.let { it.provider + "/" + it.id }} thinking=${state.thinkingLevel} status=${q(state.statusLine)}")
        if (trace.isNotEmpty()) {
            appendLine("-- transitions")
            trace.forEach { appendLine("  $it") }
        }
        appendLine("-- items")
        for (it in state.items) appendLine(renderItem(it))
    }

    /** [withKeys] 为 false 时去掉 key 与实测耗时，用于比较「实时」与「重建」两条路径。 */
    fun renderItem(item: ChatItem, withKeys: Boolean = true): String {
        val k = if (withKeys) "[${item.key}] " else ""
        return when (item) {
            is ChatItem.User -> "${k}USER ${item.delivery} ${q(item.text)}"
            is ChatItem.Assistant -> buildString {
                append("${k}ASSISTANT streaming=${item.streaming} stop=${item.stopReason} error=${q(item.errorMessage)}")
                for (b in item.blocks) {
                    append("\n    #${b.index} ")
                    append(
                        when (b) {
                            is ContentBlock.Text -> "TEXT ${q(b.text)}"
                            is ContentBlock.Thinking -> "THINKING ${if (withKeys) b.durationMs?.let { "${it}ms " } ?: "" else ""}${q(b.text)}"
                            is ContentBlock.ToolCall -> "TOOLCALL ${b.id} ${b.name} ${b.argsJson}"
                        },
                    )
                }
            }
            is ChatItem.Tool -> "${k}TOOL ${item.toolName} ${item.toolCallId} ${item.status} args=${item.args} " +
                (if (withKeys) "partial=${resultText(item.partialResult)} " else "") +
                "result=${resultText(item.result)}" + (if (withKeys) " dur=${item.durationMs?.let { "set" }}" else "")
            is ChatItem.Notice -> "${k}NOTICE ${item.kind} ${q(item.text)}"
            is ChatItem.Raw -> "${k}RAW role=${item.role} ${item.json}"
            is ChatItem.Compaction -> "${k}COMPACTION ${item.phase} reason=${item.reason} before=${item.tokensBefore} after=${item.tokensAfter} " +
                "summary=${q(item.summary)} read=${item.readFiles} modified=${item.modifiedFiles} error=${q(item.errorMessage)}"
            is ChatItem.Bash -> "${k}BASH id=${item.bashId} ${q(item.command)} running=${item.running} exit=${item.exitCode} " +
                "cancelled=${item.cancelled} truncated=${item.truncated} exclude=${item.excludeFromContext} output=${q(item.output)}"
        }
    }

    /** 工具结果里的文本（截断），其余结构不进快照。 */
    private fun resultText(e: JsonElement?): String {
        if (e == null) return "null"
        val content = (e as? JsonObject)?.get("content") as? JsonArray ?: return q(e.toString().take(120))
        return q(content.joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content ?: "" }.take(160))
    }

    /** 带转义的引号字符串：U+2028 等不可见字符显示为 \\uXXXX。 */
    fun q(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 || c == '\u2028' || c == '\u2029' || c == '\u0085' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** 与 `src/test/resources/snapshots/<name>.txt` 比对；环境变量 `UPDATE_SNAPSHOTS=1` 时改写快照（需人工检查）。 */
    fun assertSnapshot(name: String, actual: String) {
        if (System.getenv("UPDATE_SNAPSHOTS") == "1") {
            val f = File("src/test/resources/snapshots/$name.txt")
            f.parentFile.mkdirs()
            f.writeText(actual)
            return
        }
        val expected = Fixtures::class.java.getResourceAsStream("/snapshots/$name.txt")?.readBytes()?.toString(Charsets.UTF_8)
        if (expected == null) {
            fail("缺少快照 snapshots/$name.txt（用 UPDATE_SNAPSHOTS=1 生成并人工检查）")
            return
        }
        assertEquals("快照 $name 不一致", expected, actual)
    }
}
