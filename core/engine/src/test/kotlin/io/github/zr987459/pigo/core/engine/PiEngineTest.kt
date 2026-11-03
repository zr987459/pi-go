package io.github.zr987459.pigo.core.engine

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.GuestSpec
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.core.protocol.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PiEngine 的 JVM 单测：用脚本化的假 pi 进程覆盖启动、发送、排队/插话、停止、Q5、崩溃恢复（N2）、N1。 */
class PiEngineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wake = InMemoryWakeLock()
    private val marker = InMemoryRunMarker()
    private val log = ListLog()
    private val fast = EngineConfig(
        frameMs = 0,
        bootTimeoutMs = 2_000,
        abortTimeoutMs = 300,
        defaultTimeoutMs = 1_000,
        getMessagesTimeoutMs = 1_000,
        restartBackoffMs = listOf(50, 100, 150),
        stopGraceMs = 200,
        uiRequestGraceMs = 100,
    )

    @After
    fun tearDown() = scope.cancel()

    private fun engine(
        runtime: ScriptedRuntime,
        config: EngineConfig = fast,
        runMarker: RunMarkerStore = marker,
        foreground: ForegroundPort = NoForeground,
    ) = PiEngine(runtime, StaticEnv(), log, scope, config, wake, runMarker, foreground)

    /** 记录前台服务启停调用。 */
    private class RecordingForeground : ForegroundPort {
        val calls: MutableList<Boolean> = java.util.Collections.synchronizedList(mutableListOf())
        override fun onEngineActive(active: Boolean) {
            calls += active
        }
    }

    private fun ChatItem.isNotice(kind: NoticeKind, prefix: String = "") = this is ChatItem.Notice && this.kind == kind && text.startsWith(prefix)

    private suspend fun waitFor(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        withTimeout(timeoutMs) { while (!cond()) delay(10) }
    }

    /** 标准应答：get_state / get_messages / 其他命令一律成功。 */
    private val basics: ScriptedPi.(JsonObject) -> Unit = { cmd ->
        when (cmd.str("type")) {
            "get_state" -> ok(cmd, ScriptedPi.STATE)
            "get_messages" -> ok(cmd, """{"messages":[{"role":"user","content":"旧消息"},{"role":"assistant","content":[{"type":"text","text":"旧回答"}],"stopReason":"stop"}]}""")
            "get_session_stats" -> ok(cmd, """{"contextUsage":{"percent":12.5},"tokens":{"total":10},"cost":0.01}""")
            else -> ok(cmd)
        }
    }

    private fun ScriptedPi.runTurn(text: String, reply: String) {
        emit("""{"type":"agent_start"}""")
        emit("""{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"$text"}]}}""")
        emit("""{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""")
        emit("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"$reply"}}""")
        emit("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"$reply"}],"stopReason":"stop"}}""")
        emit("""{"type":"agent_end","messages":[],"willRetry":false}""")
    }

    private fun ScriptedPi.emitLater(json: String) = later(0) { emit(json) }

    @Test
    fun promptReachesSettledAndWakeLockOnlyDuringRun() = runBlocking {
        val rt = ScriptedRuntime {
            { cmd ->
                if (cmd.str("type") == "prompt") {
                    ok(cmd, """{"disposition":"started"}""")
                    runTurn(cmd.str("message")!!, "好的")
                    later(300) { emit("""{"type":"agent_settled","aborted":false}""") }
                } else {
                    basics(cmd)
                }
            }
        }
        val e = engine(rt)
        assertTrue(e.start() is Outcome.Ok)
        assertTrue(e.status.value is EngineStatus.Idle)
        assertFalse(e.wakeLockHeld) // N1：空闲不持锁
        val spec = rt.last.spec
        assertEquals(listOf("pi", "--mode", "rpc"), spec.argv)
        assertEquals("1", spec.env["PI_OFFLINE"])
        assertEquals("sk-test-secret-0001", spec.env["DEEPSEEK_API_KEY"])

        assertEquals(Outcome.Ok(Disposition.STARTED), e.send("你好"))
        assertNull(rt.last.received.last().str("streamingBehavior"))
        waitFor { e.chat.value.running }
        val heldDuringRun = e.wakeLockHeld
        assertTrue(e.status.value is EngineStatus.Running)
        assertTrue(marker.get() != null) // 运行标记
        waitFor { !e.chat.value.running }
        assertTrue("运行中应持有唤醒锁", heldDuringRun)
        assertFalse("settled 后必须释放唤醒锁（N1）", e.wakeLockHeld)
        assertNull(marker.get())
        val items = e.chat.value.items
        assertEquals(Delivery.SENT, (items[0] as ChatItem.User).delivery)
        assertEquals("好的", ((items[1] as ChatItem.Assistant).blocks.single() as ContentBlock.Text).text)
        assertTrue(e.status.value is EngineStatus.Idle)
        // settled 后自动刷新上下文占用
        waitFor { e.chat.value.contextPercent == 12.5 }
        e.close()
        assertTrue(rt.last.stdinClosed)
        assertFalse(e.wakeLockHeld)
        assertTrue(log.lines.none { it.message.contains("sk-test-secret-0001") })
    }

    @Test
    fun sendWhileRunningQueuesWithFollowUpOrSteer() = runBlocking {
        val rt = ScriptedRuntime {
            { cmd ->
                when {
                    cmd.str("type") == "prompt" && cmd.str("streamingBehavior") == null -> {
                        ok(cmd, """{"disposition":"started"}""")
                        emit("""{"type":"agent_start"}""")
                    }
                    cmd.str("type") == "prompt" -> {
                        ok(cmd, """{"disposition":"queued"}""")
                        emit("""{"type":"queue_update","steering":[],"followUp":["${cmd.str("message")}"]}""")
                    }
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        e.send("第一条")
        waitFor { e.chat.value.running }
        assertEquals(Outcome.Ok(Disposition.QUEUED), e.send("排队", SendMode.FOLLOW_UP))
        assertEquals("followUp", rt.last.received.last().str("streamingBehavior"))
        assertEquals(Outcome.Ok(Disposition.QUEUED), e.send("插话", SendMode.STEER))
        assertEquals("steer", rt.last.received.last().str("streamingBehavior"))
        val users = e.chat.value.items.filterIsInstance<ChatItem.User>()
        assertEquals(listOf(Delivery.SENT, Delivery.QUEUED, Delivery.QUEUED), users.map { it.delivery })
        waitFor { e.chat.value.queue.followUp.isNotEmpty() }
        e.close()
    }

    @Test
    fun busyRejectionIsRetriedOnceWithStreamingBehavior() = runBlocking {
        val rt = ScriptedRuntime {
            { cmd ->
                if (cmd.str("type") == "prompt" && cmd.str("streamingBehavior") == null) {
                    fail(cmd, "Agent is already processing. Specify streamingBehavior ('steer' or 'followUp') to queue the message.")
                } else if (cmd.str("type") == "prompt") {
                    ok(cmd, """{"disposition":"queued"}""")
                } else {
                    basics(cmd)
                }
            }
        }
        val e = engine(rt)
        assertEquals(Outcome.Ok(Disposition.QUEUED), e.send("x", SendMode.STEER))
        val prompts = rt.last.received.filter { it.str("type") == "prompt" }
        assertEquals(listOf(null, "steer"), prompts.map { it.str("streamingBehavior") })
        e.close()
    }

    @Test
    fun handledDispositionDoesNotWaitForSettled() = runBlocking {
        val rt = ScriptedRuntime { { cmd -> if (cmd.str("type") == "prompt") ok(cmd, """{"disposition":"handled"}""") else basics(cmd) } }
        val e = engine(rt)
        assertEquals(Outcome.Ok(Disposition.HANDLED), withTimeout(2_000) { e.send("/ext-cmd") })
        assertFalse(e.chat.value.running)
        assertEquals(Delivery.SENT, (e.chat.value.items.single() as ChatItem.User).delivery)
        assertFalse(e.wakeLockHeld)
        e.close()
    }

    @Test
    fun rejectedPromptIsMarkedFailedAndNoModelIsRecognised() = runBlocking {
        val rt = ScriptedRuntime {
            { cmd -> if (cmd.str("type") == "prompt") fail(cmd, "No API key found for the selected model.") else basics(cmd) }
        }
        val e = engine(rt)
        val r = e.send("hi") as Outcome.Err
        assertEquals(ErrCode.NO_MODEL, r.code)
        assertEquals(Delivery.FAILED, (e.chat.value.items.single() as ChatItem.User).delivery)
        // 不会自动重发
        assertEquals(1, rt.last.received.count { it.str("type") == "prompt" })
        e.close()
    }

    @Test
    fun stopClearsQueueBeforeAbortAndReturnsWithdrawnText() = runBlocking {
        val rt = ScriptedRuntime {
            { cmd ->
                when (cmd.str("type")) {
                    "prompt" -> if (cmd.str("streamingBehavior") == null) {
                        ok(cmd, """{"disposition":"started"}""")
                        emit("""{"type":"agent_start"}""")
                    } else {
                        ok(cmd, """{"disposition":"queued"}""")
                    }
                    "clear_queue" -> {
                        emit("""{"type":"queue_update","steering":[],"followUp":[]}""")
                        ok(cmd, """{"steering":["插话"],"followUp":["排队"]}""")
                    }
                    "abort" -> later(50) {
                        emit("""{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"aborted"}}""")
                        emit("""{"type":"agent_settled","aborted":true}""")
                        ok(cmd)
                    }
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        e.send("开始")
        waitFor { e.chat.value.running }
        e.send("排队", SendMode.FOLLOW_UP)
        e.send("插话", SendMode.STEER)
        val r = e.stop() as Outcome.Ok
        assertEquals(listOf("插话"), r.value.steering)
        assertEquals(listOf("排队"), r.value.followUp)
        val types = rt.last.received.map { it.str("type") }
        assertTrue(types.indexOf("clear_queue") < types.indexOf("abort"))
        waitFor { !e.chat.value.running }
        // 撤回的气泡已移除，不会让人以为它们还会发出
        assertEquals(listOf("开始"), e.chat.value.items.filterIsInstance<ChatItem.User>().map { it.text })
        assertTrue(e.chat.value.items.any { it is ChatItem.Notice && it.kind == NoticeKind.STOPPED })
        assertFalse(e.wakeLockHeld)
        assertEquals(1, rt.processes.size)
        e.close()
    }

    @Test
    fun abortTimeoutKillsOnlyOwnProcessAndRestartsWithSameSession() = runBlocking {
        // Q5：abort 不应答 → 只结束本进程树 → 以 --session 重启 → get_messages 重建 → 标「已强制停止」
        val rt = ScriptedRuntime {
            { cmd ->
                when (cmd.str("type")) {
                    "prompt" -> {
                        ok(cmd, """{"disposition":"started"}""")
                        emit("""{"type":"agent_start"}""")
                    }
                    "abort" -> Unit // 永不应答
                    else -> basics(cmd)
                }
            }
        }
        val unrelated = ScriptedPi(GuestSpec(listOf("node", "other.js"))) { }
        val e = engine(rt)
        e.send("跑个很久的任务")
        waitFor { e.chat.value.running }
        val first = rt.last
        assertTrue(e.wakeLockHeld)
        val r = withTimeout(5_000) { e.stop() }
        assertTrue(r is Outcome.Ok)
        assertEquals(2, rt.processes.size)
        assertEquals(listOf(0L), first.stopCalls.toList()) // 强杀（不再等 5 s）
        assertTrue(first.exited)
        assertFalse("不得影响别的进程", unrelated.exited)
        val second = rt.last
        assertEquals(listOf("pi", "--mode", "rpc", "--session", "/root/.pi/agent/sessions/--root-pi-cwd--/s1.jsonl"), second.spec.argv)
        assertTrue(second.received.any { it.str("type") == "get_messages" })
        val items = e.chat.value.items
        assertEquals("旧消息", (items.first() as ChatItem.User).text)
        assertEquals(NoticeKind.STOPPED, (items.last() as ChatItem.Notice).kind)
        assertEquals("已强制停止", (items.last() as ChatItem.Notice).text)
        // 审查 #1：Q5 重建不是冷启动，不能出现「上一轮被系统中断」
        assertTrue(items.none { it.isNotice(NoticeKind.INTERRUPTED) })
        assertFalse(e.chat.value.running)
        assertFalse(e.wakeLockHeld)
        assertTrue(e.status.value is EngineStatus.Idle)
        unrelated.exitNow(0)
        e.close()
    }

    @Test
    fun crashIsRecoveredWithBackoffAndSessionIsRestored() = runBlocking {
        val rt = ScriptedRuntime { n ->
            { cmd ->
                when (cmd.str("type")) {
                    "prompt" -> {
                        ok(cmd, """{"disposition":"started"}""")
                        emit("""{"type":"agent_start"}""")
                        if (n == 1) crash(134, "<--- Last few GCs --->", "FATAL ERROR: Reached heap limit Allocation failed - JavaScript heap out of memory")
                    }
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        val seen = mutableListOf<EngineStatus>()
        val collector = scope.launch { e.status.collect { synchronized(seen) { seen += it } } }
        e.send("会把 pi 撑爆")
        waitFor {
            rt.processes.size == 2 && e.status.value is EngineStatus.Idle &&
                e.chat.value.items.lastOrNull().let { it is ChatItem.Notice && it.kind == NoticeKind.INTERRUPTED }
        }
        val crashed = synchronized(seen) { seen.filterIsInstance<EngineStatus.Crashed>() }.first()
        assertEquals(ErrCode.OOM, crashed.kind)
        assertEquals(134, crashed.exitCode)
        assertEquals(50L, crashed.retryInMs)
        assertTrue(crashed.stderrTail.any { it.contains("heap out of memory") })
        assertEquals("--session", rt.last.spec.argv[3])
        assertFalse(e.chat.value.running)
        assertFalse(e.wakeLockHeld)
        assertTrue(e.chat.value.items.any { it is ChatItem.Notice && it.kind == NoticeKind.INTERRUPTED })
        // 审查 #1：崩溃恢复不做冷启动检查
        assertTrue(e.chat.value.items.none { it is ChatItem.Notice && it.text == "上一轮被系统中断" })
        assertNull(marker.get())
        collector.cancel()
        e.close()
    }

    @Test
    fun givesUpAfterThreeFailedRestarts() = runBlocking {
        val rt = ScriptedRuntime { n ->
            { cmd ->
                if (n == 1) {
                    basics(cmd)
                    if (cmd.str("type") == "get_state") crash(1, "boom")
                } else {
                    // 之后每次启动都在回应 get_state 前退出
                    crash(1, "Error: Cannot find module '/usr/lib/node_modules/@earendil-works/pi-coding-agent/dist/cli.js'")
                }
            }
        }
        val e = engine(rt)
        e.start()
        waitFor(10_000) { rt.processes.size == 4 && (e.status.value as? EngineStatus.Crashed)?.retryInMs == null }
        delay(500)
        val st = e.status.value as EngineStatus.Crashed
        assertNull(st.retryInMs)
        assertEquals(ErrCode.ENGINE_START, st.kind)
        assertEquals("pi 安装不完整", st.hint)
        assertEquals("最多自动重启 3 次", 4, rt.processes.size)
        assertFalse(e.wakeLockHeld)
        e.close()
    }

    @Test
    fun bootFailureIsClassifiedAndSendReturnsError() = runBlocking {
        val rt = ScriptedRuntime { { _ -> crash(1, "proot error: ptrace(TRACEME): Operation not permitted") } }
        val e = engine(rt)
        val r = e.send("hi") as Outcome.Err
        assertEquals(ErrCode.ENGINE_START, r.code)
        val st = e.status.value as EngineStatus.Crashed
        assertEquals("容器无法启动（系统限制）", st.hint)
        assertNull(st.retryInMs)
        assertTrue(e.chat.value.items.isEmpty())
        delay(300)
        assertEquals("启动失败不自动重启", 1, rt.processes.size)
        e.close()
    }

    @Test
    fun bootTimeoutKillsProcess() = runBlocking {
        val rt = ScriptedRuntime { { _ -> } }
        val e = engine(rt, fast.copy(bootTimeoutMs = 200))
        assertTrue(e.start() is Outcome.Err)
        assertTrue(rt.last.exited)
        assertEquals(listOf(0L), rt.last.stopCalls.toList())
        assertTrue(e.status.value is EngineStatus.Crashed)
        e.close()
    }

    @Test
    fun runtimeStartExceptionBecomesCrashed() = runBlocking {
        val rt = ScriptedRuntime { basics }
        rt.failStart = IllegalStateException("No space left on device (ENOSPC)")
        val e = engine(rt)
        val r = e.start() as Outcome.Err
        assertEquals(ErrCode.DISK_FULL, r.code)
        e.close()
    }

    @Test
    fun blockingUiRequestWithoutListenerIsCancelledAndWithListenerIsDelivered() = runBlocking {
        val rt = ScriptedRuntime { { cmd -> basics(cmd) } }
        val e = engine(rt)
        e.start()
        rt.last.emitLater("""{"type":"extension_ui_request","id":"u1","method":"confirm","title":"确定？"}""")
        waitFor { rt.last.received.any { it.str("type") == "extension_ui_response" } }
        val resp = rt.last.received.last { it.str("type") == "extension_ui_response" }
        assertEquals("u1", resp.str("id"))
        assertEquals("true", resp["cancelled"].toString())

        val got = async { withTimeout(3_000) { e.uiRequests.first() } }
        delay(200)
        rt.last.emitLater("""{"type":"extension_ui_request","id":"u2","method":"select","title":"选","options":["A","B"],"timeout":5000}""")
        val req: ExtUiRequest = got.await()
        assertEquals("u2", req.id)
        assertEquals(listOf("A", "B"), req.options)
        assertEquals(5000L, req.timeoutMs)
        assertTrue(req.needsResponse)
        e.answerUi("u2", value = "B")
        waitFor { rt.last.received.any { it.str("id") == "u2" } }
        assertEquals("B", rt.last.received.last().str("value"))
        // 状态条（不需要应答）
        rt.last.emitLater("""{"type":"extension_ui_request","id":"u3","method":"setStatus","statusKey":"k","statusText":"第 3 轮"}""")
        waitFor { e.chat.value.statusLine == "第 3 轮" }
        assertFalse(rt.last.received.any { it.str("id") == "u3" })
        e.close()
    }

    @Test
    fun openSessionSameCwdSwitchesOtherwiseRestarts() = runBlocking {
        val rt = ScriptedRuntime { { cmd -> if (cmd.str("type") == "switch_session") ok(cmd, """{"cancelled":false}""") else basics(cmd) } }
        val e = engine(rt)
        e.start()
        assertTrue(e.openSession("/root/.pi/agent/sessions/--root-pi-cwd--/s2.jsonl", "/root/pi-cwd") is Outcome.Ok)
        assertEquals(1, rt.processes.size)
        val types = rt.last.received.map { it.str("type") }
        assertTrue(types.containsAll(listOf("switch_session", "get_messages")))
        assertTrue(types.indexOf("switch_session") < types.lastIndexOf("get_messages"))
        assertEquals("旧消息", (e.chat.value.items.first() as ChatItem.User).text)

        assertTrue(e.openSession("/root/.pi/agent/sessions/--root-w2--/s3.jsonl", "/root/w2") is Outcome.Ok)
        assertEquals(2, rt.processes.size)
        assertEquals("/root/w2", rt.last.spec.cwd)
        assertEquals("/root/.pi/agent/sessions/--root-w2--/s3.jsonl", rt.last.spec.argv[4])
        assertTrue(rt.processes[0].exited)
        assertEquals(listOf(200L), rt.processes[0].stopCalls.toList()) // 有序关闭：先关 stdin 再等
        e.close()
    }

    @Test
    fun switchSessionCancelledByExtension() = runBlocking {
        val rt = ScriptedRuntime { { cmd -> if (cmd.str("type") == "switch_session") ok(cmd, """{"cancelled":true}""") else basics(cmd) } }
        val e = engine(rt)
        e.start()
        assertEquals(ErrCode.CANCELLED, (e.openSession("/x.jsonl", "/root/pi-cwd") as Outcome.Err).code)
        e.close()
    }

    @Test
    fun newSessionCancelledByExtensionOrRestartedInOtherCwd() = runBlocking {
        val rt = ScriptedRuntime { { cmd -> if (cmd.str("type") == "new_session") ok(cmd, """{"cancelled":true}""") else basics(cmd) } }
        val e = engine(rt)
        assertEquals(ErrCode.CANCELLED, (e.newSession() as Outcome.Err).code)
        val rt2 = ScriptedRuntime { basics }
        val e2 = engine(rt2)
        e2.start()
        assertTrue(e2.newSession() is Outcome.Ok)
        assertTrue(e2.newSession("/root/other") is Outcome.Ok)
        assertEquals("/root/other", rt2.last.spec.cwd)
        assertEquals(listOf("pi", "--mode", "rpc"), rt2.last.spec.argv)
        e.close()
        e2.close()
    }

    @Test
    fun modelThinkingStatsAndRename() = runBlocking {
        val rt = ScriptedRuntime {
            { cmd ->
                when (cmd.str("type")) {
                    "get_available_models" -> ok(cmd, """{"models":[{"id":"deepseek-chat","provider":"deepseek","name":"DeepSeek"}]}""")
                    "set_model" -> ok(cmd, """{"id":"${cmd.str("modelId")}","provider":"${cmd.str("provider")}","name":"X"}""")
                    "get_available_thinking_levels" -> ok(cmd, """{"levels":["off","low","high"]}""")
                    "set_session_name" -> {
                        ok(cmd)
                        emit("""{"type":"session_info_changed","name":"${cmd.str("name")}"}""")
                    }
                    "set_thinking_level" -> ok(cmd)
                    "abort_retry" -> ok(cmd)
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        assertEquals("deepseek-chat", (e.models() as Outcome.Ok).value.single().id)
        assertEquals("X", (e.setModel("deepseek", "deepseek-chat") as Outcome.Ok).value.name)
        assertEquals("deepseek", e.chat.value.model?.provider)
        assertEquals(listOf("off", "low", "high"), (e.thinkingLevels() as Outcome.Ok).value)
        assertTrue(e.setThinking("high") is Outcome.Ok)
        assertEquals("high", e.chat.value.thinkingLevel)
        assertEquals(12.5, (e.stats() as Outcome.Ok).value.contextPercent!!, 0.0)
        assertTrue(e.rename("修登录 bug") is Outcome.Ok)
        assertEquals("修登录 bug", e.chat.value.sessionName)
        assertTrue(e.abortRetry() is Outcome.Ok)
        assertTrue(e.restart("设置变更") is Outcome.Ok)
        assertEquals(2, rt.processes.size)
        assertEquals("--session", rt.last.spec.argv[3])
        e.close()
        // 关闭后的调用立即失败
        assertEquals(ErrCode.ENGINE_EXIT, (e.models() as Outcome.Err).code)
        assertEquals(ErrCode.ENGINE_EXIT, (e.stats() as Outcome.Err).code)
        assertEquals(Outcome.Ok(io.github.zr987459.pigo.contract.QueueSnapshot.EMPTY), e.stop())
    }

    @Test
    fun coldStartWithRunMarkerShowsInterrupted() = runBlocking {
        val rt = ScriptedRuntime { basics }
        val e = engine(rt, runMarker = InMemoryRunMarker("/root/.pi/agent/sessions/--root-pi-cwd--/s1.jsonl"))
        e.openSession("/root/.pi/agent/sessions/--root-pi-cwd--/s1.jsonl", "/root/pi-cwd")
        val last = e.chat.value.items.last() as ChatItem.Notice
        assertEquals(NoticeKind.INTERRUPTED, last.kind)
        assertEquals("上一轮被系统中断", last.text)
        e.close()
    }

    @Test
    fun commandTimeoutProbesAndForceRestartsWhenProbeAlsoTimesOut() = runBlocking {
        val hang = java.util.concurrent.atomic.AtomicBoolean(false)
        val rt = ScriptedRuntime { n ->
            { cmd ->
                if (n == 1 && hang.get()) {
                    Unit // 卡死：什么都不回
                } else if (n == 1 && cmd.str("type") == "get_available_models") {
                    hang.set(true)
                } else {
                    basics(cmd)
                }
            }
        }
        val e = engine(rt)
        e.start()
        assertEquals(ErrCode.RPC_TIMEOUT, (e.models() as Outcome.Err).code)
        waitFor(8_000) { rt.processes.size == 2 && e.status.value is EngineStatus.Idle }
        assertEquals(listOf(0L), rt.processes[0].stopCalls.toList())
        e.close()
    }

    @Test
    fun parseErrorsAndUnknownEventsDoNotBreakTheEngine() = runBlocking {
        val rt = ScriptedRuntime {
            { cmd ->
                if (cmd.str("type") == "get_state" && cmd.str("id")?.startsWith("boot-") != true) {
                    emit("""{"type":"response","command":"parse","success":false,"error":"Failed to parse command"}""")
                    emit("not json at all")
                    emit("""{"type":"totally_new_event"}""")
                    emit("""{"type":"extension_error","extensionPath":"/a.ts","event":"x","error":"boom"}""")
                }
                basics(cmd)
            }
        }
        val e = engine(rt)
        e.start()
        assertTrue(e.newSession() is Outcome.Ok) // 会触发一次 get_state
        waitFor { e.protocolErrors.get() >= 2 }
        waitFor { e.chat.value.items.any { it is ChatItem.Notice && it.text.startsWith("扩展出错") } }
        assertTrue(e.status.value is EngineStatus.Idle)
        e.close()
    }

    // ------------------------------------------------------------------ 审查修复

    @Test
    fun coldStartCheckIsConsumedByFirstBootEvenWithoutRebuild() = runBlocking {
        // 审查 #1：首次按需启动（无会话文件、rebuild=false）就消费冷启动检查；之后的重启不再误报。
        val m = InMemoryRunMarker("/root/.pi/agent/sessions/--gone--/missing.jsonl")
        val rt = ScriptedRuntime {
            { cmd ->
                if (cmd.str("type") == "prompt") {
                    ok(cmd, """{"disposition":"started"}""")
                    emit("""{"type":"agent_start"}""") // 运行中：标记被写入，然后被 restart 打断
                } else {
                    basics(cmd)
                }
            }
        }
        val e = engine(rt, runMarker = m)
        assertTrue(e.start() is Outcome.Ok)
        assertEquals("会话文件不存在：不回到它", listOf("pi", "--mode", "rpc"), rt.last.spec.argv)
        assertNull("首次启动后标记已清除", m.get())
        e.send("跑")
        waitFor { e.chat.value.running }
        assertTrue(m.get() != null)
        assertTrue(e.restart("设置变更") is Outcome.Ok)
        assertTrue(e.chat.value.items.none { it.isNotice(NoticeKind.INTERRUPTED, "上一轮被系统中断") })
        e.close()
    }

    @Test
    fun coldStartResumesInterruptedSessionOnFirstSendOrStart() = runBlocking {
        // 审查 #1：真实冷启动时第一个调用是 send/models/start，也要回到被中断的会话并提示。
        val dir = java.nio.file.Files.createTempDirectory("pigo-cold").toFile()
        val file = java.io.File(dir, "s9.jsonl").apply {
            writeText("""{"type":"session","version":3,"id":"s9","timestamp":"2026-01-01T00:00:00Z","cwd":"/root/proj"}""" + "\n")
        }
        val state = ScriptedPi.STATE.replace("/root/.pi/agent/sessions/--root-pi-cwd--/s1.jsonl", file.absolutePath)
        val rt = ScriptedRuntime {
            { cmd ->
                when (cmd.str("type")) {
                    "get_state" -> ok(cmd, state)
                    "get_messages" -> ok(cmd, """{"messages":[{"role":"user","content":"做到一半"}]}""")
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt, runMarker = InMemoryRunMarker(file.absolutePath))
        assertTrue(e.models() is Outcome.Ok)
        assertEquals(listOf("pi", "--mode", "rpc", "--session", file.absolutePath), rt.last.spec.argv)
        assertEquals("/root/proj", rt.last.spec.cwd)
        val items = e.chat.value.items
        assertEquals("做到一半", (items.first() as ChatItem.User).text)
        assertEquals(1, items.count { it.isNotice(NoticeKind.INTERRUPTED, "上一轮被系统中断") })
        e.close()
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun crashWithQueuedMessagesKeepsThemAsFailedWithoutResending() = runBlocking {
        // 审查 #2：崩溃时排队中的 followUp/steer 不能悄悄丢失，也不能自动重发。
        val rt = ScriptedRuntime { n ->
            { cmd ->
                when {
                    n == 1 && cmd.str("type") == "prompt" && cmd.str("streamingBehavior") == null -> {
                        ok(cmd, """{"disposition":"started"}""")
                        emit("""{"type":"agent_start"}""")
                        emit("""{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"${cmd.str("message")}"}]}}""")
                    }
                    n == 1 && cmd.str("type") == "prompt" -> {
                        ok(cmd, """{"disposition":"queued"}""")
                        val msg = cmd.str("message")
                        if (msg == "插话") {
                            emit("""{"type":"queue_update","steering":["插话"],"followUp":["排队"]}""")
                            crash(1, "Segmentation fault")
                        }
                    }
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        e.send("开始")
        waitFor { e.chat.value.running }
        assertEquals(Outcome.Ok(Disposition.QUEUED), e.send("排队", SendMode.FOLLOW_UP))
        e.send("插话", SendMode.STEER)
        waitFor { rt.processes.size == 2 && e.status.value is EngineStatus.Idle }
        val items = e.chat.value.items
        val users = items.filterIsInstance<ChatItem.User>()
        assertEquals(listOf("旧消息", "排队", "插话"), users.map { it.text })
        assertEquals(listOf(Delivery.SENT, Delivery.FAILED, Delivery.FAILED), users.map { it.delivery })
        assertTrue(items.any { it.isNotice(NoticeKind.INFO, "2 条消息未发出") })
        assertTrue(items.last().isNotice(NoticeKind.INTERRUPTED, "引擎意外退出"))
        assertEquals("不自动重发", 0, rt.last.received.count { it.str("type") == "prompt" })
        e.close()
    }

    @Test
    fun forceRestartWithQueuedMessagesKeepsThemAsFailed() = runBlocking {
        // 审查 #2：命令超时探活失败 → 强制重启，排队消息同样保留为「发送失败」。
        val hang = java.util.concurrent.atomic.AtomicBoolean(false)
        val rt = ScriptedRuntime { n ->
            { cmd ->
                when {
                    n == 1 && hang.get() -> Unit
                    n == 1 && cmd.str("type") == "prompt" && cmd.str("streamingBehavior") == null -> {
                        ok(cmd, """{"disposition":"started"}""")
                        emit("""{"type":"agent_start"}""")
                        emit("""{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"开始"}]}}""")
                    }
                    n == 1 && cmd.str("type") == "prompt" -> ok(cmd, """{"disposition":"queued"}""")
                    n == 1 && cmd.str("type") == "get_available_models" -> hang.set(true)
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        e.send("开始")
        waitFor { e.chat.value.running }
        e.send("排队", SendMode.FOLLOW_UP)
        assertEquals(ErrCode.RPC_TIMEOUT, (e.models() as Outcome.Err).code)
        waitFor(8_000) { rt.processes.size == 2 && e.status.value is EngineStatus.Idle }
        val failed = e.chat.value.items.filterIsInstance<ChatItem.User>().filter { it.delivery == Delivery.FAILED }
        assertEquals(listOf("排队"), failed.map { it.text })
        assertEquals(0, rt.last.received.count { it.str("type") == "prompt" })
        e.close()
    }

    @Test
    fun foregroundServiceIsNotToggledDuringCrashRecovery() = runBlocking {
        // 审查 #4：恢复期间多次 boot 不反复 startForegroundService/stopService。
        val fg = RecordingForeground()
        val rt = ScriptedRuntime { n ->
            { cmd ->
                when {
                    n == 1 -> {
                        basics(cmd)
                        if (cmd.str("type") == "get_state") crash(1, "boom")
                    }
                    n == 2 -> crash(1, "boom again") // 第一次恢复：握手前就退出
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt, foreground = fg)
        e.start()
        waitFor(5_000) { rt.processes.size == 3 && e.status.value is EngineStatus.Idle }
        assertEquals(listOf(true), fg.calls.toList())
        e.close()
        assertEquals(listOf(true, false), fg.calls.toList())
    }

    @Test
    fun foregroundServiceIsReleasedOnBootFailureAndGiveUp() = runBlocking {
        val fg = RecordingForeground()
        val rt = ScriptedRuntime { { _ -> crash(1, "proot error: ptrace(TRACEME): Operation not permitted") } }
        val e = engine(rt, foreground = fg)
        assertTrue(e.start() is Outcome.Err)
        assertEquals(listOf(true, false), fg.calls.toList())
        e.close()

        val fg2 = RecordingForeground()
        val rt2 = ScriptedRuntime { n ->
            { cmd ->
                if (n == 1) {
                    basics(cmd)
                    if (cmd.str("type") == "get_state") crash(1, "boom")
                } else {
                    crash(1, "boom")
                }
            }
        }
        val e2 = engine(rt2, foreground = fg2)
        e2.start()
        waitFor(10_000) { rt2.processes.size == 4 && (e2.status.value as? EngineStatus.Crashed)?.retryInMs == null }
        waitFor { fg2.calls.size == 2 }
        assertEquals("放弃恢复后才关闭前台服务", listOf(true, false), fg2.calls.toList())
        e2.close()
    }

    @Test
    fun foregroundGateNeverStopsServiceBeforeStartForeground() {
        val g = ForegroundGate()
        assertEquals(ForegroundGate.Action.START, g.request(true))
        assertEquals(ForegroundGate.Action.NONE, g.request(true))
        // 服务还没 startForeground 就要求关闭：不 stopService，由服务进入前台后 stopSelf
        assertEquals(ForegroundGate.Action.NONE, g.request(false))
        assertTrue(g.onForegroundStarted())
        g.onServiceDestroyed()
        // 正常路径：已进入前台 → 可以 stopService
        assertEquals(ForegroundGate.Action.START, g.request(true))
        assertFalse(g.onForegroundStarted())
        assertEquals(ForegroundGate.Action.STOP, g.request(false))
        // 启动失败后可以再试
        g.onServiceDestroyed()
        assertEquals(ForegroundGate.Action.START, g.request(true))
        g.onStartFailed()
        assertFalse(g.isDesired)
        assertEquals(ForegroundGate.Action.START, g.request(true))
    }

    @Test
    fun foregroundGateRapidRestartRaceReturnsNoneOnSecondStop() {
        // STOP → START → request(false)，中间不调 onServiceDestroyed
        val g = ForegroundGate()
        assertEquals(ForegroundGate.Action.START, g.request(true))
        assertFalse(g.onForegroundStarted()) // 服务进入前台
        assertEquals(ForegroundGate.Action.STOP, g.request(false)) // STOP：同时将 inForeground 置 false
        assertEquals(ForegroundGate.Action.START, g.request(true)) // START：拉起新实例
        // 中间不调 onServiceDestroyed，第三步返回 NONE（不是 STOP）
        assertEquals(ForegroundGate.Action.NONE, g.request(false))
        // 新实例随后 onForegroundStarted() 返回 true（自己 stopSelf）
        assertTrue(g.onForegroundStarted())
    }

    @Test
    fun foregroundGateAfterSelfStopCanStartAndHandleFalseBeforeStarted() {
        val g = ForegroundGate()
        assertEquals(ForegroundGate.Action.START, g.request(true))
        assertEquals(ForegroundGate.Action.NONE, g.request(false))
        assertTrue(g.onForegroundStarted()) // 返回 true（自己 stopSelf），inForeground 置 false
        assertEquals(ForegroundGate.Action.START, g.request(true))
        assertEquals(ForegroundGate.Action.NONE, g.request(false))
    }

    @Test
    fun handshakeRejectionStopsTheProcess() = runBlocking {
        // 审查 Nit #9：get_state 返回 success:false 时也要结束进程，避免之后出现两个 pi。
        val rt = ScriptedRuntime { n -> { cmd -> if (n == 1 && cmd.str("type") == "get_state") fail(cmd, "not ready") else basics(cmd) } }
        val e = engine(rt)
        assertTrue(e.start() is Outcome.Err)
        assertEquals(listOf(0L), rt.processes[0].stopCalls.toList())
        assertTrue(rt.processes[0].exited)
        assertTrue(e.start() is Outcome.Ok)
        assertEquals(2, rt.processes.size)
        e.close()
    }

    @Test
    fun historyLoadFailureIsShownInsteadOfSilentlyEmptyChat() = runBlocking {
        // 审查 Nit #10：重建时 get_messages 失败要告诉用户，并能重试。
        val failHistory = java.util.concurrent.atomic.AtomicBoolean(true)
        val rt = ScriptedRuntime {
            { cmd -> if (cmd.str("type") == "get_messages" && failHistory.get()) fail(cmd, "too large") else basics(cmd) }
        }
        val e = engine(rt)
        e.start()
        assertTrue(e.restart("测试") is Outcome.Ok) // 有会话文件 → 重建
        assertTrue(e.chat.value.items.last().isNotice(NoticeKind.ERROR, "历史记录加载失败"))
        System.err.println("DBG0 " + e.chat.value.items)
        failHistory.set(false)
        assertTrue(e.reloadHistory() is Outcome.Ok)
        System.err.println("DBG1 " + e.chat.value.items)
        assertEquals("旧消息", (e.chat.value.items.first() as ChatItem.User).text)
        assertTrue(e.chat.value.items.none { it.isNotice(NoticeKind.ERROR) })
        e.close()
    }

    @Test
    fun promptTimeoutIsConfirmedLaterInsteadOfDuplicated() = runBlocking {
        // 审查 Nit #6：prompt 应答超时 → 标红但仍等确认；pi 其实收下了 → 气泡变回「已发送」，只有一条。
        val rt = ScriptedRuntime {
            { cmd ->
                if (cmd.str("type") == "prompt") {
                    later(1_300) {
                        emit("""{"type":"agent_start"}""")
                        emit("""{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"${cmd.str("message")}"}]}}""")
                    }
                } else {
                    basics(cmd)
                }
            }
        }
        val e = engine(rt)
        e.start()
        assertEquals(ErrCode.RPC_TIMEOUT, (e.send("慢") as Outcome.Err).code)
        assertEquals(Delivery.FAILED, (e.chat.value.items.single() as ChatItem.User).delivery)
        waitFor { e.chat.value.running }
        // agent_start 与 user message_start 由同一个脚本任务先后发出：running 变真时 message_start 可能还没归约完
        // （CI 上偶发 expected SENT but was FAILED）。等确认到达再断言「只有一条且已发送」。
        waitFor { e.chat.value.items.filterIsInstance<ChatItem.User>().any { it.delivery == Delivery.SENT } }
        val users = e.chat.value.items.filterIsInstance<ChatItem.User>()
        assertEquals(1, users.size)
        assertEquals(Delivery.SENT, users.single().delivery)
        e.close()
    }

    @Test
    fun blockingUiRequestWaitsBrieflyForLateSubscriber() = runBlocking {
        // 审查 Nit #7：界面暂时没在听（切页、重建）时不立即取消，等一个短窗口。
        val rt = ScriptedRuntime { { cmd -> basics(cmd) } }
        val e = engine(rt, fast.copy(uiRequestGraceMs = 2_000))
        e.start()
        rt.last.emitLater("""{"type":"extension_ui_request","id":"late","method":"confirm","title":"确定？"}""")
        delay(300)
        val req = withTimeout(3_000) { e.uiRequests.first() }
        assertEquals("late", req.id)
        delay(200)
        assertFalse("有人处理就不自动取消", rt.last.received.any { it.str("type") == "extension_ui_response" })
        e.close()
    }

    @Test
    fun sameCwdSwitchResetsQueueAndContext() = runBlocking {
        // 审查 Nit #8：同 cwd 切换会话不沿用旧会话的队列与上下文占用。
        val rt = ScriptedRuntime {
            { cmd ->
                when (cmd.str("type")) {
                    "switch_session" -> ok(cmd, """{"cancelled":false}""")
                    "get_session_stats" -> ok(cmd, """{"tokens":{"total":1}}""")
                    "prompt" -> {
                        ok(cmd, """{"disposition":"queued"}""")
                        emit("""{"type":"queue_update","steering":[],"followUp":["旧队列"]}""")
                    }
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        e.start()
        e.send("x")
        waitFor { e.chat.value.queue.followUp.isNotEmpty() }
        assertTrue(e.openSession("/root/.pi/agent/sessions/--root-pi-cwd--/s2.jsonl", "/root/pi-cwd") is Outcome.Ok)
        assertTrue(e.chat.value.queue.isEmpty)
        assertNull(e.chat.value.contextPercent)
        e.close()
    }

    @Test
    fun crashBackoffUserSendPreservesFailedCarryAndNotice() = runBlocking {
        // 崩溃后在退避期间调用 send（触发 ensureStarted 启动），重建完成后时间线上仍有那条 Delivery.FAILED 的用户气泡和「未发出」提示
        val rt = ScriptedRuntime { n ->
            { cmd ->
                when {
                    n == 1 && cmd.str("type") == "prompt" -> {
                        ok(cmd, """{"disposition":"started"}""")
                        emit("""{"type":"agent_start"}""")
                        crash(1, "Segmentation fault")
                    }
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt, fast.copy(restartBackoffMs = listOf(1_500, 3_000)))
        e.send("老消息")
        // 等待进入退避等待状态
        waitFor {
            val s = e.status.value
            s is EngineStatus.Crashed && s.retryInMs != null
        }
        // 退避期间用户发送新消息，触发 ensureStarted 启动新连接并重建时间线
        val sendRes = e.send("新消息")
        assertTrue(sendRes is Outcome.Ok)
        waitFor { e.status.value is EngineStatus.Idle }
        val items = e.chat.value.items
        val users = items.filterIsInstance<ChatItem.User>()
        val old = users.firstOrNull { it.text == "老消息" }
        assertNotNull(old)
        assertEquals(Delivery.FAILED, old?.delivery)
        assertTrue(items.any { it.isNotice(NoticeKind.INFO, "1 条消息未发出") })
        e.close()
    }

    @Test
    fun crashRecoveryExhaustedManualRestartPreservesFailedCarry() = runBlocking {
        // 自动恢复全部失败（放弃恢复）后调用 restart()，失败气泡仍在
        val rt = ScriptedRuntime { n ->
            { cmd ->
                when {
                    n == 1 && cmd.str("type") == "prompt" -> {
                        ok(cmd, """{"disposition":"started"}""")
                        emit("""{"type":"agent_start"}""")
                        crash(1, "boom")
                    }
                    n in 2..4 -> {
                        crash(1, "Cannot start")
                    }
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        e.send("未送达消息")
        // 等待 3 次自动恢复全部尝试并放弃，停在诊断页（retryInMs == null）
        waitFor(10_000) { rt.processes.size == 4 && (e.status.value as? EngineStatus.Crashed)?.retryInMs == null }
        // 诊断页手动调用 restart()
        assertTrue(e.restart("用户诊断页重启") is Outcome.Ok)
        waitFor { e.status.value is EngineStatus.Idle }
        val items = e.chat.value.items
        val users = items.filterIsInstance<ChatItem.User>()
        val failed = users.firstOrNull { it.text == "未送达消息" }
        assertNotNull(failed)
        assertEquals(Delivery.FAILED, failed?.delivery)
        assertTrue(items.any { it.isNotice(NoticeKind.INFO, "1 条消息未发出") })
        e.close()
    }

    @Test
    fun getMessagesFailurePreservesPendingCarryAndDoesNotDuplicateOnReload() = runBlocking {
        // get_messages 失败后再次重建，失败气泡仍只出现一次
        val failHistory = java.util.concurrent.atomic.AtomicBoolean(false)
        val rt = ScriptedRuntime { n ->
            { cmd ->
                when {
                    n == 1 && cmd.str("type") == "prompt" -> {
                        ok(cmd, """{"disposition":"started"}""")
                        emit("""{"type":"agent_start"}""")
                        failHistory.set(true)
                        crash(1, "boom")
                    }
                    cmd.str("type") == "get_messages" && failHistory.get() -> {
                        fail(cmd, "history error")
                    }
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        e.send("待保留消息")
        // 等待第 2 个进程尝试恢复，get_messages 失败
        waitFor { e.chat.value.items.any { it.isNotice(NoticeKind.ERROR, "历史记录加载失败") } }
        failHistory.set(false)
        assertTrue(e.reloadHistory() is Outcome.Ok)
        val items1 = e.chat.value.items
        assertEquals(1, items1.filterIsInstance<ChatItem.User>().count { it.text == "待保留消息" })
        assertEquals(Delivery.FAILED, items1.filterIsInstance<ChatItem.User>().first { it.text == "待保留消息" }.delivery)

        // 再次 reloadHistory，消费过的 carry 不会重复出现
        assertTrue(e.reloadHistory() is Outcome.Ok)
        val items2 = e.chat.value.items
        assertEquals(1, items2.filterIsInstance<ChatItem.User>().count { it.text == "待保留消息" })
        e.close()
    }

    @Test
    fun resendingCarriedTextDropsItFromLaterRebuilds() = runBlocking {
        // 用户点「重试」重新发送了接回的文字后，之后再重建不应再以「发送失败」接回它（不重复）。
        val rt = ScriptedRuntime { n ->
            { cmd ->
                when {
                    n == 1 && cmd.str("type") == "prompt" -> {
                        ok(cmd, """{"disposition":"started"}""")
                        emit("""{"type":"agent_start"}""")
                        crash(1, "boom")
                    }
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        e.send("要重试的")
        waitFor { e.status.value is EngineStatus.Idle && e.chat.value.items.any { it is ChatItem.User && it.text == "要重试的" && it.delivery == Delivery.FAILED } }
        assertTrue(e.send("要重试的") is Outcome.Ok)
        assertTrue(e.reloadHistory() is Outcome.Ok)
        val failed = e.chat.value.items.filterIsInstance<ChatItem.User>().filter { it.text == "要重试的" && it.delivery == Delivery.FAILED }
        assertTrue(failed.isEmpty())
        e.close()
    }

    @Test
    fun newSessionClearsPendingCarry() = runBlocking {
        val rt = ScriptedRuntime { n ->
            { cmd ->
                when {
                    n == 1 && cmd.str("type") == "prompt" -> {
                        ok(cmd, """{"disposition":"started"}""")
                        emit("""{"type":"agent_start"}""")
                        crash(1, "boom")
                    }
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        e.send("旧会话消息")
        waitFor {
            val s = e.status.value
            s is EngineStatus.Crashed && s.retryInMs != null
        }
        // 在新目录新建会话
        assertTrue(e.newSession("/other/dir") is Outcome.Ok)
        waitFor { e.status.value is EngineStatus.Idle }
        // 新会话时间线不应包含旧会话的 pendingCarry
        assertTrue(e.chat.value.items.filterIsInstance<ChatItem.User>().none { it.text == "旧会话消息" })
        e.close()
    }

    private val s1 = "/root/.pi/agent/sessions/--root-pi-cwd--/s1.jsonl"

    /** 第一个进程收到 prompt 后崩溃；之后的 get_messages 在 [failHistory] 为真时失败。 */
    private fun crashThenHistoryFails(failHistory: java.util.concurrent.atomic.AtomicBoolean) = ScriptedRuntime { n ->
        { cmd ->
            when {
                n == 1 && cmd.str("type") == "prompt" -> {
                    ok(cmd, """{"disposition":"started"}""")
                    emit("""{"type":"agent_start"}""")
                    failHistory.set(true)
                    crash(1, "boom")
                }
                cmd.str("type") == "get_messages" && failHistory.get() -> fail(cmd, "history error")
                else -> basics(cmd)
            }
        }
    }

    @Test
    fun reopeningSameSessionAfterHistoryFailureKeepsPendingCarry() = runBlocking {
        // 审查 R3：崩溃 → get_messages 失败 → 按提示 openSession(同一文件) 重试，FAILED 气泡与「n 条未发出」提示仍在。
        val failHistory = java.util.concurrent.atomic.AtomicBoolean(false)
        val rt = crashThenHistoryFails(failHistory)
        val e = engine(rt)
        e.send("待保留消息")
        waitFor { e.status.value is EngineStatus.Idle && e.chat.value.items.any { it.isNotice(NoticeKind.ERROR, "历史记录加载失败") } }
        failHistory.set(false)
        assertTrue(e.openSession(s1, "/root/pi-cwd") is Outcome.Ok)
        val items = e.chat.value.items
        val kept = items.filterIsInstance<ChatItem.User>().filter { it.text == "待保留消息" }
        assertEquals(1, kept.size)
        assertEquals(Delivery.FAILED, kept.single().delivery)
        assertTrue(items.any { it.isNotice(NoticeKind.INFO, "1 条消息未发出") })
        assertTrue("历史已重新加载", items.any { it is ChatItem.User && it.text == "旧消息" })
        assertEquals("同一会话不发 switch_session", 0, rt.last.received.count { it.str("type") == "switch_session" })
        assertEquals("不自动重发", 0, rt.last.received.count { it.str("type") == "prompt" })
        e.close()
    }

    @Test
    fun reopeningSameSessionWhileEngineDownKeepsPendingCarry() = runBlocking {
        // 慢路径（引擎未就绪，重启进程打开）：同一会话同样接回 carry，不先清空时间线。
        val failHistory = java.util.concurrent.atomic.AtomicBoolean(false)
        val rt = ScriptedRuntime { n ->
            { cmd ->
                when {
                    n == 1 && cmd.str("type") == "prompt" -> {
                        ok(cmd, """{"disposition":"started"}""")
                        emit("""{"type":"agent_start"}""")
                        crash(1, "boom")
                    }
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        e.send("引擎没起来时的消息")
        waitFor {
            val s = e.status.value
            s is EngineStatus.Crashed && s.retryInMs != null
        }
        assertTrue(e.openSession(s1, "/root/pi-cwd") is Outcome.Ok)
        waitFor { e.status.value is EngineStatus.Idle }
        val items = e.chat.value.items
        val kept = items.filterIsInstance<ChatItem.User>().filter { it.text == "引擎没起来时的消息" }
        assertEquals(1, kept.size)
        assertEquals(Delivery.FAILED, kept.single().delivery)
        assertTrue(items.any { it.isNotice(NoticeKind.INFO, "1 条消息未发出") })
        e.close()
    }

    @Test
    fun openingAnotherSessionStillDropsPendingCarry() = runBlocking {
        val failHistory = java.util.concurrent.atomic.AtomicBoolean(false)
        val rt = crashThenHistoryFails(failHistory)
        val e = engine(rt)
        e.send("旧会话的消息")
        waitFor { e.status.value is EngineStatus.Idle && e.chat.value.items.any { it.isNotice(NoticeKind.ERROR, "历史记录加载失败") } }
        failHistory.set(false)
        assertTrue(e.openSession("/root/.pi/agent/sessions/--root-pi-cwd--/s2.jsonl", "/root/pi-cwd") is Outcome.Ok)
        assertTrue(e.chat.value.items.filterIsInstance<ChatItem.User>().none { it.text == "旧会话的消息" })
        e.close()
    }

    @Test
    fun reopeningSameSessionWhileGeneratingDoesNotEndTheTurn() = runBlocking {
        // 定点核实：生成中在抽屉里点当前会话，不能把这一轮当成结束（running、唤醒锁、队列、流式中的消息都保留）。
        val rt = ScriptedRuntime { _ ->
            { cmd ->
                when (cmd.str("type")) {
                    "prompt" -> if (cmd.str("streamingBehavior") == null) {
                        ok(cmd, """{"disposition":"started"}""")
                        emit("""{"type":"agent_start"}""")
                        emit("""{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"${cmd.str("message")}"}]}}""")
                        emit("""{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""")
                        emit("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"输出到一半"}}""")
                    } else {
                        ok(cmd, """{"disposition":"queued"}""")
                        emit("""{"type":"queue_update","steering":[],"followUp":["${cmd.str("message")}"]}""")
                    }
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        e.send("开始")
        waitFor { e.chat.value.items.any { it is ChatItem.Assistant } && wake.isHeld }
        e.send("排队的", SendMode.FOLLOW_UP)
        waitFor { e.chat.value.queue.followUp == listOf("排队的") }
        assertTrue(e.openSession(s1, "/root/pi-cwd") is Outcome.Ok)
        assertTrue(e.reloadHistory() is Outcome.Ok)
        delay(100)
        val chat = e.chat.value
        assertTrue(chat.running)
        assertTrue(wake.isHeld)
        assertTrue(e.status.value is EngineStatus.Running)
        assertEquals(listOf("排队的"), chat.queue.followUp)
        assertTrue("流式中的消息没被历史快照替换", chat.items.any { it is ChatItem.Assistant })
        assertEquals("生成中不重建", 0, rt.last.received.count { it.str("type") == "get_messages" })
        e.close()
    }

    @Test
    fun repeatedHistoryFailuresShowOneNotice() = runBlocking {
        val failHistory = java.util.concurrent.atomic.AtomicBoolean(false)
        val rt = crashThenHistoryFails(failHistory)
        val e = engine(rt)
        e.send("待保留消息")
        waitFor { e.status.value is EngineStatus.Idle && e.chat.value.items.any { it.isNotice(NoticeKind.ERROR, "历史记录加载失败") } }
        assertTrue(e.openSession(s1, "/root/pi-cwd") is Outcome.Err)
        assertTrue(e.reloadHistory() is Outcome.Err)
        assertEquals(1, e.chat.value.items.count { it.isNotice(NoticeKind.ERROR, "历史记录加载失败") })
        assertTrue(e.chat.value.items.any { it is ChatItem.User && it.text == "待保留消息" && it.delivery == Delivery.FAILED })
        e.close()
    }
}
