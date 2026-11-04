package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.bridge.BridgeState
import io.github.zr987459.pigo.contract.bridge.BridgeStatus
import io.github.zr987459.pigo.contract.bridge.PiBridge
import io.github.zr987459.pigo.contract.fake.FakeEngineClient
import io.github.zr987459.pigo.contract.fake.FakePiBridge
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r3/app：[AppSessionOps] 不再有「需要 pigo-bridge」桩，每一项都落到 PiBridge / PiEngine 的真实方法上。
 * 桥接用 core/contract 的 [FakePiBridge]；PiEngine 侧（clear_queue、结束进程、abort_retry）用记录调用的 [RecEngine]。
 * PiEngine 自己的 clearQueue / stopProcess 见 core/engine PiEngineTest（脚本化假 pi）。
 */
class AppSessionOpsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tmp: File = Files.createTempDirectory("pigo-ops").toFile()

    @After
    fun tearDown() {
        scope.cancel()
        tmp.deleteRecursively()
    }

    private class RecEngine(var queue: QueueSnapshot = QueueSnapshot.EMPTY) : AppSessionOps.Engine {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun abortRetry(): Outcome<Unit> { calls += "abort_retry"; return Outcome.Ok(Unit) }
        override suspend fun clearQueue(): Outcome<QueueSnapshot> { calls += "clear_queue"; return Outcome.Ok(queue).also { queue = QueueSnapshot.EMPTY } }
        override suspend fun stopProcess(): Outcome<Unit> { calls += "stop_process"; return Outcome.Ok(Unit) }
    }

    /** EngineClient 只记 stop / newSession。 */
    private class RecClient(scope: CoroutineScope, val withdrawn: QueueSnapshot = QueueSnapshot.EMPTY) : io.github.zr987459.pigo.contract.EngineClient by FakeEngineClient(scope) {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun stop(): Outcome<QueueSnapshot> { calls += "stop"; return Outcome.Ok(withdrawn) }
        override suspend fun newSession(cwd: String?): Outcome<Unit> { calls += "new_session ${cwd.orEmpty()}"; return Outcome.Ok(Unit) }
    }

    private fun ops(
        client: RecClient = RecClient(scope),
        engine: RecEngine = RecEngine(),
        bridge: PiBridge = FakePiBridge(),
        exec: suspend (List<String>, Long) -> ExecResult = { _, _ -> ExecResult(0, "", "") },
    ) = AppSessionOps(client, engine, bridge, exec, tmp)

    @Test
    fun setLabelGoesThroughBridgeAndClears() = runBlocking {
        val b = FakePiBridge()
        val o = ops(bridge = b)
        assertTrue(o.setLabel("e1", "重要") is Outcome.Ok)
        assertEquals("重要", b.labels["e1"])
        assertTrue(o.setLabel("e1", null) is Outcome.Ok)
        assertNull(b.labels["e1"])
        assertEquals(listOf("label e1 重要", "label e1"), b.calls)
    }

    @Test
    fun setLabelReportsBridgeReasonWhenUnavailable() = runBlocking {
        val b = FakePiBridge().apply { status.value = BridgeStatus(BridgeState.UNAVAILABLE, "扩展加载失败") }
        val r = ops(bridge = b).setLabel("e1", "x")
        assertTrue(r is Outcome.Err && r.message.contains("扩展加载失败"))
    }

    @Test
    fun reloadUsesBridgeReload() = runBlocking {
        val b = FakePiBridge()
        assertTrue(ops(bridge = b).reload() is Outcome.Ok)
        assertEquals(1, b.reloads)
        b.status.value = BridgeStatus(BridgeState.UNAVAILABLE, "x")
        val r = ops(bridge = b).reload()
        assertTrue(r is Outcome.Err && r.message == PiBridge.RELOAD_NEEDS_RESTART)
    }

    @Test
    fun treeNavigateSwitchesInPlaceInSameSessionFile() = runBlocking {
        val b = FakePiBridge()
        val file = b.sessionFile
        val r = ops(bridge = b).treeNavigate("e3", summarize = true) as Outcome.Ok
        assertEquals("e3", r.value.leafId)
        assertFalse(r.value.viaFork)
        assertFalse(r.value.cancelled)
        assertEquals("e3", b.leafId)
        assertEquals(file, b.sessionFile) // 同一会话文件，原地切换
        assertEquals(listOf("tree-nav e3 --summarize"), b.calls)
    }

    @Test
    fun treeNavigateFallsBackToForkWhenBridgeUnavailable() = runBlocking {
        val b = FakePiBridge().apply { status.value = BridgeStatus(BridgeState.UNAVAILABLE, "x") }
        val r = ops(bridge = b).treeNavigate("e3", summarize = false) as Outcome.Ok
        assertTrue(r.value.viaFork)
    }

    @Test
    fun dequeueAllOnlyClearsQueueAndNeverAborts() = runBlocking {
        val client = RecClient(scope)
        val eng = RecEngine(QueueSnapshot(listOf("插话"), listOf("排队")))
        val r = ops(client = client, engine = eng).dequeueAll() as Outcome.Ok
        assertEquals(listOf("插话"), r.value.steering)
        assertEquals(listOf("排队"), r.value.followUp)
        assertEquals(listOf("clear_queue"), eng.calls)
        assertTrue(client.calls.isEmpty()) // 没有 stop / abort
    }

    @Test
    fun stopEngineStopsTurnThenEndsProcessAndReturnsQueuedTextToDraft() = runBlocking {
        val client = RecClient(scope, withdrawn = QueueSnapshot(emptyList(), listOf("还没发的话")))
        val eng = RecEngine()
        val o = ops(client = client, engine = eng)
        var recalled: List<String>? = null
        o.onWithdrawn = { recalled = it }
        assertTrue(o.stopEngine() is Outcome.Ok)
        assertEquals(listOf("stop"), client.calls)
        assertEquals(listOf("stop_process"), eng.calls)
        assertEquals(listOf("还没发的话"), recalled)
    }

    @Test
    fun abortRetrySendsOnlyAbortRetry() = runBlocking {
        val client = RecClient(scope)
        val eng = RecEngine()
        assertTrue(ops(client = client, engine = eng).abortRetry() is Outcome.Ok)
        assertEquals(listOf("abort_retry"), eng.calls)
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun runGuestNewSessionAndReport() = runBlocking {
        val client = RecClient(scope)
        val seen = mutableListOf<List<String>>()
        val o = ops(client = client, exec = { cmd, _ -> seen += cmd; ExecResult(0, "out", "err") })
        val g = o.runGuest("pi list") as Outcome.Ok
        assertEquals(listOf("bash", "-lc", "pi list"), seen.single())
        assertEquals("out\nerr", g.value.output)
        assertTrue(o.newSessionInDir("/root/p2") is Outcome.Ok)
        assertEquals(listOf("new_session /root/p2"), client.calls)
        val path = (o.writeReport("a/b.txt", "报告") as Outcome.Ok).value
        assertEquals("报告", File(path).readText())
    }

    @Test
    fun forEngineOnFakeEngineSaysNotPiInsteadOfPretending() = runBlocking {
        val o = AppSessionOps.forEngine(FakeEngineClient(scope), io.github.zr987459.pigo.contract.fake.FakeRuntimeManager(stepDelayMs = 0), tmp)
        assertTrue(o.setLabel("e1", "x") is Outcome.Err)
        assertTrue(o.dequeueAll() is Outcome.Err)
    }
}
