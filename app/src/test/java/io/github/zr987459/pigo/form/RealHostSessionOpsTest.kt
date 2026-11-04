package io.github.zr987459.pigo.form

import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.fake.FakeDraftStore
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.contract.fake.FakePiBridge
import io.github.zr987459.pigo.contract.fake.FakePiSettings
import io.github.zr987459.pigo.contract.fake.FakeScriptedEngine
import io.github.zr987459.pigo.contract.fake.FakeSessionRepository
import io.github.zr987459.pigo.contract.session.UiForm
import io.github.zr987459.pigo.di.AppSessionOps
import io.github.zr987459.pigo.di.SessionBindings
import io.github.zr987459.pigo.feature.chat.ChatController
import java.io.File
import java.util.Collections
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r3/app：在 App 真实装配 [SessionBindings.real] 得到的 SessionHostImpl 上跑动作表，核对标签、reload、树内原地切换、
 * 取回排队、停止引擎、取消重试都落到 [AppSessionOps] → PiBridge / PiEngine（这里是 FakePiBridge + 记录调用的引擎端口），
 * 两种形态切换之间引擎只起一次（start=1、restart=0）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealHostSessionOpsTest {

    private class RecEngine : AppSessionOps.Engine {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var queue = QueueSnapshot.EMPTY
        override suspend fun abortRetry(): Outcome<Unit> { calls += "abort_retry"; return Outcome.Ok(Unit) }
        override suspend fun clearQueue(): Outcome<QueueSnapshot> { calls += "clear_queue"; return Outcome.Ok(queue).also { queue = QueueSnapshot.EMPTY } }
        override suspend fun stopProcess(): Outcome<Unit> { calls += "stop_process"; return Outcome.Ok(Unit) }
    }

    @Test
    fun sessionOpsActionsReachBridgeAndEngineOnRealHost() = runTest {
        val scope = backgroundScope
        val engine = FakeScriptedEngine(scope).also { it.start() }
        val controller = ChatController(engine, scope, drafts = FakeDraftStore())
        val bridge = FakePiBridge()
        val port = RecEngine()
        val ops = AppSessionOps(engine, port, bridge, { _, _ -> ExecResult(0, "", "") }, File(System.getProperty("java.io.tmpdir"), "pigo-ops-host"))
        val host = SessionBindings.real(
            scope, UiForm.CALM,
            SessionBindings.Deps(
                engine = engine, extras = FakeEngineExtras(), controller = controller, settings = FakePiSettings(),
                repo = FakeSessionRepository(), panels = null, ops = ops,
            ),
        )
        runCurrent()

        // 标签（/pigo:label）
        host.runAction("tree.label", "e1 重要")
        assertEquals("重要", bridge.labels["e1"])
        host.toggleForm()
        // reload（/pigo:reload）
        assertEquals(ActionResult.Toast("已重新加载"), host.runAction("reload", null))
        assertEquals(1, bridge.reloads)
        // 树内原地切换（/pigo:tree-nav），不分叉
        val nav = host.runAction("session.tree", "e3 nosummary")
        assertEquals(ActionResult.Toast("已切换到所选位置"), nav)
        assertEquals("e3", bridge.leafId)
        host.toggleForm()
        // 取回排队（clear_queue，不 abort）：文本进共享草稿
        port.queue = QueueSnapshot(listOf("插话"), emptyList())
        host.runAction("queue.dequeue", null)
        assertTrue(host.draft.value.text.contains("插话"))
        // 取消重试（abort_retry）
        host.runAction("retry.abort", null)
        // 停止引擎（/quit）：先 stop 当前一轮，再结束进程
        host.runAction("engine.stop", null)
        runCurrent()
        assertEquals(listOf("clear_queue", "abort_retry", "stop_process"), port.calls)
        assertTrue(engine.commands.value.any { it.type == "abort" })
        // 草稿还在（停止引擎不清草稿）
        assertTrue(host.draft.value.text.contains("插话"))
        // 引擎只起一次，切形态不重启
        assertEquals(1, engine.startCount)
        assertEquals(0, engine.restartCount)
        assertEquals(listOf("label e1 重要", "reload", "tree-nav e3"), bridge.calls)
    }
}
