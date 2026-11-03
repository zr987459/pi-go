package io.github.zr987459.pigo.core.store

import io.github.zr987459.pigo.contract.SessionSummary
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** 审查 R1 #4：冷启动回到上次会话（L5）。 */
class ResumePolicyTest {
    private fun s(path: String, updated: Long) = SessionSummary(
        guestPath = path, id = path, cwd = "/root/pi-cwd", name = null, preview = "",
        createdAt = Instant.ofEpochMilli(0), updatedAt = Instant.ofEpochMilli(updated), sizeBytes = 1,
    )

    @Test fun normalColdStartReturnsMostRecentlyUpdatedSession() {
        val list = listOf(s("/root/.pi/agent/sessions/a.jsonl", 100), s("/root/.pi/agent/sessions/b.jsonl", 300), s("/root/.pi/agent/sessions/c.jsonl", 200))
        assertEquals("/root/.pi/agent/sessions/b.jsonl", ResumePolicy.coldStartTarget(false, list)?.guestPath)
    }

    @Test fun interruptedRunIsLeftToTheEngine() {
        val list = listOf(s("/root/.pi/agent/sessions/a.jsonl", 100))
        assertNull(ResumePolicy.coldStartTarget(true, list))
    }

    @Test fun engineResumesOnlyWhenMarkerPointsToExistingFile() {
        val exists = setOf("/root/.pi/agent/sessions/a.jsonl")
        assertTrue(ResumePolicy.engineWillResume("/root/.pi/agent/sessions/a.jsonl") { it in exists })
        assertFalse("会话文件已被删除：不等引擎，回到最近会话", ResumePolicy.engineWillResume("/root/.pi/agent/sessions/gone.jsonl") { it in exists })
        assertFalse("被中断时还没有会话文件", ResumePolicy.engineWillResume("") { true })
        assertFalse("上一轮正常结束", ResumePolicy.engineWillResume(null) { true })
        assertFalse("读文件出错按不能恢复处理", ResumePolicy.engineWillResume("/x") { error("io") })
    }

    @Test fun noSessionsMeansNewSession() {
        assertNull(ResumePolicy.coldStartTarget(false, emptyList()))
    }

    @Test fun tieBreaksByPath() {
        val list = listOf(s("/root/.pi/agent/sessions/z.jsonl", 5), s("/root/.pi/agent/sessions/m.jsonl", 5))
        assertEquals("/root/.pi/agent/sessions/m.jsonl", ResumePolicy.coldStartTarget(false, list)?.guestPath)
    }
}
