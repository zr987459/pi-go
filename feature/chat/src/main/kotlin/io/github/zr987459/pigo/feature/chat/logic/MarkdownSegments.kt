package io.github.zr987459.pigo.feature.chat.logic

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 流式 Markdown 的分段：在代码块之外的空行处切开。已经完整的段落内容不再变化，
 * Compose 会跳过它们的重组，每个 delta 只需要重新解析最后一段（§3.5 性能、S1/S2）。
 */
object MarkdownSegments {

    fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n')
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inFence = false
        var fenceMarker = ""
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (!inFence && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
                inFence = true
                fenceMarker = trimmed.take(3)
            } else if (inFence && trimmed.startsWith(fenceMarker)) {
                inFence = false
            }
            if (!inFence && line.isBlank() && cur.isNotEmpty()) {
                // 下一条非空行如果是缩进的续行（列表项里的段落），不切，避免被当成缩进代码块。
                var j = i + 1
                while (j < lines.size && lines[j].isBlank()) j++
                val nextIndented = j < lines.size && (lines[j].startsWith(" ") || lines[j].startsWith("\t"))
                if (!nextIndented) {
                    out += cur.toString()
                    cur.setLength(0)
                    i = j
                    continue
                }
            }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(line)
            i++
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}

/** 从工具结果 / partialResult 中取出给人看的文本（`content[].text`），取不到时显示原始 JSON。 */
object ToolText {
    /** 回合结束但工具没走到终态时，卡片上代替耗时显示的文字。 */
    fun phaseLabel(phase: ToolPhase): String? = when (phase) {
        ToolPhase.NOT_RUN -> "未执行"
        ToolPhase.INTERRUPTED -> "已中断"
        else -> null
    }

    fun of(e: JsonElement?): String? = when (e) {
        null, JsonNull -> null
        is JsonPrimitive -> e.contentOrNull
        is JsonArray -> e.mapNotNull { of(it) }.joinToString("\n").ifEmpty { null }
        is JsonObject -> {
            val content = e["content"]
            when {
                content is JsonArray -> content.filterIsInstance<JsonObject>()
                    .mapNotNull { c ->
                        when ((c["type"] as? JsonPrimitive)?.contentOrNull) {
                            "text" -> (c["text"] as? JsonPrimitive)?.contentOrNull
                            "image" -> "[图片]"
                            else -> null
                        }
                    }
                    .joinToString("\n")
                    .ifEmpty { e.toString() }
                content is JsonPrimitive -> content.contentOrNull
                e["text"] is JsonPrimitive -> (e["text"] as JsonPrimitive).contentOrNull
                e["output"] is JsonPrimitive -> (e["output"] as JsonPrimitive).contentOrNull
                else -> e.toString()
            }
        }
    }

    /** 卡片标题上的一句话参数：bash 显示命令，读写类显示路径。 */
    fun argSummary(tool: ChatItem.Tool): String? {
        val a = tool.args ?: return null
        fun s(k: String) = (a[k] as? JsonPrimitive)?.contentOrNull
        return s("command") ?: s("path") ?: s("file_path") ?: s("pattern") ?: s("url") ?: s("query")
    }

    /** 显示名：内置工具用中文说明。 */
    fun label(name: String): String = when (name) {
        "bash" -> "运行命令"
        "read" -> "读取文件"
        "write" -> "写入文件"
        "edit" -> "修改文件"
        "ls" -> "列出目录"
        "grep" -> "搜索内容"
        "find" -> "查找文件"
        else -> "工具"
    }

    /** 过长输出只保留末尾，避免一次性排版几十万字卡住界面。 */
    fun clip(text: String, max: Int = 8_000): String =
        if (text.length <= max) text else "…（前面省略 ${text.length - max} 字）\n" + text.takeLast(max)
}

/** 助手消息正文（所有 text 块）。 */
fun ChatItem.Assistant.text(): String = blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n\n") { it.text }
