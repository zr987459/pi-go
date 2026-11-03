package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.PiState
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.SessionStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 审查第 2 轮 Important：冷启动预热结束前，用户命令不能抢先启动引擎。 */
@OptIn(ExperimentalCoroutinesApi::class)
class WarmUpGateTest {
    /** 只记录调用顺序的引擎桩。 */
    private class RecEngine : EngineClient {
        val calls = mutableListOf<String>()
        override val status = MutableStateFlow<EngineStatus>(EngineStatus.Stopped)
        override val chat: StateFlow<ChatState> = MutableStateFlow(ChatState())
        override val uiRequests: SharedFlow<ExtUiRequest> = MutableSharedFlow()
        override suspend fun send(text: String, mode: SendMode): Outcome<Disposition> { calls += "send:$text"; return Outcome.Ok(Disposition.STARTED) }
        override suspend fun stop(): Outcome<QueueSnapshot> { calls += "stop"; return Outcome.Ok(QueueSnapshot.EMPTY) }
        override suspend fun newSession(cwd: String?): Outcome<Unit> { calls += "new"; return Outcome.Ok(Unit) }
        override suspend fun openSession(guestPath: String, cwd: String): Outcome<Unit> { calls += "open:$guestPath"; return Outcome.Ok(Unit) }
        override suspend fun rename(name: String): Outcome<Unit> { calls += "rename"; return Outcome.Ok(Unit) }
        override suspend fun models(): Outcome<List<ModelInfo>> { calls += "models"; return Outcome.Ok(emptyList()) }
        override suspend fun setModel(provider: String, modelId: String): Outcome<ModelInfo> { calls += "setModel"; return Outcome.Ok(ModelInfo(provider, modelId)) }
        override suspend fun thinkingLevels(): Outcome<List<String>> { calls += "levels"; return Outcome.Ok(emptyList()) }
        override suspend fun setThinking(level: String): Outcome<Unit> { calls += "thinking"; return Outcome.Ok(Unit) }
        override suspend fun stats(): Outcome<SessionStats> { calls += "stats"; return Outcome.Ok(SessionStats(null, null, null)) }
        override suspend fun answerUi(id: String, value: String?, confirmed: Boolean?, cancelled: Boolean) { calls += "answer" }
        override suspend fun restart(reason: String): Outcome<Unit> { calls += "restart"; return Outcome.Ok(Unit) }
    }

    @Test fun userCommandsWaitUntilWarmUpReleasesGate() = runTest {
        val inner = RecEngine()
        val gate = WarmUpGate()
        val e = GatedEngineClient(inner, gate, MutableStateFlow(true), backgroundScope)

        launch { e.send("你好") }
        launch { e.newSession() }
        launch { e.openSession("/s/x.jsonl", "/root") }
        launch { e.models() }
        launch { e.restart("r") }
        advanceUntilIdle()
        assertTrue("预热未结束，命令不能到达引擎：${inner.calls}", inner.calls.isEmpty())

        // 预热自己用的是内层引擎，不受闸门影响（模拟 warmUp 的 openSession 先完成）
        inner.openSession("/s/last.jsonl", "/root")
        gate.release()
        advanceUntilIdle()
        assertEquals("/s/last.jsonl 必须先于用户消息", "open:/s/last.jsonl", inner.calls.first())
        assertTrue(inner.calls.containsAll(listOf("send:你好", "new", "open:/s/x.jsonl", "models", "restart")))
    }

    @Test fun readOnlyCallsPassThrough() = runTest {
        val inner = RecEngine()
        val e = GatedEngineClient(inner, WarmUpGate(), MutableStateFlow(true), backgroundScope)
        e.stats(); e.stop(); e.answerUi("1")
        assertEquals(listOf("stats", "stop", "answer"), inner.calls)
    }

    @Test fun bypassWhenRuntimeNotReady() = runTest {
        val inner = RecEngine()
        var ready = false
        val e = GatedEngineClient(inner, WarmUpGate(bypass = { !ready }), MutableStateFlow(false), backgroundScope)
        e.send("x")
        assertEquals(listOf("send:x"), inner.calls)
        ready = true
        backgroundScope.launch { e.send("y") }
        advanceUntilIdle()
        assertEquals("运行环境就绪后要等预热", listOf("send:x"), inner.calls)
    }

    @Test fun releaseIsIdempotentAndOpensLaterCallsImmediately() = runTest {
        val gate = WarmUpGate()
        gate.release(); gate.release()
        gate.await()
        assertTrue(gate.open.value)
    }

    @Test fun statusShowsStartingWhileWarmingUp() = runTest {
        val inner = RecEngine()
        val gate = WarmUpGate()
        val ready = MutableStateFlow(true)
        val e = GatedEngineClient(inner, gate, ready, backgroundScope)
        assertSame(EngineStatus.Starting, e.status.value)
        gate.release(); runCurrent()
        assertSame(EngineStatus.Stopped, e.status.value)
        val idle = EngineStatus.Idle(PiState())
        inner.status.value = idle; runCurrent()
        assertEquals(idle, e.status.value)
    }

    @Test fun presentedStatusRules() {
        val idle = EngineStatus.Idle(PiState())
        assertSame(EngineStatus.Starting, GatedEngineClient.presented(EngineStatus.Stopped, gateOpen = false, runtimeReady = true))
        assertSame("运行环境未就绪（安装向导）不改写", EngineStatus.Stopped, GatedEngineClient.presented(EngineStatus.Stopped, false, false))
        assertSame(EngineStatus.Stopped, GatedEngineClient.presented(EngineStatus.Stopped, true, true))
        assertEquals(idle, GatedEngineClient.presented(idle, false, true))
    }
}
