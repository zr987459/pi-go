package io.github.zr987459.pigo.feature.usage.logic

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ToolStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 轮末汇总（C10）的展示数据：本轮改动的文件、耗时、输出速率。
 *
 * 改动文件只认**成功完成**的 `write` / `edit` 工具调用（不从回复正文里猜路径）。
 * 思路移植自 agegr/pi-web@025e24e `lib/turn-written-files.ts:83-127`、`lib/turn-duration.ts:56-64`，MIT。
 * 耗时与速率由引擎（W1）给出时直接显示；这里只负责格式化。
 */
data class TurnSummaryView(
    val writtenFiles: List<String>,
    val durationText: String?,
    val rateText: String?,
) {
    val isEmpty: Boolean get() = writtenFiles.isEmpty() && durationText == null && rateText == null
}

@Deprecated("r3: r3/app 删除（DESIGN-r3 X3），新代码不得引用")
object TurnSummary {
    private val WRITE_TOOLS = setOf("write", "edit")

    /** 从 [items] 里取最后一轮（最后一条用户消息之后）的改动文件，按首次出现排序、去重，相对路径按 [cwd] 补全。 */
    fun writtenFilesOfLastTurn(items: List<ChatItem>, cwd: String?): List<String> {
        val start = items.indexOfLast { it is ChatItem.User }.let { if (it < 0) 0 else it + 1 }
        return writtenFiles(items.subList(start, items.size), cwd)
    }

    fun writtenFiles(items: List<ChatItem>, cwd: String?): List<String> {
        val seen = LinkedHashSet<String>()
        for (it in items) {
            if (it !is ChatItem.Tool || it.toolName !in WRITE_TOOLS || it.status != ToolStatus.OK) continue
            val raw = pathOf(it.args) ?: continue
            seen += resolve(raw, cwd)
        }
        return seen.toList()
    }

    fun build(items: List<ChatItem>, cwd: String?, durationMs: Long?, tokensPerSecond: Double?): TurnSummaryView =
        TurnSummaryView(
            writtenFiles = writtenFilesOfLastTurn(items, cwd),
            durationText = durationMs?.let(::formatDuration),
            rateText = tokensPerSecond?.takeIf { it.isFinite() && it > 0 }?.let { "%.1f tok/s".format(it) },
        )

    /** 14s → 3m 5s → 1h 2m 5s。 */
    fun formatDuration(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        if (total < 60) return "${total}s"
        val m = total / 60
        val s = total % 60
        if (m < 60) return "${m}m ${s}s"
        return "${m / 60}h ${m % 60}m ${s}s"
    }

    fun pathOf(args: JsonObject?): String? {
        if (args == null) return null
        val v = (args["path"] ?: args["file_path"]) as? JsonPrimitive ?: return null
        return v.contentOrNull?.takeIf { it.isNotBlank() }
    }

    fun resolve(path: String, cwd: String?): String {
        if (path.startsWith("/") || cwd.isNullOrBlank()) return normalize(path)
        val p = path.removePrefix("./")
        return normalize(cwd.trimEnd('/') + "/" + p)
    }

    private fun normalize(p: String): String {
        val parts = ArrayList<String>()
        for (seg in p.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += seg
            }
        }
        return "/" + parts.joinToString("/")
    }
}

/** `edit` 工具卡的 diff 数据（C11）。 */
@Deprecated("r3: r3/app 删除（DESIGN-r3 X3），新代码不得引用")
object EditToolDiff {
    /** 不是 edit 工具，或参数和结果都给不出 diff 时返回 null。 */
    fun of(tool: ChatItem.Tool): List<DiffLine>? {
        if (tool.toolName != "edit") return null
        patchOf(tool.result)?.let { p -> LineDiff.parseUnified(p).takeIf { it.isNotEmpty() }?.let { return it } }
        val edits = editsOf(tool.args) ?: return null
        if (edits.isEmpty()) return null
        return LineDiff.diffEdits(edits)
    }

    fun editsOf(args: JsonObject?): List<Pair<String, String>>? {
        val arr = args?.get("edits") as? JsonArray ?: return null
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val old = (o["oldText"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val new = (o["newText"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            old to new
        }
    }

    /** 结果可能是工具结果消息（含 `details`），也可能直接是 details 对象。 */
    fun patchOf(result: JsonElement?): String? {
        val o = result as? JsonObject ?: return null
        val details = (o["details"] as? JsonObject) ?: o
        return (details["patch"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
