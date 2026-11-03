package io.github.zr987459.pigo.core.engine

import io.github.zr987459.pigo.contract.RunOutcome
import io.github.zr987459.pigo.contract.RunOutcomeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** N1：App 在后台时 agent_settled 发一条完成通知，aborted 不发；前台不发。 */
class TaskNotifierTest {
    private class Recorder : TaskNotificationPort {
        val posted = mutableListOf<TaskNotice>()
        override fun post(notice: TaskNotice) {
            posted += notice
        }
    }

    private fun outcome(kind: RunOutcomeKind) = RunOutcome(kind, "/root/.pi/agent/sessions/a.jsonl", "重构", message = "401", lastTextPreview = "改好了", atMillis = 1, cwd = "/root/p")

    @Test
    fun backgroundCompletedIsNotifiedAbortedIsNot() {
        var fg = false
        val rec = Recorder()
        val n = TaskNotifier({ fg }, rec)
        n.onOutcome(outcome(RunOutcomeKind.COMPLETED))
        assertEquals(1, rec.posted.size)
        assertEquals("pi 已完成 · 重构", rec.posted[0].title)
        assertEquals("改好了", rec.posted[0].text)
        assertEquals("/root/p", rec.posted[0].outcome.cwd)
        assertNull(n.onOutcome(outcome(RunOutcomeKind.ABORTED)))
        assertEquals(1, rec.posted.size)
        fg = true
        assertNull("前台不发", n.onOutcome(outcome(RunOutcomeKind.COMPLETED)))
        fg = false
        assertEquals("pi 出错了 · 重构", n.onOutcome(outcome(RunOutcomeKind.ERROR))!!.title)
        assertTrue(n.onOutcome(outcome(RunOutcomeKind.NEEDS_INPUT))!!.title.startsWith("pi 需要你回答"))
        assertEquals("同一会话复用通知 id", 1, rec.posted.map { it.id }.distinct().size)
    }

    /** 审查第 1 轮 Important#3：回调注册晚于 MainActivity.onStart（AppGraph by lazy）时计数停在 0，靠进程重要性兜底仍判为前台。 */
    @Test
    fun lateRegistrationStillDetectsForegroundViaImportance() {
        var started = 0
        var importance: Int? = ForegroundDecider.IMPORTANCE_FOREGROUND
        val d = ForegroundDecider({ started }, { importance })
        assertTrue("计数为 0 但进程在前台", d.isForeground())
        importance = 125 // 退到后台，只剩 EngineService 前台服务
        assertFalse(d.isForeground())
        importance = null // 读不到时只看计数
        started = 1
        assertTrue(d.isForeground())
        started = 0
        assertFalse(d.isForeground())
    }

    /** started 不发；stopped 后 agent_settled（经 runOutcomes 流）发一条；aborted 不发。 */
    @Test
    fun outcomesFlowRespectsStartedStopped() = kotlinx.coroutines.runBlocking {
        var started = 1
        val rec = Recorder()
        val flow = kotlinx.coroutines.flow.MutableSharedFlow<RunOutcome>(extraBufferCapacity = 8)
        val job = TaskNotifier(ForegroundDecider({ started }, { 400 })::isForeground, rec).start(flow, this)
        kotlinx.coroutines.yield()
        flow.emit(outcome(RunOutcomeKind.COMPLETED))
        kotlinx.coroutines.yield()
        assertEquals("started 时不发", 0, rec.posted.size)
        started = 0
        flow.emit(outcome(RunOutcomeKind.COMPLETED))
        flow.emit(outcome(RunOutcomeKind.ABORTED))
        kotlinx.coroutines.yield()
        kotlinx.coroutines.yield()
        assertEquals(1, rec.posted.size)
        job.cancel()
    }
}
