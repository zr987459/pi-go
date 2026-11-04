package io.github.zr987459.pigo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.fake.FakeEngineClient
import io.github.zr987459.pigo.contract.fake.FakeSecretStore
import io.github.zr987459.pigo.core.store.GuestPaths
import io.github.zr987459.pigo.core.store.PiProviderConfigStore
import io.github.zr987459.pigo.core.store.QuickModelSetup
import io.github.zr987459.pigo.di.CredentialReloader
import io.github.zr987459.pigo.di.QuickSetupWiring
import io.github.zr987459.pigo.feature.settings.QuickModelSetupPage
import io.github.zr987459.pigo.feature.settings.QuickTags
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * r4/model-config 主流程（PR #29 审查 N6：走真实接线）：页面 ← [QuickSetupWiring] ← 真实 [QuickModelSetup] + [PiProviderConfigStore]
 * （临时 rootfs）+ [CredentialReloader]（热加载回调记录调用）+ 假引擎。选服务商 → 填 Key → 选模型 → 保存 →
 * auth.json 里有 Key、热加载一次、没有重启、当前会话用上这个模型并能发消息。另测「保存期间页面离开再回来」不中断（I2）
 * 与转屏不清空已选模型（N5，用重建组合模拟）。
 */
@RunWith(AndroidJUnit4::class)
class QuickModelSetupUiTest {
    @get:Rule val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fake = FakeEngineClient(scope, stepDelayMs = 0)
    private val restarts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val engine = object : EngineClient by fake {
        override suspend fun restart(reason: String): Outcome<Unit> { restarts += reason; return Outcome.Ok(Unit) }
    }
    private val hot: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val rootfs = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "quick-${System.nanoTime()}")
    private val config = PiProviderConfigStore(FakeSecretStore(), GuestPaths(rootfs))
    private val reloader = CredentialReloader(engine, scope, hotReload = { hot += "refresh"; Outcome.Ok(Unit) })
    private val setup = QuickModelSetup(
        config,
        catalogModels = { p -> if (p == "deepseek") listOf("deepseek-chat", "deepseek-reasoner") else emptyList() },
        applyConfig = { p, reason, onWaiting -> reloader.apply(p, reason, onWaiting) },
        setModel = { p, m -> engine.setModel(p, m) },
    )
    private val saver = QuickSetupWiring.runner(setup, scope)
    private val current = fake.chat.map { c -> c.model?.let { "${it.provider}/${it.id}" } }.stateIn(scope, SharingStarted.Eagerly, null)
    private val actions = QuickSetupWiring.actions(setup, saver, current)

    @After fun tearDown() {
        scope.cancel()
        rootfs.deleteRecursively()
    }

    private fun list() = rule.onNodeWithTag(QuickTags.LIST)
    private fun waitTag(tag: String) = rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun pickProviderTypeKeyPickModelSaveHotReloadsAndChatUsesIt() {
        var shown by mutableStateOf(true)
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(rule)
        restoration.setContent { if (shown) QuickModelSetupPage(actions) }
        rule.onNodeWithTag(QuickTags.preset("deepseek")).performClick()
        waitTag(QuickTags.model("deepseek-reasoner"))
        rule.onNodeWithTag(QuickTags.KEY).performTextInput("sk-test-0123456789abcdef")
        list().performScrollToNode(hasTestTag(QuickTags.model("deepseek-reasoner")))
        rule.onNodeWithTag(QuickTags.model("deepseek-reasoner")).performClick()

        // N5：模拟转屏（保存并恢复实例状态、重建组合），已选模型不被清空
        restoration.emulateSavedInstanceStateRestore()
        waitTag(QuickTags.model("deepseek-reasoner"))
        rule.onNodeWithTag(QuickTags.MODEL_INPUT).assertTextContains("deepseek-reasoner")
        rule.onNodeWithTag(QuickTags.KEY).performTextInput("sk-test-0123456789abcdef") // Key 只在内存里，重建后要重填

        list().performScrollToNode(hasTestTag(QuickTags.SAVE))
        rule.onNodeWithTag(QuickTags.SAVE).performClick()
        // I2：保存在 App 作用域里跑，页面立刻离开也照样做完
        shown = false
        rule.waitUntil(10_000) { fake.chat.value.model?.id == "deepseek-reasoner" }
        shown = true
        rule.waitForIdle()
        list().performScrollToNode(hasTestTag(QuickTags.RESULT))
        rule.onNodeWithTag(QuickTags.RESULT).assertTextContains("已保存", substring = true)

        assertEquals("热加载一次", listOf("refresh"), hot.toList())
        assertTrue("没有重启：$restarts", restarts.isEmpty())
        assertTrue(File(rootfs, "root/.pi/agent/auth.json").readText().contains("sk-test-0123456789abcdef"))
        assertEquals("deepseek", fake.chat.value.model?.provider)
        assertTrue(runBlocking { engine.send("你好", SendMode.STEER) } is Outcome.Ok)
        list().performScrollToNode(hasTestTag(QuickTags.CURRENT))
        rule.onNodeWithTag(QuickTags.CURRENT).assertTextContains("deepseek/deepseek-reasoner", substring = true)
    }

    @Test fun customEndpointNeedsOnlyBaseUrlAndKeyAndBadUrlIsExplained() {
        rule.setContent { QuickModelSetupPage(actions, onAdvanced = {}) }
        rule.onNodeWithTag(QuickTags.preset(QuickModelSetup.CUSTOM)).performClick()
        rule.onNodeWithTag(QuickTags.BASE_URL).assertIsDisplayed().performTextInput("example.com")
        rule.onNodeWithTag(QuickTags.KEY).performTextInput("sk-test-0123456789abcdef")
        rule.onNodeWithTag(QuickTags.MODEL_INPUT).performTextInput("m1")
        list().performScrollToNode(hasTestTag(QuickTags.TEST))
        rule.onNodeWithTag(QuickTags.TEST).performClick()
        waitTag(QuickTags.RESULT)
        list().performScrollToNode(hasTestTag(QuickTags.RESULT))
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag(QuickTags.RESULT).assertTextContains("接口地址不对", substring = true) }.isSuccess
        }
        list().performScrollToNode(hasTestTag(QuickTags.ADVANCED))
        rule.onNodeWithTag(QuickTags.ADVANCED).assertIsDisplayed()
    }
}
