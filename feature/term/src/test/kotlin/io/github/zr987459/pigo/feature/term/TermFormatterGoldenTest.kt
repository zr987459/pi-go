package io.github.zr987459.pigo.feature.term

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.ToolStatus
import io.github.zr987459.pigo.feature.term.format.FormatOptions
import io.github.zr987459.pigo.feature.term.format.TermFormatter
import io.github.zr987459.pigo.feature.term.text.CellWidth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESIGN-r3 §7.3 TermFormatterGoldenTest：与真 pi 1.5.0 在 80 列下的输出逐行比对，差异 ≤ 5%。
 * 夹具 `pi/term-format-golden.json` 由 node 直接调用 pi 包里的 `UserMessageComponent`、`AssistantMessageComponent`、
 * `ToolExecutionComponent`（内置工具定义 + 默认键位）渲染并去掉 ANSI 生成；覆盖用户消息、Markdown（标题、中英混排折行、
 * 有序 / 无序列表、引用、代码块、分隔线、链接、行内代码）、思考块显示 / 隐藏、错误、中止、read / bash / write / grep / ls 的折叠、展开与截断提示。
 */
class TermFormatterGoldenTest {
    private val cases = Json.parseToJsonElement(javaClass.getResource("/pi/term-format-golden.json")!!.readText()).jsonArray.map { it.jsonObject }
    private val long30 = (1..30).joinToString("\n") { "line $it" }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content

    private fun item(c: JsonObject): Pair<ChatItem, FormatOptions> {
        var o = FormatOptions(width = 80, cwd = "/root/proj")
        val it: ChatItem = when (c.s("type")) {
            "user" -> ChatItem.User("u", c.s("text")!!)
            "assistant" -> {
                o = o.copy(hideThinking = c["hideThinking"]?.jsonPrimitive?.booleanOrNull == true)
                val blocks = c["blocks"]!!.jsonArray.mapIndexed { i, b ->
                    val bo = b.jsonObject
                    if (bo.s("type") == "thinking") ContentBlock.Thinking(i, bo.s("text")!!) else ContentBlock.Text(i, bo.s("text")!!)
                }
                ChatItem.Assistant("a", blocks, streaming = false, stopReason = c.s("stopReason"), errorMessage = c.s("errorMessage"))
            }
            else -> {
                val res = c.s("result")?.let { if (it == "LONG30") long30 else it }
                val err = c["isError"]?.jsonPrimitive?.booleanOrNull == true
                if (c["expanded"]?.jsonPrimitive?.booleanOrNull == true) o = o.copy(toolsExpanded = true)
                ChatItem.Tool(
                    key = "t", toolCallId = "t1", toolName = c.s("tool")!!, args = c["args"]!!.jsonObject,
                    status = if (res == null) ToolStatus.RUNNING else if (err) ToolStatus.ERROR else ToolStatus.OK,
                    result = res?.let { r -> buildJsonObject { putJsonArray("content") { addJsonObject { put("type", "text"); put("text", r) } }; put("isError", err) } },
                    durationMs = 0,
                )
            }
        }
        return it to o
    }

    @Test
    fun matchesPiAt80Columns() {
        var total = 0
        var diff = 0
        val report = StringBuilder()
        for (c in cases) {
            val want = c["lines"]!!.jsonArray.map { it.jsonPrimitive.content.trimEnd() }
            val (item, o) = item(c)
            val got = TermFormatter.format(item, o).map { it.text.trimEnd() }
            got.forEach { assertTrue("${c.s("name")} 超宽：$it", CellWidth.width(it) <= 80) }
            val n = maxOf(want.size, got.size)
            total += n
            for (i in 0 until n) {
                if (want.getOrNull(i) != got.getOrNull(i)) {
                    diff++
                    report.append("${c.s("name")}[$i]\n  pi : ${want.getOrNull(i)}\n  我们: ${got.getOrNull(i)}\n")
                }
            }
        }
        println("TermFormatterGoldenTest: $diff/$total 行不同\n$report")
        assertTrue("差异 $diff/$total 超过 5%：\n$report", diff * 100 <= total * 5)
    }

    @Test
    fun toolStatesUseThreeBackgrounds() {
        fun bg(s: ToolStatus) = TermFormatter.format(ChatItem.Tool("k", "id", "ls", JsonObject(emptyMap()), status = s), FormatOptions()).mapNotNull { it.bg }.distinct()
        assertEquals(listOf("toolPendingBg"), bg(ToolStatus.RUNNING))
        assertEquals(listOf("toolSuccessBg"), bg(ToolStatus.OK))
        assertEquals(listOf("toolErrorBg"), bg(ToolStatus.ERROR))
    }

    @Test
    fun editDiffUsesThreeColors() {
        val r = buildJsonObject { putJsonArray("content") { add(JsonObject(emptyMap())) }; put("details", buildJsonObject { put("diff", "-val a = 1\n+val a = 10\n val b = 2") }) }
        val lines = TermFormatter.format(ChatItem.Tool("k", "id", "edit", buildJsonObject { put("path", "a.kt") }, ToolStatus.OK, result = r), FormatOptions())
        val toks = lines.flatMap { it.spans }.mapNotNull { it.token }
        assertTrue(toks.containsAll(listOf("toolDiffRemoved", "toolDiffAdded", "toolDiffContext")))
    }

    @Test
    fun cjkWrapsByCells() {
        val lines = TermFormatter.format(ChatItem.User("u", "中".repeat(100)), FormatOptions(width = 40))
        lines.forEach { assertTrue(CellWidth.width(it.text) <= 40) }
        assertEquals(19, lines[1].text.trim().length)
    }
}
