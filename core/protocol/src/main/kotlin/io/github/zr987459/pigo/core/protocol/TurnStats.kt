package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ToolStatus
import io.github.zr987459.pigo.contract.rpc.AssistantMessageEvent
import io.github.zr987459.pigo.contract.rpc.EngineEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * C10 的数据部分：一轮的耗时、输出速率、本轮写过的文件。界面（W4/W5）只读。
 *
 * - [durationMs]：从 `agent_start` 到 `agent_settled`；进行中为到 `now` 的已用时间。
 * - [outputTokens]：本轮各条助手消息 `usage.output` 之和；流式过程中 pi 还没给 usage 时按字符数估算（约 4 字符 1 token）。
 * - [tokensPerSecond]：输出速率。流式中 = 首字之后的输出 / 首字之后的时间（不足 0.5 s 为 null）；结束后 = 输出 / 各条回复的生成时长。
 * - [writtenFiles]：本轮成功的 `write`/`edit`（以及 `apply_patch`）工具调用写过的文件，按首次出现排序、去重。
 *   只认工具调用与结果，不从回复正文里猜路径。
 *
 * 移植自 Pi-Web（agegr/pi-web@025e24e，MIT）：`lib/turn-written-files.ts:82-127`、`lib/turn-duration.ts:27-64`、
 * `lib/stream-token-rate.ts:1-39`，按 pi-go 的事件流改写（Pi-Web 从消息时间戳反推，这里直接用事件到达时间）。
 */
data class TurnStats(
    val running: Boolean,
    val startedAtMillis: Long?,
    val durationMs: Long?,
    val outputTokens: Long?,
    val tokensPerSecond: Double?,
    val writtenFiles: List<String>,
) {
    companion object {
        val EMPTY = TurnStats(false, null, null, null, null, emptyList())

        /** 由 [ChatState]（写过的文件）与 [TurnClock] 的计时派生。 */
        fun derive(state: ChatState, clock: TurnClock.Snapshot, now: Long, cwd: String? = null, turnStartAfterKey: String? = null): TurnStats {
            val files = TurnFiles.written(state, cwd, turnStartAfterKey)
            val start = clock.startedAt
            val end = clock.settledAt
            val running = clock.running
            val duration = when {
                start == null -> null
                running -> (now - start).coerceAtLeast(0)
                end != null -> (end - start).coerceAtLeast(0)
                else -> null
            }
            val tokens = clock.outputTokens()
            val rate = if (running) clock.liveRate(now) else clock.finalRate()
            return TurnStats(running, start, duration, tokens.takeIf { it > 0 }, rate, files)
        }
    }
}

/**
 * 一轮的计时簿记：由归约协程按事件顺序喂入（与 ChatReducer 同线程），[snapshot] 交给界面派生 [TurnStats]。
 */
class TurnClock {
    data class Snapshot(
        val running: Boolean,
        val startedAt: Long?,
        val settledAt: Long?,
        val firstTokenAt: Long?,
        /** 已结束的助手消息 `usage.output` 之和。 */
        val finishedOutput: Long,
        /** 已结束的助手消息的生成时长之和（`durationMs`，没有时用首字到结束的时间）。 */
        val finishedGenMs: Long,
        /** 正在流式的消息：已收到的字符数与 pi 报告的 output（可能为 0）。 */
        val streamingChars: Long,
        val streamingUsageOutput: Long,
        val streamingFirstTokenAt: Long?,
    ) {
        fun outputTokens(): Long = finishedOutput + streamingTokens()

        private fun streamingTokens(): Long =
            if (streamingUsageOutput > 0) streamingUsageOutput else (streamingChars + 3) / 4

        fun liveRate(now: Long): Double? {
            val first = streamingFirstTokenAt ?: return finalRate()
            val sec = (now - first) / 1000.0
            return if (sec > 0.5) streamingTokens() / sec else null
        }

        fun finalRate(): Double? {
            if (finishedOutput <= 0 || finishedGenMs <= 0) return null
            return finishedOutput / (finishedGenMs / 1000.0)
        }
    }

    private var running = false
    private var startedAt: Long? = null
    private var settledAt: Long? = null
    private var firstTokenAt: Long? = null
    private var finishedOutput = 0L
    private var finishedGenMs = 0L
    private var streamingChars = 0L
    private var streamingUsageOutput = 0L
    private var streamingFirstTokenAt: Long? = null

    fun onEvent(e: EngineEvent, now: Long) {
        when (e) {
            is EngineEvent.AgentStart -> {
                running = true
                startedAt = now
                settledAt = null
                firstTokenAt = null
                finishedOutput = 0
                finishedGenMs = 0
                resetStreaming()
            }
            is EngineEvent.MessageStart -> if (e.message.str("role") == "assistant") resetStreaming()
            is EngineEvent.MessageUpdate -> {
                val d = when (val ev = e.event) {
                    is AssistantMessageEvent.TextDelta -> ev.delta.length
                    is AssistantMessageEvent.ThinkingDelta -> ev.delta.length
                    is AssistantMessageEvent.ToolcallDelta -> ev.delta.length
                    else -> 0
                }
                if (d > 0) {
                    if (streamingFirstTokenAt == null) streamingFirstTokenAt = now
                    if (firstTokenAt == null) firstTokenAt = now
                    streamingChars += d
                }
                val out = (e.usage ?: e.message?.obj("usage"))?.long("output") ?: 0
                if (out > 0) streamingUsageOutput = out
            }
            is EngineEvent.MessageEnd -> if (e.message.str("role") == "assistant") {
                val out = e.message.obj("usage")?.long("output") ?: 0
                finishedOutput += if (out > 0) out else (streamingChars + 3) / 4
                val dur = e.message.long("durationMs")?.takeIf { it > 0 }
                    ?: streamingFirstTokenAt?.let { (now - it).coerceAtLeast(0) }
                    ?: 0
                finishedGenMs += dur
                resetStreaming()
            }
            is EngineEvent.AgentSettled -> {
                running = false
                settledAt = now
                resetStreaming()
            }
            else -> Unit
        }
    }

