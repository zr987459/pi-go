package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.MessageMeta
import io.github.zr987459.pigo.contract.ModelRef
import io.github.zr987459.pigo.contract.TokenUsage
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * `EngineExtras.messageMeta` 的簿记（S1、U1）：把时间线条目（[ChatItem.key]）与 pi 会话文件里的 message entry 对上。
 *
 * pi 1.5.0 的真实事件序列（agent-session.js:766-781）：user / assistant / toolResult 在 `message_end` 时经 `appendMessage` 写盘，
 * **不发 `entry_appended`**；只有 custom / context_edit / compaction 等才发。所以：
 * - 数据源只有 `get_entries`：打开 / 重建时全量（[reset]），之后按 `since=`[lastId] 增量（[merge]）。不靠 `entry_appended` 拼接。
 * - 每份快照带 [covered]：调用方在**发请求前**登记的、已收到 `message_end`（已写盘）的时间线 key。
 *   请求期间又有新的 message_end 时调用方丢弃这份快照再拉一次，所以快照里的 message entry 与 covered 一一对应。
 * - 只看**当前分支**：从 leafId 沿 parentId 走到根（链断时只用已知的那一段，即叶子一侧）。
 * - `context_edit`（replacement 为 null）把目标条目从投影里去掉（自动重试时的失败助手消息，agent-session.js:3125-3127），不参与配对；
 *   时间线上对应的「已重试的失败助手消息」也不参与配对。
 * - 配对规则：
 *   - `toolResult` 按 `toolCallId` 对到 `tool-<id>` 卡；
 *   - `user`、`assistant` 各自**从末尾往前**配对，只用 covered 里的条目：压缩后头部对不齐，尾部对齐；
 *     还没写盘的尾部气泡（不在 covered 里）不参与，不会错配到上一条。
 * 不按文本匹配（同一句话可能出现多次）。
 */
class MessageMetaTracker {
    private class Msg(val id: String, val parentId: String?, val type: String?, val role: String?, val message: JsonObject?, val raw: JsonObject)

    private val byId = LinkedHashMap<String, Msg>()
    private var leafId: String? = null
    private var covered: Set<String> = emptySet()

    /** 当前 entry 的版本号：每次 [reset]/[merge]/[clear] 递增，调用方据此判断是否需要重新配对。 */
    var version = 0L
        private set

    /** 文件顺序里最后一条 entry 的 id，用作下一次增量 `get_entries(since=…)`。没有快照时为 null（应全量拉）。 */
    val lastId: String? get() = byId.keys.lastOrNull()

    val isEmpty: Boolean get() = byId.isEmpty()

    /** `get_entries` 全量结果（不带 since）。[entries] 为原始 SessionEntry JSON。 */
    fun reset(entries: List<JsonObject>, leafId: String?, covered: Set<String>) {
        byId.clear()
        entries.forEach { add(it) }
        this.leafId = leafId ?: entries.lastOrNull()?.str("id")
        this.covered = covered
        version++
    }

    /** `get_entries(since=lastId)` 的增量结果：追加新条目，叶子取响应里的 leafId（分支切换后也对）。 */
    fun merge(entries: List<JsonObject>, leafId: String?, covered: Set<String>) {
        entries.forEach { add(it) }
        this.leafId = leafId ?: entries.lastOrNull()?.str("id") ?: this.leafId
        this.covered = covered
        version++
    }

    fun clear() {
        byId.clear()
        leafId = null
        covered = emptySet()
        version++
    }

    private fun add(o: JsonObject): String? {
        val id = o.str("id") ?: return null
        val type = o.str("type")
        val message = if (type == "message") o.obj("message") else null
        byId[id] = Msg(id, o.str("parentId"), type, message?.str("role") ?: type, message, o)
        return id
    }

    /** 当前分支上的条目，按根 → 叶顺序。防环；parentId 指向未知条目时停在那里（只用已知的叶子一侧）。 */
    private fun branch(): List<Msg> {
        val out = ArrayList<Msg>()
        val seen = HashSet<String>()
        var cur = leafId?.let { byId[it] }
        while (cur != null && seen.add(cur.id)) {
            out += cur
            cur = cur.parentId?.let { byId[it] }
        }
        out.reverse()
        return out
    }

