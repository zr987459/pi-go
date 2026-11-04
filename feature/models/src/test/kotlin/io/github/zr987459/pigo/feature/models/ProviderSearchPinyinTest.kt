package io.github.zr987459.pigo.feature.models

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN-r3 §7.3：「qw」只剩 qwen 相关；42 个服务商都可见。服务商取自内置 pi 1.5.0 目录快照。 */
class ProviderSearchPinyinTest {

    private fun catalogRows(): List<ProviderSearch.Row> {
        val candidates = listOf(
            "../../core/store/src/main/resources/pigo/catalog-1.5.0.json",
            "core/store/src/main/resources/pigo/catalog-1.5.0.json",
        )
        val f = candidates.map(::File).first { it.isFile }
        val root = Json.parseToJsonElement(f.readText()).jsonObject
        return root["providers"]!!.jsonArray.map {
            val o = it.jsonObject
            ProviderSearch.Row(o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content)
        }
    }

    @Test fun all42ProvidersVisibleWithEmptyQuery() {
        val rows = catalogRows()
        assertEquals(42, rows.size)
        assertEquals(rows, ProviderSearch.filter(rows, ""))
        assertEquals(rows, ProviderSearch.filter(rows, "   "))
    }

    @Test fun qwOnlyLeavesQwen() {
        val rows = catalogRows() + ProviderSearch.Row("qwen-token-plan-cn", "通义 Token Plan（中国）")
        val hits = ProviderSearch.filter(rows, "qw")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.joinToString { it.id }, hits.all { it.id.startsWith("qwen") })
        assertEquals(rows.filter { it.id.startsWith("qwen") }.toSet(), hits.toSet())
    }

    @Test fun chineseLabelsMatchByPinyinInitials() {
        val rows = listOf(
            ProviderSearch.Row("qwen-token-plan-cn", "通义 Token Plan（中国）"),
            ProviderSearch.Row("zai-coding-cn", "智谱 GLM Coding Plan（中国）"),
            ProviderSearch.Row("xiaomi", "小米 MiMo"),
            ProviderSearch.Row("deepseek", "DeepSeek"),
        )
        assertEquals(listOf("qwen-token-plan-cn"), ProviderSearch.filter(rows, "ty").map { it.id })
        assertEquals(listOf("zai-coding-cn"), ProviderSearch.filter(rows, "zp").map { it.id })
        assertEquals(listOf("xiaomi"), ProviderSearch.filter(rows, "xm").map { it.id })
        assertEquals(listOf("deepseek"), ProviderSearch.filter(rows, "deeps").map { it.id })
        assertEquals(listOf("qwen-token-plan-cn"), ProviderSearch.filter(rows, "通义").map { it.id })
        assertEquals("ty t p zg", ProviderSearch.initials("通义 Token Plan（中国）"))
    }
}
