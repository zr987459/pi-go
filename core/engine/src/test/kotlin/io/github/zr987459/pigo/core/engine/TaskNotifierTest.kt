package io.github.zr987459.pigo.core.engine

import io.github.zr987459.pigo.contract.RunOutcome
import io.github.zr987459.pigo.contract.RunOutcomeKind
import org.junit.Assert.assertEquals
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
}
