package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.fake.FakeAuthManager
import io.github.zr987459.pigo.contract.fake.FakeCustomProviderStore
import io.github.zr987459.pigo.contract.fake.FakeEngineClient
import io.github.zr987459.pigo.contract.fake.FakeModelCatalog
import io.github.zr987459.pigo.contract.fake.FakePiSettings
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.session.OverlayStack
import io.github.zr987459.pigo.feature.models.RealModelPanels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR #26 第 2 轮 Nit 2：OAuth 登录在后台完成（RealModelPanels.oauthFlow 里 `OAuthStep.Done → credentialsReady`，不经面板事件）
 * 后自动选回模型时，/model 选择面板与登录面板也要关掉。真实 RealModelPanels + SetModelSignal（与 AppGraph 同样接线）。
 * github-copilot 在 FakeAuthManager 里走设备码流程，无需用户操作即到 Done。
 */
class PickCloseOAuthTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val auth = FakeAuthManager()
    private val engine = FakeEngineClient(scope, stepDelayMs = 0)
    private val signal = SetModelSignal(engine)
    private val panels = RealModelPanels(FakeModelCatalog(auth), auth, FakeCustomProviderStore(), signal, scope, FakePiSettings())

    @After fun tearDown() = scope.cancel()

    @Test
    fun backgroundOAuthAutoSelectClosesPickerAndLoginPanel() = runBlocking {
        val s = OverlayStack()
        signal.onSwitched = { p -> PickClose.afterBackgroundSwitch(s, p) }
        s.push(panels.modelPicker())
        // 选未登录的模型 → 记为待选，打开服务商表单（终端形态逐层压栈：选择面板留在下面）
        val r = panels.onPanelEvent("model.select", "model:github-copilot/gpt-4.1", null)
        assertTrue(r is ActionResult.Overlay)
        s.push((r as ActionResult.Overlay).model)
        assertEquals(0, signal.count)
        assertTrue(s.items.value.any { it.id == "model.select" })
        // 开始登录：登录面板压栈，设备码流程在后台走到 Done → 自动选回
        s.push(panels.oauthFlow("github-copilot"))
        withTimeout(5_000) { while (signal.count == 0) delay(10) }
        withTimeout(5_000) { while (s.items.value.any { it.id == "model.select" }) delay(10) }
        val ids = s.items.value.map { it.id }
        assertFalse("选择面板已关：$ids", "model.select" in ids)
        assertFalse("登录面板已关：$ids", "oauth:github-copilot" in ids)
        assertFalse("服务商表单已关：$ids", "provider.form:github-copilot" in ids)
        assertEquals("gpt-4.1", engine.chat.value.model?.id)
    }

    @Test
    fun loginWithoutPickerLeavesStackAlone() {
        val s = OverlayStack()
        s.push(io.github.zr987459.pigo.contract.session.OverlayModel.Text("oauth:github-copilot", "登录", ""))
        assertFalse(PickClose.afterBackgroundSwitch(s, "github-copilot"))
        assertEquals(listOf("oauth:github-copilot"), s.items.value.map { it.id })
        // 选择面板开着但登录的是别的服务商：不动
        s.push(io.github.zr987459.pigo.contract.session.OverlayModel.Text("model.select", "选择模型", ""))
        assertFalse(PickClose.afterBackgroundSwitch(s, "anthropic"))
        assertEquals(2, s.items.value.size)
    }
}
