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
import kotlinx.coroutines.async
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

    private fun config(
        bridge: String? = bridgeFile.absolutePath,
        readyMs: Long = 2_000,
        slowMs: Long = 1_000,
        summarizeMs: Long = 1_000,
        lateMs: Long = 5_000,
    ) = EngineConfig(
        frameMs = 0, bootTimeoutMs = 2_000, abortTimeoutMs = 300, defaultTimeoutMs = 1_000, getMessagesTimeoutMs = 1_000,
        stopGraceMs = 200, bridgeExtension = bridge, bridgeReadyTimeoutMs = readyMs, bridgeReplyTimeoutMs = 1_000,
        bridgeSlowTimeoutMs = slowMs, bridgeSummarizeTimeoutMs = summarizeMs, bridgeLateWindowMs = lateMs,
    )

    private val history = """{"messages":[{"role":"user","content":"第一句"},{"role":"assistant","provider":"p","model":"m1","content":[{"type":"text","text":"答一"}],"stopReason":"stop"}]}"""

    private fun ScriptedPi.notify(payload: String) =
        emit("""{"type":"extension_ui_request","id":"n${System.nanoTime()}","method":"notify","message":${JsonPrimitive("pigo-bridge:$payload")},"notifyType":"info"}""")

    /**
     * 假 pi：第一次 get_state 前回 ready（[ready] 为假时不回）；prompt 里的 /pigo:* 按 [onBridge] 回传。
     * [promptAfterMs] 非空时像真 pi 1.5.0 那样：先执行扩展命令（[onBridge]），过这么久才回 `prompt` 的 response
     * （agent-session.js 的 prompt() 先 await 扩展 handler）。[state] 是 get_state 的数据。
     */
    private fun script(
        ready: Boolean = true,
        promptAfterMs: Long? = null,
        state: () -> String = { ScriptedPi.STATE },
        onBridge: ScriptedPi.(String) -> Unit = {},
    ): ScriptedPi.(JsonObject) -> Unit {
        var announced = false
        return { cmd ->
            when (cmd.str("type")) {
                "get_state" -> {
                    if (ready && !announced) { announced = true; notify("""{"event":"ready","version":1,"reason":"startup"}""") }
                    ok(cmd, state())
                }
                "get_tree" -> ok(cmd, """{"tree":[],"leafId":"pi-leaf"}""")
                "get_fork_messages" -> ok(cmd, """{"messages":[{"entryId":"e1","text":"第一句"}]}""")
                "get_messages" -> ok(cmd, history)
                "get_entries" -> ok(cmd, """{"entries":[],"leafId":null}""")
                "get_commands" -> ok(cmd, """{"commands":[{"name":"pigo:label","source":"extension"},{"name":"tpl","source":"prompt"},{"name":"pigo:reload","source":"extension"}]}""")
                "fork" -> ok(cmd, """{"text":"第一句","cancelled":false}""")
                "prompt" -> {
                    val m = cmd.str("message").orEmpty()
                    if (promptAfterMs != null && m.startsWith("/pigo:")) {
                        onBridge(m)
                        later(promptAfterMs) { ok(cmd, """{"disposition":"handled"}""") }
                    } else {
                        ok(cmd, """{"disposition":"handled"}""")
                        if (m.startsWith("/pigo:")) onBridge(m)
                    }
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
        assertEquals("fork 退路带回原文（审查 Nit 3）", "第一句", nav.editorText)
        assertTrue(rt.last.received.any { it.str("type") == "fork" && it.str("entryId") == "e1" })
        // 选中的不是用户消息（不在 get_fork_messages 里）：直接说明，不发 fork
        val forks = rt.last.received.count { it.str("type") == "fork" }
        val notUser = e.treeNavigate("a7")
        assertTrue(notUser is Outcome.Err && notUser.message.contains("只能从用户消息分叉"))
        assertEquals(forks, rt.last.received.count { it.str("type") == "fork" })
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

    // ---------------------------------------------------------------- 审查第 1 轮（PR #16）

    @Test
    fun slowSummarizeTreeNavUsesSummarizeTimeoutNotPromptDefault() = runBlocking {
        // Important 1：真 pi 要等 handler（写分支摘要）执行完才回 prompt 的 response。回复在 1.5 s 才到，
        // 超过 defaultTimeoutMs（1 s）与 bridgeReplyTimeoutMs（1 s），但在 bridgeSummarizeTimeoutMs（5 s）内 → 正常成功。
        bridgeFile.parentFile.mkdirs(); bridgeFile.writeText("// x")
        val rt = ScriptedRuntime {
            script(promptAfterMs = 1_600) {
                later(1_500) { notify("""{"cmd":"tree-nav","ok":true,"cancelled":false,"leafId":"e1","sessionFile":"/root/.pi/agent/sessions/--root-pi-cwd--/s1.jsonl","editorText":"第一句"}""") }
            }
        }
        val e = PiEngine(rt, StaticEnv(), log, scope, config(summarizeMs = 5_000))
        assertTrue(e.start() is Outcome.Ok)
        val before = rt.last.received.count { it.str("type") == "get_messages" }
        val r = e.treeNavigate("e1", summarize = true)
        assertTrue("慢的摘要切换不当失败：$r", r is Outcome.Ok)
        val v = (r as Outcome.Ok).value
        assertEquals("e1", v.leafId)
        assertFalse(v.unconfirmed)
        assertEquals("第一句", v.editorText)
        assertTrue("切换后重建时间线", rt.last.received.count { it.str("type") == "get_messages" } > before)
        delay(300)
        assertEquals("prompt 慢不触发探活重启", 1, rt.processes.size)
        e.close()
    }

    @Test
    fun treeNavTimeoutReconcilesWithPiStateInsteadOfFailing() = runBlocking {
        // Important 1：超时内没收到回复 → 按 pi 当前状态对账（get_state + get_messages + get_tree），返回 unconfirmed；
        // 迟到的回复到了再重建一次。
        bridgeFile.parentFile.mkdirs(); bridgeFile.writeText("// x")
        val rt = ScriptedRuntime {
            script(promptAfterMs = 1_000) {
                later(900) { notify("""{"cmd":"tree-nav","ok":true,"cancelled":false,"leafId":"e1","sessionFile":"/root/.pi/agent/sessions/--root-pi-cwd--/s1.jsonl"}""") }
            }
        }
        val e = PiEngine(rt, StaticEnv(), log, scope, config(slowMs = 300))
        assertTrue(e.start() is Outcome.Ok)
        val before = rt.last.received.count { it.str("type") == "get_messages" }
        val r = e.treeNavigate("e1")
        assertTrue("超时不当失败：$r", r is Outcome.Ok)
        val v = (r as Outcome.Ok).value
        assertTrue(v.unconfirmed)
        assertEquals("leafId 取 pi 当前的（get_tree）", "pi-leaf", v.leafId)
        val afterReconcile = rt.last.received.count { it.str("type") == "get_messages" }
        assertTrue("对账时重建时间线", afterReconcile > before)
        assertTrue(rt.last.received.any { it.str("type") == "get_tree" })
        waitFor { rt.last.received.count { it.str("type") == "get_messages" } > afterReconcile }
        e.close()
    }

    @Test
    fun switchTimeoutReconcilesWhenPiAlreadyOnTarget() = runBlocking {
        // Important 1：切大会话超时，但 get_state 显示已经在目标会话 → 成功并重建时间线。
        bridgeFile.parentFile.mkdirs(); bridgeFile.writeText("// x")
        val target = "/root/.pi/agent/sessions/--root-pi-cwd--/big.jsonl"
        val switched = java.util.concurrent.atomic.AtomicBoolean(false)
        val rt = ScriptedRuntime {
            script(promptAfterMs = 2_000, state = {
                if (switched.get()) ScriptedPi.STATE.replace("/root/.pi/agent/sessions/--root-pi-cwd--/s1.jsonl", target) else ScriptedPi.STATE
            }) { switched.set(true) /* 已切换，但 ready 迟迟不来 */ }
        }
        val e = PiEngine(rt, StaticEnv(), log, scope, config(slowMs = 300))
        assertTrue(e.start() is Outcome.Ok)
        val before = rt.last.received.count { it.str("type") == "get_messages" }
        assertEquals(Outcome.Ok(Unit), e.switchSessionViaBridge(target))
        assertEquals(target, e.currentSessionFile)
        assertTrue(rt.last.received.count { it.str("type") == "get_messages" } > before)
        e.close()
    }

    @Test
    fun switchCancelledByExtensionFailsFast() = runBlocking {
        // Nit 2：被别的扩展取消时扩展回 {cmd:switch, ok:true, cancelled:true}，要立即报错而不是等到超时。
        bridgeFile.parentFile.mkdirs(); bridgeFile.writeText("// x")
        val rt = ScriptedRuntime { script { notify("""{"cmd":"switch","ok":true,"cancelled":true,"sessionFile":"/x.jsonl"}""") } }
        val e = PiEngine(rt, StaticEnv(), log, scope, config(slowMs = 5_000))
        assertTrue(e.start() is Outcome.Ok)
        val t0 = System.currentTimeMillis()
        val r = e.switchSessionViaBridge("/x.jsonl")
        assertTrue(r is Outcome.Err && r.code == ErrCode.CANCELLED)
        assertTrue("不等超时", System.currentTimeMillis() - t0 < 2_000)
        e.close()
    }

    @Test
    fun rejectedPromptFailsFast() = runBlocking {
        bridgeFile.parentFile.mkdirs(); bridgeFile.writeText("// x")
        val rt = ScriptedRuntime {
            val base = script()
            val h: ScriptedPi.(JsonObject) -> Unit = { cmd -> if (cmd.str("type") == "prompt") fail(cmd, "busy") else base(this, cmd) }
            h
        }
        val e = PiEngine(rt, StaticEnv(), log, scope, config(slowMs = 5_000))
        assertTrue(e.start() is Outcome.Ok)
        val t0 = System.currentTimeMillis()
        val r = e.reloadResources()
        assertTrue(r is Outcome.Err && r.code == ErrCode.RPC_REJECTED)
        assertTrue("不等超时", System.currentTimeMillis() - t0 < 2_000)
        e.close()
    }

    @Test
    fun concurrentLabelsGetTheirOwnReplies() = runBlocking {
        // Nit 1：两个 setEntryLabel 同时在途，各自拿到自己的结果（e1 成功、e2 失败），不会被第一条回复一起完成。
        bridgeFile.parentFile.mkdirs(); bridgeFile.writeText("// x")
        val rt = ScriptedRuntime {
            script { m ->
                val id = m.removePrefix("/pigo:label ").substringBefore(' ')
                later(100) {
                    if (id == "e1") notify("""{"cmd":"label","ok":true,"entryId":"e1","label":"a"}""")
                    else notify("""{"cmd":"label","ok":false,"entryId":"$id","error":"没有这条记录：$id"}""")
                }
            }
        }
        val e = PiEngine(rt, StaticEnv(), log, scope, config())
        assertTrue(e.start() is Outcome.Ok)
        val a = async { e.setEntryLabel("e1", "a") }
        val b = async { e.setEntryLabel("e2", "b") }
        assertEquals(Outcome.Ok(Unit), a.await())
        val rb = b.await()
        assertTrue("e2 的失败不被 e1 的成功吞掉：$rb", rb is Outcome.Err && rb.message.contains("e2"))
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
