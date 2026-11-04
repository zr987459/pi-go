package io.github.zr987459.pigo.feature.term

import io.github.zr987459.pigo.feature.term.format.Footer
import io.github.zr987459.pigo.feature.term.format.FooterInput
import io.github.zr987459.pigo.feature.term.format.plain
import io.github.zr987459.pigo.feature.term.text.CellWidth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESIGN-r3 §7.3 FooterSnapshotTest：同一组输入下，页脚与 pi 1.5.0 `footer.js` 的 `render(width)` 逐行一致（去掉 ANSI 后）。
 * 夹具 `pi/footer-golden.json` 由 node 直接调用 pi 包里的 `FooterComponent` 生成，覆盖 120 / 100 / 80 / 60 / 48 / 40 / 30 列，
 * 含窄屏截断顺序（先丢 provider、再截模型名、最后截统计）、订阅 `(sub)`、上下文 >70% / >90% 着色阈值、扩展状态行。
 */
class FooterSnapshotTest {
    private val cases = Json.parseToJsonElement(javaClass.getResource("/pi/footer-golden.json")!!.readText()).jsonArray

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content
    private fun JsonObject.d(k: String) = this[k]?.jsonPrimitive?.doubleOrNull

    private fun toInput(c: JsonObject): FooterInput {
        val msgs = c["msgs"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        fun sum(k: String) = msgs.sumOf { it[k]?.jsonPrimitive?.longOrNull ?: 0L }
        val last = msgs.lastOrNull()
        val prompt = last?.let { (it.d("input") ?: 0.0) + (it.d("cacheRead") ?: 0.0) + (it.d("cacheWrite") ?: 0.0) } ?: 0.0
        return FooterInput(
            cwd = c.s("cwd")!!, home = c.s("home"), gitBranch = c.s("branch"), sessionName = c.s("sessionName"),
            input = sum("input"), output = sum("output"), cacheRead = sum("cacheRead"), cacheWrite = sum("cacheWrite"),
            cost = msgs.sumOf { it.d("cost") ?: 0.0 },
            latestCacheHitRate = if (prompt > 0) (last!!.d("cacheRead") ?: 0.0) / prompt * 100 else null,
            usingSubscription = c["sub"]?.jsonPrimitive?.booleanOrNull == true,
            contextPercent = c.d("contextPercent") ?: 0.0,
            contextWindow = c["contextWindow"]?.jsonPrimitive?.longOrNull ?: 0L,
            autoCompact = c["auto"]?.jsonPrimitive?.booleanOrNull != false,
            modelId = c.s("modelId"), provider = c.s("provider"),
            reasoning = c["reasoning"]?.jsonPrimitive?.booleanOrNull == true,
            thinkingLevel = c.s("thinkingLevel"),
            availableProviderCount = c["providers"]?.jsonPrimitive?.intOrNull ?: 1,
            extensionStatuses = c["statuses"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
        )
    }

    @Test
    fun matchesPiFooterJs() {
        assertTrue(cases.size >= 7)
        for (case in cases) {
            val o = case.jsonObject
            val input = o["input"]!!.jsonObject
            val width = input["width"]!!.jsonPrimitive.intOrNull!!
            val want = o["lines"]!!.jsonArray.map { it.jsonPrimitive.content }
            val got = Footer.render(toInput(input), width).map { it.plain() }
            assertEquals("width=$width", want, got)
            got.forEach { assertTrue("超宽：$it", CellWidth.width(it) <= width) }
        }
    }

    @Test
    fun segmentsAreTappable() {
        val lines = Footer.render(FooterInput(cwd = "/root/p", home = "/root", modelId = "m", contextWindow = 1000), 60)
        assertEquals("cwd", lines[0].single().segment)
        assertEquals("model", lines[1].last().segment)
        assertEquals("m", lines[1].last().text)
    }

    @Test
    fun contextColorThresholds() {
        fun tok(p: Double) = Footer.render(FooterInput(cwd = "/", contextPercent = p, contextWindow = 100), 80)[1].first { it.text.contains("%/") }.token
        assertEquals("dim", tok(70.0))
        assertEquals("warning", tok(70.5))
        assertEquals("error", tok(90.5))
    }
}
