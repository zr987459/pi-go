package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.actions.PiActionCatalog
import io.github.zr987459.pigo.contract.actions.TermEntry
import io.github.zr987459.pigo.contract.actions.UiEntry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 硬性要求 ① 的 JVM 部分（DESIGN-r3 §7.1 第 2 条）：解析 `docs/pi-features.md` §1–§11 每个表格的数据行首列，
 * 断言都在 `docs/r3/coverage-map.tsv` 里；映射到的动作都在动作表里、两种入口都有；没有孤儿动作。
 */
class PiFeatureCoverageTest {
    private fun file(prop: String, fallback: String): File =
        System.getProperty(prop)?.let(::File) ?: generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, fallback) }.first { it.exists() }

    private val features = file("pigo.piFeatures", "docs/pi-features.md")
    private val map = file("pigo.coverageMap", "docs/r3/coverage-map.tsv")

    data class Row(val section: String, val head: String)

    /** 与 coverage-map 生成脚本同一规则：`##`/`###` 标题的节号；表头后、分隔行跳过；`\|` 不拆列。 */
    private fun featureRows(): List<Row> {
        val out = ArrayList<Row>()
        var sec: String? = null
        var inTable = false
        for (line in features.readLines()) {
            Regex("^#+\\s+(\\d+(?:\\.\\d+)?)\\.?\\s").find(line)?.let { sec = "§" + it.groupValues[1]; inTable = false; return@let }
            if (line.startsWith("#")) { inTable = false; continue }
            if (!line.startsWith("|")) { if (line.isBlank()) inTable = false; continue }
            val cells = line.trim().trim('|').replace("\\|", "\u0000").split('|').map { it.trim().replace("\u0000", "\\|") }
            if (!inTable) { inTable = true; continue } // 表头
            if (cells.all { it.isEmpty() || Regex(":?-+:?").matches(it) }) continue
            val s = sec ?: continue
            if (s.removePrefix("§").substringBefore('.').toInt() > 11) continue
            out += Row(s, cells[0])
        }
        return out
    }

    private fun mapping(): Map<Row, String> = map.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { l ->
        val c = l.split('\t'); Row(c[0], c[1]) to c[2]
    }

    @Test
    fun everyFeatureRowIsMapped() {
        val rows = featureRows()
        assertTrue("清单表格行太少（解析出错？）：${rows.size}", rows.size > 200)
        val m = mapping()
        val missing = rows.filter { it !in m }
        assertTrue("coverage-map.tsv 缺这些清单行：\n" + missing.joinToString("\n"), missing.isEmpty())
        val stale = m.keys - rows.toSet()
        assertTrue("coverage-map.tsv 有清单里已不存在的行：\n" + stale.joinToString("\n"), stale.isEmpty())
    }

    @Test
    fun mappedActionsExistWithBothEntries() {
        val ids = PiActionCatalog.all.associateBy { it.id }
        val bad = ArrayList<String>()
        for ((row, v) in mapping()) {
            if (v == "BASE" || v == "FORM") continue
            val targets = if (v.startsWith("NA:")) listOf(v.removePrefix("NA:")) else v.split(',').map { it.trim() }
            for (t in targets) {
                val spec = ids[t]
                if (spec == null) { bad += "$row → $t 不在动作表"; continue }
                if (v.startsWith("NA:") && (spec.ui !is UiEntry.Explain || spec.term !is TermEntry.Explain)) bad += "$row → $t 应为说明条目"
                (spec.ui as? UiEntry.Explain)?.let { if (it.text.isBlank()) bad += "$t 的 UI 说明为空" }
                (spec.term as? TermEntry.Explain)?.let { if (it.text.isBlank()) bad += "$t 的终端说明为空" }
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun noOrphanActions() {
        val used = mapping().values.flatMap { v -> if (v.startsWith("NA:")) listOf(v.removePrefix("NA:")) else v.split(',').map { it.trim() } }.toSet()
        // 形态切换本身来自清单 §13（不是 §1–§11 的表格行），允许只由 §13 引用。
        val orphans = PiActionCatalog.all.filter { (it.id !in used && it.featureRefs.none { r -> r.startsWith("§13 ") }) || it.featureRefs.isEmpty() }.map { it.id }
        assertTrue("孤儿动作（没有清单行映射到它，或 featureRefs 为空）：$orphans", orphans.isEmpty())
        val sections = featureRows().map { it.section }.toSet() + "§13"
        val badRefs = PiActionCatalog.all.flatMap { s -> s.featureRefs.filter { r -> r.substringBefore(' ') !in sections }.map { "${s.id}: $it" } }
        assertTrue("featureRefs 指向清单里不存在的节：$badRefs", badRefs.isEmpty())
    }

    @Test
    fun actionIdsAreUnique() {
        val dup = PiActionCatalog.all.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue("重复的动作 id：$dup", dup.isEmpty())
    }
}
