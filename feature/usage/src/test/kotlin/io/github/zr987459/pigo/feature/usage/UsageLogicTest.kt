package io.github.zr987459.pigo.feature.usage

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContextUsage
import io.github.zr987459.pigo.contract.ToolStatus
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.feature.usage.logic.CompactionSummary
import io.github.zr987459.pigo.feature.usage.logic.ContextBar
import io.github.zr987459.pigo.feature.usage.logic.DiffKind
import io.github.zr987459.pigo.feature.usage.logic.EditToolDiff
import io.github.zr987459.pigo.contract.CostDisplaySettings
import io.github.zr987459.pigo.contract.fake.FakeAppSettingsStore
import io.github.zr987459.pigo.feature.usage.logic.LineDiff
import io.github.zr987459.pigo.feature.usage.logic.SessionTreeController
import io.github.zr987459.pigo.feature.usage.logic.SessionTreeRows
import io.github.zr987459.pigo.feature.usage.logic.TurnSummary
import io.github.zr987459.pigo.feature.usage.logic.UsageController
import io.github.zr987459.pigo.feature.usage.logic.UsageFormat
import io.github.zr987459.pigo.contract.Outcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsageLogicTest {
    private fun TestScope.settle() { runCurrent(); advanceTimeBy(1_000); runCurrent() }

    @Test
    fun usageShowsTokensCostAndContextBar() = runTest {
        val extras = FakeEngineExtras()
        val c = UsageController(extras, backgroundScope, FakeAppSettingsStore(cost = CostDisplaySettings(showCny = true, usdToCny = 7.1)))
        c.refresh()
        settle()
        val s = c.state.value
        assertEquals(105_000L, s.stats!!.tokens.total)
        assertEquals("$0.45", s.costUsd)
        assertEquals("约 ¥3.20", s.costCny)
        val bar = s.bar as ContextBar.Known
        assertEquals(30.0, bar.percent, 0.0)
        assertEquals("30% · 60k / 200k", bar.label)

        c.setCnyRate(null)
        assertNull(c.state.value.costCny)
    }

    /** 审查第 1 轮 Nit：汇率写进契约的 AppSettingsStore（持久化由设置存储负责），新的控制器（重启后）读得回来。 */
    @Test
    fun cnyRatePersistsThroughAppSettingsStore() = runTest {
        val settings = FakeAppSettingsStore()
        val c = UsageController(FakeEngineExtras(), backgroundScope, settings)
        c.refresh(); settle()
        assertNull("默认只显示美元", c.state.value.costCny)
        c.setCnyRate(7.3)
        settle()
        assertEquals(CostDisplaySettings(showCny = true, usdToCny = 7.3), settings.costDisplay.value)

        val again = UsageController(FakeEngineExtras(), backgroundScope, settings)
        again.refresh(); settle()
        assertEquals(7.3, again.state.value.cnyRate!!, 0.0)
        assertEquals("约 ¥3.29", again.state.value.costCny)

        // 设置页那边关掉人民币显示，弹层跟着变
        settings.setCostDisplay(settings.costDisplay.value.copy(showCny = false))
        settle()
        assertNull(again.state.value.cnyRate)
        assertNull(again.state.value.costCny)
        assertEquals("关闭时保留汇率", 7.3, settings.costDisplay.value.usdToCny, 0.0)
    }

    @Test
    fun compactPassesInstructionsAndShowsAfterCompaction() = runTest {
        val extras = FakeEngineExtras()
        val c = UsageController(extras, backgroundScope)
        c.refresh(); settle()
        c.compact("  保留接口设计的讨论  ")
        settle()
        assertTrue(extras.calls.contains("compact:保留接口设计的讨论"))
        assertEquals(60_000L, c.state.value.lastCompaction!!.tokensBefore)
        assertTrue("压缩后 percent 为 null → 待更新，不回退旧值", c.state.value.bar is ContextBar.AfterCompaction)
        assertFalse(c.state.value.compacting)

        c.compact("   ")
        settle()
        assertEquals("compact:", extras.calls.last { it.startsWith("compact:") })
    }

    @Test
    fun contextBarUnknownBeforeAnyUsage() {
        assertEquals(ContextBar.Unknown, ContextBar.of(null, null, null, hadUsage = false))
        assertTrue(ContextBar.of(null, null, 200_000, hadUsage = true) is ContextBar.AfterCompaction)
    }

    @Test
    fun formatting() {
        assertEquals("950", UsageFormat.tokens(950))
        assertEquals("1.2k", UsageFormat.tokens(1_234))
        assertEquals("150k", UsageFormat.tokens(150_000))
        assertEquals("2.5M", UsageFormat.tokens(2_500_000))
        assertEquals("$0.0042", UsageFormat.usd(0.0042))
        assertEquals("—", UsageFormat.usd(null))
        assertEquals(7.2, UsageFormat.parseRate(" 7。2 ")!!, 0.0)
        assertNull(UsageFormat.parseRate("-1"))
    }

    @Test
    fun treeRendersThreeLeavesAndForkCallsEngine() = runTest {
        val extras = FakeEngineExtras()
        val forked = mutableListOf<String>()
        val c = SessionTreeController(extras, backgroundScope, fork = { id -> forked += id; extras.fork(id) })
        c.load(); settle()
        val rows = c.state.value.rows
        assertEquals(7, rows.size)
        assertEquals(setOf("e4", "e6", "e7"), rows.filter { it.isLeaf }.map { it.id }.toSet())
        assertEquals(3, SessionTreeRows.leafCount(c.state.value.tree!!))
        assertEquals(setOf("e1", "e2", "e3", "e4"), rows.filter { it.onCurrentPath }.map { it.id }.toSet())
        assertTrue(rows.single { it.id == "e4" }.isCurrentLeaf)
        assertEquals(listOf("e1", "e3", "e5"), rows.filter { it.canFork }.map { it.id })
        // 单链不缩进，分叉处缩进一级；当前分支排在同层最前。
        assertEquals(0, rows.single { it.id == "e2" }.depth)
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5", "e6", "e7"), rows.map { it.id })
        assertEquals(1, rows.single { it.id == "e3" }.depth)

        val r = c.forkFrom("e5")
        assertEquals(listOf("e5"), forked)
        assertEquals("换个问法", (r as Outcome.Ok).value.text)
        assertNull(c.state.value.forking)

        c.toggle("e2")
        assertEquals(listOf("e1", "e2"), c.state.value.rows.map { it.id })
    }

    @Test
    fun compactionSummaryParsing() {
        val p = CompactionSummary.parse("## 目标\n修登录\n\n<read-files>\nsrc/a.kt\nsrc/b.kt\n</read-files>\n<modified-files>\nsrc/a.kt\n</modified-files>\n")
        assertEquals("## 目标\n修登录", p.body)
        assertEquals(listOf("src/a.kt", "src/b.kt"), p.readFiles)
        assertEquals(listOf("src/a.kt"), p.modifiedFiles)
        val plain = CompactionSummary.parse("正文里提到 <read-files> 但不在末尾")
        assertTrue(plain.readFiles.isEmpty())
    }

    @Test
    fun lineDiffAndUnifiedPatch() {
        val d = LineDiff.diff("a\nb\nc\n", "a\nB\nc\nd\n")
        assertEquals(listOf(DiffKind.CONTEXT, DiffKind.REMOVED, DiffKind.ADDED, DiffKind.CONTEXT, DiffKind.ADDED), d.map { it.kind })
        assertEquals(2 to 1, LineDiff.stats(d))
        val u = LineDiff.parseUnified("--- a/x.kt\n+++ b/x.kt\n@@ -10,2 +10,2 @@\n ctx\n-old\n+new\n\\ No newline at end of file\n")
        assertEquals(listOf(DiffKind.FILE, DiffKind.FILE, DiffKind.HUNK, DiffKind.CONTEXT, DiffKind.REMOVED, DiffKind.ADDED), u.map { it.kind })
        assertEquals(11, u.single { it.kind == DiffKind.REMOVED }.oldNo)
        assertEquals(11, u.single { it.kind == DiffKind.ADDED }.newNo)
    }

    private fun obj(vararg p: Pair<String, Any>): JsonObject = JsonObject(p.associate { (k, v) ->
        k to when (v) {
            is String -> JsonPrimitive(v)
            is JsonObject -> v
            is JsonArray -> v
            else -> JsonPrimitive(v.toString())
        }
    })

    @Test
    fun editToolDiffPrefersPatchThenArgs() {
        val args = obj("path" to "src/a.kt", "edits" to JsonArray(listOf(obj("oldText" to "x = 1", "newText" to "x = 2"))))
        val pending = ChatItem.Tool("t1", "c1", "edit", args, ToolStatus.RUNNING)
        assertEquals(listOf(DiffKind.REMOVED, DiffKind.ADDED), EditToolDiff.of(pending)!!.map { it.kind })
        val done = pending.copy(status = ToolStatus.OK, result = obj("details" to obj("patch" to "@@ -1 +1 @@\n-x = 1\n+x = 2\n")))
        assertEquals(DiffKind.HUNK, EditToolDiff.of(done)!!.first().kind)
        assertNull(EditToolDiff.of(pending.copy(toolName = "read")))
    }

    @Test
    fun turnSummaryCountsOnlySuccessfulWrites() {
        val items = listOf(
            ChatItem.User("u0", "旧问题"),
            ChatItem.Tool("t0", "c0", "write", obj("path" to "old.txt"), ToolStatus.OK),
            ChatItem.User("u1", "新问题"),
            ChatItem.Tool("t1", "c1", "write", obj("path" to "src/a.kt"), ToolStatus.OK),
            ChatItem.Tool("t2", "c2", "edit", obj("path" to "./src/../src/a.kt"), ToolStatus.OK),
            ChatItem.Tool("t3", "c3", "edit", obj("path" to "/etc/b"), ToolStatus.ERROR),
            ChatItem.Tool("t4", "c4", "read", obj("path" to "README.md"), ToolStatus.OK),
            ChatItem.Tool("t5", "c5", "write", obj("file_path" to "/abs/c.txt"), ToolStatus.OK),
        )
        val v = TurnSummary.build(items, "/root/pi-cwd", 185_000, 42.04)
        assertEquals(listOf("/root/pi-cwd/src/a.kt", "/abs/c.txt"), v.writtenFiles)
        assertEquals("3m 5s", v.durationText)
        assertEquals("42.0 tok/s", v.rateText)
        assertEquals("1h 2m 5s", TurnSummary.formatDuration(3_725_000))
    }
}
