package io.github.zr987459.pigo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.contract.Phase
import io.github.zr987459.pigo.di.BindingMode
import io.github.zr987459.pigo.di.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 端到端冒烟（mvp/integration，DESIGN §6）：真实装配下 App 能启动，四个顶层页面都能进入（经抽屉），
 * 抽屉打开时返回键只关抽屉，子页面左上角返回圆钮可回到聊天，
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

    /** 抽屉已关上：面板移出屏幕（ModalNavigationDrawer 关着时内容仍在组合里，只是不显示）。 */
    private fun waitDrawerClosed(timeoutMs: Long = 5_000) {
        rule.waitUntil(timeoutMs) {
            runCatching { rule.onNodeWithTag("drawer").assertIsNotDisplayed() }.isSuccess
        }
    }

    @Test
    fun topLevelNavigationReachesEveryPage() {
        // 设置：抽屉身份栏圆钮进入，左上角返回圆钮回到聊天
        nav("settings")
        waitTag("page:settings")
        rule.onNodeWithTag("settings:sec-models").assertIsDisplayed()
        rule.onNodeWithTag("settings:back").performClick()
        waitTag("page:chat")
        // 会话：抽屉本身就是会话面板
        openDrawer()
        rule.onNodeWithTag("page:sessions").assertIsDisplayed()
        // 抽屉打开时按返回键只关抽屉，不退出 App
        Espresso.pressBackUnconditionally()
        waitDrawerClosed()
        assertFalse(rule.activity.isFinishing)
        rule.onNodeWithTag("page:chat").assertIsDisplayed()
        // 抽屉里的「聊天」入口
        nav("chat")
        waitTag("page:chat")
        waitDrawerClosed()
        // 安装与运行环境，及其左上角圆钮（已就绪时返回聊天，未就绪时「≡」打开抽屉）
        nav("onboarding")
        waitTag("page:onboarding")
        val graph = (rule.activity.application as PiGoApp).graph
        if (graph.runtime.status.value.phase == Phase.READY) {
            rule.onNodeWithTag("onboarding:back").performClick()
            waitTag("page:chat")
        } else {
            rule.onNodeWithTag("onboarding:menu").performClick()
            waitTag("drawer")
            rule.onNodeWithTag("drawer").assertIsDisplayed()
        }
    }

    @Test
    fun noModelBannerJumpsToModelSettings() {
        // 不论运行环境是否就绪，都从抽屉的「聊天」入口进入聊天页（不再提前 return）。
        nav("chat")
        waitTag("page:chat")
        // 全新安装没有任何 Key：聊天页提示「还没有配置模型」，点「去设置模型」定位到设置页「模型与 Key」。
        val hasBanner = runCatching { waitTag("chat:goSettings", 5_000) }.isSuccess
        Assume.assumeTrue("设备上已配置过模型，跳过横幅这一步", hasBanner)
        rule.onNodeWithTag("chat:goSettings").performClick()
        waitTag("page:settings")
        rule.onNodeWithTag("settings:sec-models").assertIsDisplayed()
    }
}
