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

    // ---------------- 审查第 2 轮 ----------------

    @Test fun emptyMarkerNeverOpensAnOldSession() {
        val list = listOf(s("/root/.pi/agent/sessions/a.jsonl", 100), s("/root/.pi/agent/sessions/b.jsonl", 200))
        // 被中断时还没有会话文件：不打开旧会话，否则「上一轮被系统中断」会挂到并未中断的会话上
        assertNull(ResumePolicy.coldStartTarget("", { true }, list))
        // 正常结束：回到最近会话
        assertEquals("/root/.pi/agent/sessions/b.jsonl", ResumePolicy.coldStartTarget(null, { true }, list)?.guestPath)
        // 标记指向仍存在的会话：交给引擎
        assertNull(ResumePolicy.coldStartTarget("/root/.pi/agent/sessions/a.jsonl", { true }, list))
        // 标记指向已删除的会话：回到最近会话
        assertEquals("/root/.pi/agent/sessions/b.jsonl", ResumePolicy.coldStartTarget("/root/.pi/agent/sessions/gone.jsonl", { false }, list)?.guestPath)
    }

    @Test fun autoOpenOnlyWhileEngineUntouched() {
        val idle = io.github.zr987459.pigo.contract.ChatState()
        assertTrue(ResumePolicy.mayAutoOpen(null, io.github.zr987459.pigo.contract.EngineStatus.Stopped, idle))
        assertFalse("已有当前会话", ResumePolicy.mayAutoOpen("/x.jsonl", io.github.zr987459.pigo.contract.EngineStatus.Stopped, idle))
        assertFalse("引擎已在启动（用户抢先发送）", ResumePolicy.mayAutoOpen(null, io.github.zr987459.pigo.contract.EngineStatus.Starting, idle))
        assertFalse("正在生成", ResumePolicy.mayAutoOpen(null, io.github.zr987459.pigo.contract.EngineStatus.Stopped, idle.copy(running = true)))
        val withMsg = idle.copy(items = listOf(io.github.zr987459.pigo.contract.ChatItem.User("u1", "你好", io.github.zr987459.pigo.contract.Delivery.SENDING)))
        assertFalse("界面上已有消息", ResumePolicy.mayAutoOpen(null, io.github.zr987459.pigo.contract.EngineStatus.Stopped, withMsg))
    }
}
