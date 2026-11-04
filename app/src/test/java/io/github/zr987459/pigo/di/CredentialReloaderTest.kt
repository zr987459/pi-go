package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.fake.FakeEngineClient
import io.github.zr987459.pigo.contract.fake.FakeSecretStore
import io.github.zr987459.pigo.core.store.GuestPaths
import io.github.zr987459.pigo.core.store.PiProviderConfigStore
import io.github.zr987459.pigo.core.store.QuickModelSetup
import io.github.zr987459.pigo.feature.settings.QuickSaveRequest
import io.github.zr987459.pigo.feature.settings.QuickSaveState
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r4/model-config：凭据变化后首选热加载（不重启、不打断生成）；热加载做不到时退回重启，且重启不能腰斩正在生成的回合、
 * 不能丢排队消息（PR #29 审查 I1）；失败要提示（N1）；「保存并使用」在 App 作用域里跑，页面不在了也会做完（I2）。
 */
class CredentialReloaderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fake = FakeEngineClient(scope, stepDelayMs = 40)

    /** 记录每次重启发生时引擎是否还在生成；重启本身不动假引擎（真实 PiEngine 会结束进程，这里只看时机）。 */
    private inner class Recording(private val inner: EngineClient) : EngineClient by inner {
        val restarts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var result: Outcome<Unit> = Outcome.Ok(Unit)
        override suspend fun restart(reason: String): Outcome<Unit> {
            val c = inner.chat.value
            restarts += "$reason|running=${c.running}|queued=${!c.queue.isEmpty}"
            return result
        }
    }

    private val engine = Recording(fake)
    private val notices: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val hotCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var hotResult: Outcome<Unit> = Outcome.Err(ErrCode.UNKNOWN, "桥接不可用")
    private var hotCapable = true
    private val restarter = CredentialReloader(
        engine, scope,
        hotReload = { hotCalls += "running=${fake.chat.value.running}"; hotResult },
        canHotReload = { hotCapable },
        notify = { notices += it },
    )
    private val rootfs: File = Files.createTempDirectory("pigo-restart").toFile()

    @After fun tearDown() {
        scope.cancel()
        rootfs.deleteRecursively()
    }

    private suspend fun awaitRunning() = withTimeout(5_000) { fake.chat.first { it.running } }
    private suspend fun awaitIdle() = withTimeout(10_000) { fake.chat.first { !it.running && it.queue.isEmpty } }

    @Test fun hotReloadWhileGeneratingNeitherRestartsNorInterrupts() = runBlocking {
        hotResult = Outcome.Ok(Unit)
        assertTrue(fake.send("第一条", SendMode.STEER) is Outcome.Ok)
        awaitRunning()
        assertTrue(fake.send("排队的第二条", SendMode.FOLLOW_UP) is Outcome.Ok)
        val r = restarter.request("deepseek")
        assertEquals(CredentialReloader.Result.Hot, r)
        assertEquals(null, CredentialReloader.toToast(r))
        assertEquals("生成中立即热加载", listOf("running=true"), hotCalls)
        assertTrue("没有重启", engine.restarts.isEmpty())
        awaitIdle()
        delay(100)
        assertTrue(engine.restarts.isEmpty())
        val items = fake.chat.value.items
        assertTrue(items.any { it is ChatItem.User && it.text == "排队的第二条" })
        assertFalse(items.any { it is ChatItem.Notice && it.kind == NoticeKind.STOPPED })
    }

    @Test fun envOnlyCredentialsSkipHotReload() = runBlocking {
        hotResult = Outcome.Ok(Unit); hotCapable = false
        assertEquals(CredentialReloader.Result.Restarted, restarter.request("amazon-bedrock"))
        assertTrue("凭据只能进进程环境：不试热加载", hotCalls.isEmpty())
        assertEquals(1, engine.restarts.size)
    }

    @Test fun idleFallbackRestartsImmediately() = runBlocking {
        assertEquals(CredentialReloader.Result.Restarted, restarter.request("deepseek"))
        assertEquals(listOf("deepseek 的凭据已更新|running=false|queued=false"), engine.restarts)
        assertEquals(null, CredentialReloader.toToast(CredentialReloader.Result.Restarted))
    }

    /** 退路（桥接不可用）：生成中不立刻重启，等本轮与排队消息结束再重启一次。 */
    @Test fun fallbackWhileGeneratingWaitsForTurnEndAndKeepsQueuedMessage() = runBlocking {
        assertTrue(fake.send("第一条", SendMode.STEER) is Outcome.Ok)
        awaitRunning()
        assertTrue(fake.send("排队的第二条", SendMode.FOLLOW_UP) is Outcome.Ok)

        val r = restarter.request("deepseek")
        assertEquals(CredentialReloader.Result.AfterTurn, r)
        val toast = CredentialReloader.toToast(r)!!
        assertFalse(toast.failed)
        assertTrue(toast.text, toast.text.contains("不会打断当前回复"))
        assertTrue("生成中不立刻重启：${engine.restarts}", engine.restarts.isEmpty())
        // 再报一次（例如 AuthService 与面板各报一次）：只排一个
        assertEquals(CredentialReloader.Result.AfterTurn, restarter.request("deepseek"))

        awaitIdle()
        withTimeout(5_000) { while (engine.restarts.isEmpty()) delay(10) }
        delay(100)
        assertEquals("本轮与排队的消息都结束后才重启一次", listOf("deepseek 的凭据已更新|running=false|queued=false"), engine.restarts)
        val items = fake.chat.value.items
        assertTrue("排队的消息没丢", items.any { it is ChatItem.User && it.text == "排队的第二条" })
        assertFalse("没有被中途停止", items.any { it is ChatItem.Notice && it.kind == NoticeKind.STOPPED })
        assertFalse("回复都写完了", items.any { it is ChatItem.Assistant && it.streaming })
        withTimeout(2_000) { while (notices.isEmpty()) delay(10) }
        assertEquals(listOf(CredentialReloader.DONE_AFTER_TURN), notices)
    }

    @Test fun restartFailureIsReported() = runBlocking {
        engine.result = Outcome.Err(ErrCode.ENGINE_START, "运行环境未就绪")
        val r = restarter.request("deepseek")
        assertTrue(r is CredentialReloader.Result.Failed)
        val toast = CredentialReloader.toToast(r)!!
        assertTrue(toast.failed)
        assertTrue(toast.text, toast.text.contains("运行环境未就绪"))
    }

    @Test fun sameProviderReportedTwiceRestartsOnce() = runBlocking {
        restarter.request("github-copilot")
        restarter.request("github-copilot")
        restarter.request("deepseek")
        assertEquals(2, engine.restarts.size)
    }

    private fun setup(config: PiProviderConfigStore) = QuickModelSetup(
        config,
        catalogModels = { listOf("deepseek-chat") },
        applyConfig = { p, reason, onWaiting -> restarter.apply(p, reason, onWaiting) },
        setModel = { p, m -> engine.setModel(p, m) },
    )

    /** 「生成中保存」（热加载）：真实 QuickModelSetup + App 作用域的 QuickSaveRunner；不等本轮结束、不重启，当前回复不受影响。 */
    @Test fun quickSaveWhileGeneratingHotReloadsAndSwitchesModelImmediately() = runBlocking {
        hotResult = Outcome.Ok(Unit)
        val config = PiProviderConfigStore(FakeSecretStore(), GuestPaths(rootfs))
        val saver = QuickSetupWiring.runner(setup(config), scope)
        assertTrue(fake.send("写一首长诗", SendMode.STEER) is Outcome.Ok)
        awaitRunning()
        assertTrue(saver.start(QuickSaveRequest("deepseek", "sk-test-0123456789abcdef", "deepseek-chat", emptyList(), "")))
        val done = withTimeout(5_000) { saver.state.first { it is QuickSaveState.Done || it is QuickSaveState.Failed } }
        assertTrue("$done", done is QuickSaveState.Done)
        assertEquals(listOf("running=true"), hotCalls)
        assertTrue(engine.restarts.isEmpty())
        assertEquals("deepseek-chat", fake.chat.value.model?.id)
        awaitIdle()
        assertFalse(fake.chat.value.items.any { it is ChatItem.Notice && it.kind == NoticeKind.STOPPED })
        // Key 进了 pi 自己读的 auth.json（600），不进进程环境
        val auth = File(rootfs, "root/.pi/agent/auth.json")
        assertTrue(auth.readText().contains("sk-test-0123456789abcdef"))
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(auth.toPath())))
        assertFalse(config.env().values.any { it.contains("sk-test") })
    }

    /** 「生成中保存」（退路：桥接不可用）：等本轮结束再重启，再切模型。 */
    @Test fun quickSaveWhileGeneratingFallbackFinishesAfterTurnThenSwitchesModel() = runBlocking {
        val config = PiProviderConfigStore(FakeSecretStore(), GuestPaths(rootfs))
        val setup = setup(config)
        val finished = mutableListOf<String>()
        val saver = QuickSetupWiring.runner(setup, scope) { finished += it.message }

        assertTrue(fake.send("写一首长诗", SendMode.STEER) is Outcome.Ok)
        awaitRunning()
        assertTrue(saver.start(QuickSaveRequest("deepseek", "sk-test-0123456789abcdef", "deepseek-chat", emptyList(), "")))
        assertFalse("保存中不重复开始", saver.start(QuickSaveRequest("deepseek", "k", "deepseek-chat", emptyList(), "")))
        withTimeout(5_000) { saver.state.first { it == QuickSaveState.Running(QuickModelSetup.PROGRESS_WAITING) } }
        assertTrue(engine.restarts.isEmpty())

        val done = withTimeout(10_000) { saver.state.first { it is QuickSaveState.Done || it is QuickSaveState.Failed } }
        assertTrue("$done", done is QuickSaveState.Done)
        assertEquals(listOf("模型配置已更新|running=false|queued=false"), engine.restarts)
        assertEquals("deepseek-chat", fake.chat.value.model?.id)
        assertEquals("deepseek", fake.chat.value.model?.provider)
        assertFalse(fake.chat.value.items.any { it is ChatItem.Notice && it.kind == NoticeKind.STOPPED })
        assertEquals(1, finished.size)
    }
}
