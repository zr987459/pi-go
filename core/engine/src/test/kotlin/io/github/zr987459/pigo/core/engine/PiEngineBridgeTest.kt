package io.github.zr987459.pigo.core.engine

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.bridge.BridgeState
import io.github.zr987459.pigo.contract.bridge.PiBridge
import io.github.zr987459.pigo.core.protocol.str
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r3/core：PiEngine 的桥接扩展调用（DESIGN-r3 §5.6）与退路，用脚本化的假 pi 模拟 pigo-bridge.ts 的 notify 回传。
 * 真 pi 1.5.0 上的同一流程见 realpi/RealPiBridgeIntegrationTest。
 */
class PiEngineBridgeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = ListLog()
    private val tmp: File = Files.createTempDirectory("pigo-bridge").toFile()
    private val bridgeFile = File(tmp, "pigo/pigo-bridge.ts")

    @After
    fun tearDown() {
        scope.cancel()
        tmp.deleteRecursively()
    }

    private fun config(bridge: String? = bridgeFile.absolutePath, readyMs: Long = 2_000) = EngineConfig(
        frameMs = 0, bootTimeoutMs = 2_000, abortTimeoutMs = 300, defaultTimeoutMs = 1_000, getMessagesTimeoutMs = 1_000,
        stopGraceMs = 200, bridgeExtension = bridge, bridgeReadyTimeoutMs = readyMs, bridgeReplyTimeoutMs = 1_000,
    )

    private val history = """{"messages":[{"role":"user","content":"第一句"},{"role":"assistant","provider":"p","model":"m1","content":[{"type":"text","text":"答一"}],"stopReason":"stop"}]}"""

    private fun ScriptedPi.notify(payload: String) =
        emit("""{"type":"extension_ui_request","id":"n${System.nanoTime()}","method":"notify","message":${JsonPrimitive("pigo-bridge:$payload")},"notifyType":"info"}""")

    /** 假 pi：第一次 get_state 前回 ready（[ready] 为假时不回）；prompt 里的 /pigo:* 按 [onBridge] 回传。 */
    private fun script(ready: Boolean = true, onBridge: ScriptedPi.(String) -> Unit = {}): ScriptedPi.(JsonObject) -> Unit {
        var announced = false
        return { cmd ->
            when (cmd.str("type")) {
                "get_state" -> {
                    if (ready && !announced) { announced = true; notify("""{"event":"ready","version":1,"reason":"startup"}""") }
                    ok(cmd, ScriptedPi.STATE)
                }
                "get_messages" -> ok(cmd, history)
                "get_entries" -> ok(cmd, """{"entries":[],"leafId":null}""")
                "get_commands" -> ok(cmd, """{"commands":[{"name":"pigo:label","source":"extension"},{"name":"tpl","source":"prompt"},{"name":"pigo:reload","source":"extension"}]}""")
                "fork" -> ok(cmd, """{"text":"第一句","cancelled":false}""")
                "prompt" -> {
                    ok(cmd, """{"disposition":"handled"}""")
                    val m = cmd.str("message").orEmpty()
                    if (m.startsWith("/pigo:")) onBridge(m)
                }
                else -> ok(cmd)
            }
        }
    }

    private suspend fun waitFor(timeoutMs: Long = 5_000, cond: () -> Boolean) = withTimeout(timeoutMs) { while (!cond()) delay(10) }

    @Test
    fun installsFromSourceAndAddsExtensionArg() = runBlocking {
        val rt = ScriptedRuntime { script() }
        val e = PiEngine(rt, StaticEnv(), log, scope, config(), bridgeSource = { "export default function(){}".byteInputStream() })
        assertTrue(e.start() is Outcome.Ok)
        assertTrue("已安装到 bridgeExtension", bridgeFile.isFile)
        assertEquals(listOf("pi", "--mode", "rpc", "-e", bridgeFile.absolutePath), rt.last.spec.argv)
        waitFor { e.bridgeStatus.value.state == BridgeState.READY }
        assertEquals(1, e.bridgeStatus.value.version)
        e.close()
    }

    @Test
    fun noFileNoArgAndFallbacks() = runBlocking {
        val rt = ScriptedRuntime { script(ready = false) }
        val e = PiEngine(rt, StaticEnv(), log, scope, config())
        assertTrue(e.start() is Outcome.Ok)
        assertEquals("没安装就不加 -e", listOf("pi", "--mode", "rpc"), rt.last.spec.argv)
        assertEquals(BridgeState.UNAVAILABLE, e.bridgeStatus.value.state)
        // 退路：树内切换 → fork；标签 → 不可用；reload → 需要重启
        val nav = (e.treeNavigate("e1") as Outcome.Ok).value
        assertTrue(nav.viaFork)
        assertTrue(rt.last.received.any { it.str("type") == "fork" && it.str("entryId") == "e1" })
        val label = e.setEntryLabel("e1", "x")
        assertTrue(label is Outcome.Err && label.message.startsWith("标签不可用"))
        val reload = e.reloadResources()
        assertTrue(reload is Outcome.Err && reload.code == ErrCode.ENGINE_START && reload.message == PiBridge.RELOAD_NEEDS_RESTART)
        assertFalse("退路不发任何 /pigo: 命令", rt.last.received.any { it.str("type") == "prompt" })
        e.close()
    }

    @Test
    fun readyTimeoutFallsBack() = runBlocking {
        bridgeFile.parentFile.mkdirs(); bridgeFile.writeText("// x")
        val rt = ScriptedRuntime { script(ready = false) }
        val e = PiEngine(rt, StaticEnv(), log, scope, config(readyMs = 200))
        assertTrue(e.start() is Outcome.Ok)
        assertEquals(BridgeState.PENDING, e.bridgeStatus.value.state)
        assertTrue(e.setEntryLabel("e1", "x") is Outcome.Err)
        assertEquals(BridgeState.UNAVAILABLE, e.bridgeStatus.value.state)
        e.close()
    }

    @Test
    fun extensionErrorMarksUnavailableAndIsNotShown() = runBlocking {
        bridgeFile.parentFile.mkdirs(); bridgeFile.writeText("// x")
        val rt = ScriptedRuntime {
            { cmd ->
                if (cmd.str("type") == "get_state") emit("""{"type":"extension_error","extensionPath":"${bridgeFile.absolutePath}","event":"load","error":"SyntaxError"}""")
                script(ready = false)(this, cmd)
            }
        }
        val e = PiEngine(rt, StaticEnv(), log, scope, config())
        assertTrue(e.start() is Outcome.Ok)
        waitFor { e.bridgeStatus.value.state == BridgeState.UNAVAILABLE }
        assertTrue(e.bridgeStatus.value.reason!!.contains("SyntaxError"))
        assertTrue(e.treeNavigate("e1").let { it is Outcome.Ok && it.value.viaFork })
        e.close()
    }

    @Test
    fun labelRoundTripNotForwardedToUi() = runBlocking {
        bridgeFile.parentFile.mkdirs(); bridgeFile.writeText("// x")
        val rt = ScriptedRuntime {
            script { m ->
                val parts = m.removePrefix("/pigo:label ").split(" ", limit = 2)
                notify("""{"cmd":"label","ok":true,"entryId":"${parts[0]}","label":${JsonPrimitive(parts.getOrElse(1) { "" })}}""")
            }
        }
        val e = PiEngine(rt, StaticEnv(), log, scope, config())
        val seen = CopyOnWriteArrayList<ExtUiRequest>()
        scope.launch { e.uiRequests.collect { seen += it } }
        assertTrue(e.start() is Outcome.Ok)
        val itemsBefore = e.chat.value.items.size
        assertEquals(Outcome.Ok(Unit), e.setEntryLabel("e2", "书签\n一"))
        val sent = rt.last.received.last { it.str("type") == "prompt" }
        assertEquals("换行压成空格", "/pigo:label e2 书签 一", sent.str("message"))
        assertEquals("不进聊天时间线", itemsBefore, e.chat.value.items.size)
        delay(100)
        assertTrue("桥接 notify 不交给界面", seen.none { it.message?.startsWith("pigo-bridge:") == true })
        assertTrue(e.setEntryLabel("bad id; rm", "x") is Outcome.Err)
        e.close()
    }

    @Test
    fun labelFailureReported() = runBlocking {
        bridgeFile.parentFile.mkdirs(); bridgeFile.writeText("// x")
        val rt = ScriptedRuntime { script { notify("""{"cmd":"label","ok":false,"error":"没有这条记录：zz"}""") } }
        val e = PiEngine(rt, StaticEnv(), log, scope, config())
        assertTrue(e.start() is Outcome.Ok)
        val r = e.setEntryLabel("zz", "x")
        assertTrue(r is Outcome.Err && r.message.contains("没有这条记录"))
        e.close()
    }

    @Test
    fun treeNavRebuildsTimeline() = runBlocking {
        bridgeFile.parentFile.mkdirs(); bridgeFile.writeText("// x")
        val rt = ScriptedRuntime {
            script { m ->
                assertEquals("/pigo:tree-nav e1 --summarize", m)
                notify("""{"cmd":"tree-nav","ok":true,"cancelled":false,"leafId":"e1","sessionFile":"/root/.pi/agent/sessions/--root-pi-cwd--/s1.jsonl"}""")
            }
        }
        val e = PiEngine(rt, StaticEnv(), log, scope, config())
        assertTrue(e.start() is Outcome.Ok)
        val before = rt.last.received.count { it.str("type") == "get_messages" }
        val r = (e.treeNavigate("e1", summarize = true) as Outcome.Ok).value
        assertEquals("e1", r.leafId)
        assertFalse(r.viaFork)
        assertEquals("/root/.pi/agent/sessions/--root-pi-cwd--/s1.jsonl", r.sessionFile)
        assertTrue("切换后重建时间线", rt.last.received.count { it.str("type") == "get_messages" } > before)
        assertFalse("树内切换不走 fork", rt.last.received.any { it.str("type") == "fork" })
        e.close()
    }

    @Test
    fun reloadWaitsForNewInstanceReady() = runBlocking {
        bridgeFile.parentFile.mkdirs(); bridgeFile.writeText("// x")
        val rt = ScriptedRuntime { script { later(150) { notify("""{"event":"ready","version":1,"reason":"reload"}""") } } }
        val e = PiEngine(rt, StaticEnv(), log, scope, config())
        assertTrue(e.start() is Outcome.Ok)
        val t0 = System.currentTimeMillis()
        assertEquals(Outcome.Ok(Unit), e.reloadResources())
        assertTrue("等到 reason=reload 的 ready", System.currentTimeMillis() - t0 >= 140)
        assertEquals(BridgeState.READY, e.bridgeStatus.value.state)
        e.close()
    }

    @Test
    fun switchViaBridgeWaitsForResume() = runBlocking {
        bridgeFile.parentFile.mkdirs(); bridgeFile.writeText("// x")
        val rt = ScriptedRuntime { script { m ->
            assertEquals("/pigo:switch /root/.pi/agent/sessions/x y.jsonl", m)
            later(50) { notify("""{"event":"ready","version":1,"reason":"resume","sessionFile":"/root/.pi/agent/sessions/x y.jsonl"}""") }
        } }
        val e = PiEngine(rt, StaticEnv(), log, scope, config())
        assertTrue(e.start() is Outcome.Ok)
        assertEquals(Outcome.Ok(Unit), e.switchSessionViaBridge("/root/.pi/agent/sessions/x y.jsonl"))
        assertTrue(e.switchSessionViaBridge("a\nb") is Outcome.Err)
        e.close()
    }

    @Test
    fun noReplyTimesOut() = runBlocking {
        bridgeFile.parentFile.mkdirs(); bridgeFile.writeText("// x")
        val rt = ScriptedRuntime { script { /* 不回 */ } }
        val e = PiEngine(rt, StaticEnv(), log, scope, config())
        assertTrue(e.start() is Outcome.Ok)
        val r = e.setEntryLabel("e1", "x")
        assertTrue(r is Outcome.Err && r.code == ErrCode.RPC_TIMEOUT)
        e.close()
    }

    @Test
    fun bridgeCommandsHiddenFromSlashList() = runBlocking {
        val rt = ScriptedRuntime { script() }
        val e = PiEngine(rt, StaticEnv(), log, scope, config())
        val names = (e.commands(refresh = true) as Outcome.Ok).value.map { it.name }
        assertEquals(listOf("tpl"), names)
        e.close()
    }

    @Test
    fun debugEnginePidUsesResolver() = runBlocking {
        val rt = ScriptedRuntime { script() }
        val e = PiEngine(rt, StaticEnv(), log, scope, config(), enginePidResolver = { it + 1 })
        assertEquals(null, e.debugEnginePid())
        assertTrue(e.start() is Outcome.Ok)
        assertEquals(rt.last.prootPid + 1, e.debugEnginePid())
        e.close()
        waitFor { e.debugEnginePid() == null }
        assertNotNull(rt.last)
    }
}