    /**
     * @param notPersisted 不会写入会话的用户气泡（扩展消费掉的输入等）
     * @param retrying 正在自动重试（重试横幅还在）：时间线最后一条失败的助手消息已被 pi 从投影排除
     * @param running 这一轮还在进行：最后一条 error/length 条目可能马上被 context_edit 排除，暂不配对
     */
    fun compute(
        items: List<ChatItem>,
        notPersisted: Set<String> = emptySet(),
        retrying: Boolean = false,
        running: Boolean = false,
    ): Map<String, MessageMeta> {
        val branch = branch()
        val omitted = branch.asSequence()
            .filter { it.type == "context_edit" && it.raw["replacement"].let { r -> r == null || r is JsonNull } }
            .mapNotNullTo(HashSet()) { it.raw.str("targetId") }
        // path 保留被 context_edit 排除的条目（标 excluded），尾部配对时与失败气泡一起跳过，而不是从序列里消失后让两边错位。
        val path = branch.filter { it.message != null }
        if (path.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MessageMeta>()

        // toolResult：按 toolCallId（有条目才配，与顺序无关）。
        val toolKeys = items.mapNotNullTo(HashSet()) { (it as? ChatItem.Tool)?.key }
        for (m in path) {
            if (m.role != "toolResult" || m.id in omitted) continue
            val callId = m.message?.str("toolCallId") ?: continue
            val key = ChatReducer.toolKey(callId)
            if (key in toolKeys) out[key] = meta(m)
        }

        val users = path.filter { it.role == "user" && it.id !in omitted }
        run {
            val timeline = items.filter { it is ChatItem.User && it.delivery == Delivery.SENT && it.key !in notPersisted && it.key in covered }
            var i = timeline.size - 1
            var j = users.size - 1
            while (i >= 0 && j >= 0) out[timeline[i--].key] = meta(users[j--])
        }
        val retried = retriedFailures(items, retrying)
        pairAssistants(
            items.filter { it is ChatItem.Assistant && !it.streaming && it.key in covered && it.key !in retried }.map { it as ChatItem.Assistant },
            path.filter { it.role == "assistant" },
            omitted,
            running,
            out,
        )
        return out
    }

    /**
     * 助手消息从末尾往前配对，被 pi 排除的条目由 tracker 自己对齐（不靠时间线猜）：
     * - 条目被 context_edit(replacement=null) 排除（自动重试 / 重试中停止 / 放弃重试的 `error`，截断恢复 `_omitRecoveryAttempt` 的 `length`）：
     *   气泡的 stopReason 与条目相同且属于 {error, length} 时两边一起跳过、不发 meta；否则（空失败消息已被时间线删掉、重建后没有它）只跳条目。
     * - 条目未被排除、stopReason ∈ {error, length}，且这一轮还在进行（[running]）：可能马上被排除（扩展 turn_end/agent_end 异步 await、
     *   auto_retry_start 之前的瞬时窗口），暂按上一条处理，不发 meta。
     * - 其余按正常规则配对：不会重试的错误（401、重试次数用尽）和普通截断留在上下文里，两边都是 error/length 时照常配对。
     * - 气泡是 error、条目不是：这条气泡没有对应条目，只跳气泡。
     */
    private fun pairAssistants(
        timeline: List<ChatItem.Assistant>,
        entries: List<Msg>,
        omitted: Set<String>,
        running: Boolean,
        out: MutableMap<String, MessageMeta>,
    ) {
        var i = timeline.size - 1
        var j = entries.size - 1
        while (i >= 0 && j >= 0) {
            val bubble = timeline[i]
            val entry = entries[j]
            val entryStop = entry.message?.str("stopReason")
            val recoverable = entryStop == "error" || entryStop == "length"
            val pendingOmit = running && j == entries.size - 1 && recoverable
            when {
                entry.id in omitted || pendingOmit ->
                    if (recoverable && bubble.stopReason == entryStop) { i--; j-- } else j--
                bubble.stopReason == "error" && entryStop != "error" -> i--
                else -> { out[bubble.key] = meta(entry); i--; j-- }
            }
        }
    }

    /** 时间线上已被自动重试取代的失败助手消息：其后、下一条用户消息之前还有助手消息；或正在重试时的最后一条失败消息。 */
    private fun retriedFailures(items: List<ChatItem>, retrying: Boolean): Set<String> {
        val out = HashSet<String>()
        var laterAssistant = false
        var lastSeen = true
        for (it in items.asReversed()) {
            when (it) {
                is ChatItem.User -> { laterAssistant = false; lastSeen = false }
                is ChatItem.Assistant -> {
                    if (it.stopReason == "error" && (laterAssistant || (retrying && lastSeen))) out += it.key
                    laterAssistant = true
                    lastSeen = false
                }
                else -> Unit
            }
        }
        return out
    }

    private fun meta(m: Msg): MessageMeta {
        val msg = m.message
        val usage = msg?.obj("usage")
        val tokens = usage?.let {
            TokenUsage(
                input = it.long("input") ?: 0,
                output = it.long("output") ?: 0,
                cacheRead = it.long("cacheRead") ?: 0,
                cacheWrite = it.long("cacheWrite") ?: 0,
                total = it.long("totalTokens") ?: ((it.long("input") ?: 0) + (it.long("output") ?: 0) + (it.long("cacheRead") ?: 0) + (it.long("cacheWrite") ?: 0)),
            )
        }
        val provider = msg?.str("provider")
        val model = msg?.str("model")
        return MessageMeta(
            entryId = m.id,
            role = m.role,
            usage = if (m.role == "assistant") tokens else null,
            costUsd = if (m.role == "assistant") usage?.obj("cost")?.double("total") else null,
            model = if (m.role == "assistant" && provider != null && model != null) ModelRef(provider, model) else null,
        )
    }
}
