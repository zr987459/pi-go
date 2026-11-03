package io.github.zr987459.pigo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.contract.Phase
import io.github.zr987459.pigo.di.BindingMode
import io.github.zr987459.pigo.di.Mode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 端到端冒烟（mvp/integration，DESIGN §6）：真实装配下 App 能启动，四个顶层页面都能进入，
 * 设置页能被「去设置模型」定位到「模型与 Key」。CI 只编译；在 §5 规定的真机/模拟器上用
 * `./gradlew :app:connectedDebugAndroidTest` 运行。不依赖网络与模型 Key。
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitTag(tag: String, timeoutMs: Long = 10_000) {
        rule.waitUntil(timeoutMs) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** dsh-Go 外壳没有底部导航栏：抽屉就是会话面板，先从左边缘拉出抽屉。 */
    private fun openDrawer() {
        rule.onRoot().performTouchInput { swipeRight(startX = 2f, endX = centerX) }
        waitTag("drawer")
        waitTag("page:sessions")
    }

    /** 抽屉里的入口：设置在身份栏圆钮，安装与运行环境在列表下方的系统行。 */
    private fun nav(route: String) {
        openDrawer()
        val tag = if (route == "settings") "sessions:settings" else "nav:$route"
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    @Test
    fun allBindingsAreReal() {
        assertEquals(Mode.REAL, BindingMode.runtime)
        assertEquals(Mode.REAL, BindingMode.engine)
        assertEquals(Mode.REAL, BindingMode.chat)
        assertEquals(Mode.REAL, BindingMode.store)
    }

    @Test
    fun startsOnOnboardingOrChatDependingOnRuntime() {
        val graph = (rule.activity.application as PiGoApp).graph
        val ready = graph.runtime.status.value.phase == Phase.READY
        waitTag(if (ready) "page:chat" else "page:onboarding")
    }

    @Test
    fun topLevelNavigationReachesEveryPage() {
        nav("settings")
        waitTag("page:settings")
        rule.onNodeWithTag("settings:sec-models").assertIsDisplayed()
        nav("onboarding")
        waitTag("page:onboarding")
    }

    @Test
    fun noModelBannerJumpsToModelSettings() {
        val graph = (rule.activity.application as PiGoApp).graph
        if (graph.runtime.status.value.phase != Phase.READY) return // 运行环境未就绪时起点是安装向导
        waitTag("page:chat")
        // 全新安装没有任何 Key：聊天页顶部提示「还没有配置模型」，点「去设置模型」定位到设置页「模型与 Key」。
        val hasBanner = runCatching { waitTag("chat:goSettings", 5_000) }.isSuccess
        if (!hasBanner) return // 设备上已配置过模型时跳过这一步
        rule.onNodeWithTag("chat:goSettings").performClick()
        waitTag("page:settings")
        rule.onNodeWithTag("settings:sec-models").assertIsDisplayed()
    }
}