    /** 进程中断 / 重建：结束计时（不再算作进行中）。 */
    fun interrupt(now: Long) {
        if (running) {
            running = false
            settledAt = now
        }
        resetStreaming()
    }

    /** 切会话、新会话：清空。 */
    fun reset() {
        running = false
        startedAt = null
        settledAt = null
        firstTokenAt = null
        finishedOutput = 0
        finishedGenMs = 0
        resetStreaming()
    }

    private fun resetStreaming() {
        streamingChars = 0
        streamingUsageOutput = 0
        streamingFirstTokenAt = null
    }

    fun snapshot() = Snapshot(
        running, startedAt, settledAt, firstTokenAt, finishedOutput, finishedGenMs,
        streamingChars, streamingUsageOutput, streamingFirstTokenAt,
    )
}

/** 本轮写过的文件（C10）。 */
object TurnFiles {
    private val WRITE_TOOLS = setOf("write", "edit", "multiedit", "multi_edit", "str_replace_editor", "create_file")
    private val PATCH_TOOLS = setOf("apply_patch", "applypatch")

    /**
     * 本轮成功的写文件工具调用的路径；[cwd] 不为空时把相对路径解析成绝对路径。
     * 本轮起点：[turnStartAfterKey]（agent_start 时时间线最后一条的 key）之后；没有或找不到时退回「最后一条用户消息之后」。
     * 以 agent_start 为界，轮中 steer 进来的用户消息之前写过的文件不会漏（审查第 1 轮 Nit）。
     */
    fun written(state: ChatState, cwd: String? = null, turnStartAfterKey: String? = null): List<String> {
        val anchor = turnStartAfterKey?.let { k -> state.items.indexOfFirst { it.key == k } } ?: -1
        val from = if (anchor >= 0) anchor + 1 else state.items.indexOfLast { it is ChatItem.User } + 1
        val turn = state.items.subList(from.coerceIn(0, state.items.size), state.items.size)
        val seen = LinkedHashSet<String>()
        for (item in turn) {
            val t = item as? ChatItem.Tool ?: continue
            if (t.status != ToolStatus.OK) continue
            val name = t.toolName.lowercase()
            val raw = when {
                name in WRITE_TOOLS -> listOfNotNull(path(t.args))
                name in PATCH_TOOLS -> patchPaths(t.args)
                else -> emptyList()
            }
            for (p in raw) resolve(p, cwd)?.let { seen += it }
        }
        return seen.toList()
    }

    private fun path(args: JsonObject?): String? =
        (args?.str("path") ?: args?.str("file_path"))?.takeIf { it.isNotEmpty() }

    /** `*** Add File:` / `*** Update File:` / `*** Move to:`；`*** Delete File:` 不算写。 */
    private fun patchPaths(args: JsonObject?): List<String> {
        val text = args?.let { a -> a.str("input") ?: a.str("patch") ?: (a["input"] as? JsonPrimitive)?.content } ?: return emptyList()
        val out = ArrayList<String>()
        val deleted = HashSet<String>()
        for (line in text.lineSequence()) {
            val l = line.trim()
            when {
                l.startsWith("*** Add File:") -> out += l.removePrefix("*** Add File:").trim()
                l.startsWith("*** Update File:") -> out += l.removePrefix("*** Update File:").trim()
                l.startsWith("*** Move to:") -> out += l.removePrefix("*** Move to:").trim()
                l.startsWith("*** Delete File:") -> deleted += l.removePrefix("*** Delete File:").trim()
            }
        }
        return out.filter { it.isNotEmpty() && it !in deleted }
    }

    private fun resolve(p: String, cwd: String?): String? {
        val path = p.trim().removePrefix("file://")
        if (path.isEmpty()) return null
        if (path.startsWith("/") || cwd.isNullOrEmpty()) return normalize(path)
        if (path.startsWith("~/")) return normalize("/root/" + path.removePrefix("~/"))
        return normalize(cwd.trimEnd('/') + "/" + path)
    }

    private fun normalize(path: String): String {
        if (!path.startsWith("/")) return path
        val parts = ArrayDeque<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(seg)
            }
        }
        return "/" + parts.joinToString("/")
    }
}

/** 时长显示：14s、3m 5s、1h 2m 5s（移植自 Pi-Web `lib/turn-duration.ts` 的 formatTurnDuration）。 */
fun formatTurnDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    if (total < 60) return "${total}s"
    val m = total / 60
    val s = total % 60
    if (m < 60) return "${m}m ${s}s"
    return "${m / 60}h ${m % 60}m ${s}s"
}
