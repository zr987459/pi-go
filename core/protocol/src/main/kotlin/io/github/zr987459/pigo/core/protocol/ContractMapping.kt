package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.BashResult
import io.github.zr987459.pigo.contract.CommandSource
import io.github.zr987459.pigo.contract.CompactResult
import io.github.zr987459.pigo.contract.ContextUsage
import io.github.zr987459.pigo.contract.CycleModelResult
import io.github.zr987459.pigo.contract.EntriesPage
import io.github.zr987459.pigo.contract.ForkPoint
import io.github.zr987459.pigo.contract.ForkResult
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.QueueMode
import io.github.zr987459.pigo.contract.SessionEntry
import io.github.zr987459.pigo.contract.SessionSettings
import io.github.zr987459.pigo.contract.SessionTree
import io.github.zr987459.pigo.contract.SlashCommand
import io.github.zr987459.pigo.contract.TokenUsage
import io.github.zr987459.pigo.contract.TreeNode
import io.github.zr987459.pigo.contract.UsageStats
import io.github.zr987459.pigo.contract.rpc.BashResultRecord
import io.github.zr987459.pigo.contract.rpc.CompactionResultRecord
import io.github.zr987459.pigo.contract.rpc.ForkMessageRecord
import io.github.zr987459.pigo.contract.rpc.ForkResultRecord
import io.github.zr987459.pigo.contract.rpc.RpcImage
import io.github.zr987459.pigo.contract.rpc.SessionEntriesRecord
import io.github.zr987459.pigo.contract.rpc.SessionEntryRecord
import io.github.zr987459.pigo.contract.rpc.SessionStatsRecord
import io.github.zr987459.pigo.contract.rpc.SessionTreeNodeRecord
import io.github.zr987459.pigo.contract.rpc.SessionTreeRecord
import io.github.zr987459.pigo.contract.rpc.SlashCommandRecord
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** `rpc/RpcRecords.kt` 的记录类型 → W0 契约（`EngineExtras.kt`）的数据类。纯函数，不抛异常。 */
object ContractMapping {

    fun command(r: SlashCommandRecord) = SlashCommand(
        name = r.name,
        description = r.description,
        source = when (r.source) {
            "extension" -> CommandSource.EXTENSION
            "prompt" -> CommandSource.PROMPT
            "skill" -> CommandSource.SKILL
            else -> CommandSource.UNKNOWN
        },
        path = r.path,
        scope = r.scope,
        origin = r.raw.obj("sourceInfo")?.str("origin"),
        raw = r.raw,
    )

    fun forkPoint(r: ForkMessageRecord) = ForkPoint(r.entryId, r.text)

    fun fork(r: ForkResultRecord) = ForkResult(r.text.takeIf { it.isNotEmpty() }, r.cancelled)

    fun entry(r: SessionEntryRecord) = SessionEntry(r.id, r.parentId, r.type, r.timestamp, r.role, r.preview, r.raw)

    fun entries(r: SessionEntriesRecord) = EntriesPage(r.entries.map(::entry), r.leafId)

    /** 树很深时（线性长会话）用显式栈转换，不递归。 */
    fun tree(r: SessionTreeRecord): SessionTree = SessionTree(r.roots.map(::node), r.leafId)

    private fun node(root: SessionTreeNodeRecord): TreeNode {
        // 后序遍历：先转换孩子再组装父节点。
        val done = java.util.IdentityHashMap<SessionTreeNodeRecord, TreeNode>()
        val stack = ArrayDeque<Pair<SessionTreeNodeRecord, Boolean>>()
        stack.addLast(root to false)
        while (stack.isNotEmpty()) {
            val (n, expanded) = stack.removeLast()
            if (expanded || n.children.isEmpty()) {
                done[n] = TreeNode(entry(n.entry), n.children.map { done.getValue(it) }, n.label)
            } else {
                stack.addLast(n to true)
                n.children.forEach { stack.addLast(it to false) }
            }
        }
        return done.getValue(root)
    }

    fun usage(r: SessionStatsRecord) = UsageStats(
        sessionFile = r.sessionFile,
        sessionId = r.sessionId,
        userMessages = r.userMessages,
        assistantMessages = r.assistantMessages,
        toolCalls = r.toolCalls,
        toolResults = r.toolResults,
        totalMessages = r.totalMessages,
        tokens = TokenUsage(r.tokens.input, r.tokens.output, r.tokens.cacheRead, r.tokens.cacheWrite, r.tokens.total),
        costUsd = r.raw["cost"]?.let { r.cost },
        // percent 为 null（压缩后、下一次回复前）原样保留，绝不回退旧值（U2）。
        context = r.contextUsage?.let { ContextUsage(it.tokens, it.contextWindow, it.percent) },
        raw = r.raw,
    )

    fun compact(r: CompactionResultRecord) = CompactResult(
        summary = r.summary,
        firstKeptEntryId = r.firstKeptEntryId,
        tokensBefore = r.tokensBefore,
        estimatedTokensAfter = r.estimatedTokensAfter,
        raw = r.raw,
    )

    fun bash(r: BashResultRecord) = BashResult(r.output, r.exitCode, r.cancelled, r.truncated, r.fullOutputPath)

    fun image(i: ImageInput) = RpcImage(i.base64Data, i.mimeType)

    /** `cycle_model` → `{model, thinkingLevel, isScoped}`；`data` 为 null（没有可切换的模型）→ null。 */
    fun cycleModel(data: JsonElement?): CycleModelResult? {
        val o = data as? JsonObject ?: return null
        val model = Responses.model(o["model"]) ?: return null
        return CycleModelResult(model, o.str("thinkingLevel"), o.bool("isScoped") ?: false)
    }

    /** `cycle_thinking_level` → `{level}`；`data` 为 null（模型不支持思考）→ null。 */
    fun thinkingLevel(data: JsonElement?): String? = (data as? JsonObject)?.str("level")

    /** `get_state` 中的运行设置；autoRetry 1.5.0 不给，由调用方补（最近一次 set_auto_retry 的值）。 */
    fun settings(state: JsonElement?, autoRetry: Boolean?): SessionSettings {
        val o = state as? JsonObject ?: return SessionSettings(autoRetry = autoRetry)
        return SessionSettings(
            autoCompaction = o.bool("autoCompactionEnabled"),
            autoRetry = autoRetry,
            steeringMode = QueueMode.fromWire(o.str("steeringMode")),
            followUpMode = QueueMode.fromWire(o.str("followUpMode")),
        )
    }
}
