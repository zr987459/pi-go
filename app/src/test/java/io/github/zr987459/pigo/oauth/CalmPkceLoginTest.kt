package io.github.zr987459.pigo.oauth

import io.github.zr987459.pigo.contract.AuthState
import io.github.zr987459.pigo.contract.fake.FakeAuthManager
import io.github.zr987459.pigo.contract.fake.FakeCustomProviderStore
import io.github.zr987459.pigo.contract.fake.FakeEngineClient
import io.github.zr987459.pigo.contract.fake.FakeModelCatalog
import io.github.zr987459.pigo.contract.fake.FakePiSettings
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.OverlayStack
import io.github.zr987459.pigo.feature.chat.ui.calm.CalmPanelLogic
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
 * PR #21 审查第 1 轮 Important 4：UI 形态的 PKCE 登录能走通（真实 RealModelPanels + UI 形态的落栈逻辑 CalmPanelLogic，
 * 与 CalmSheet 的轮询规则相同：只有 needsOAuthPolling 为 true 时每秒发 refresh）。
 */
class CalmPkceLoginTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val auth = FakeAuthManager()
    private val panels = RealModelPanels(FakeModelCatalog(auth), auth, FakeCustomProviderStore(), FakeEngineClient(scope, stepDelayMs = 0), scope, FakePiSettings())

    @After fun tearDown() = scope.cancel()

    private suspend fun send(s: OverlayStack, key: String, value: String? = null) {
        val top = s.top!!
        CalmPanelLogic.apply(s, top.id, panels.onPanelEvent(top.id, key, value))
    }

    @Test
    fun pkceLoginCompletesInUiForm() = runBlocking {
        val s = OverlayStack()
        s.push(panels.oauthFlow("anthropic"))
        // 轮询文字面板，直到出现填授权码的表单
        withTimeout(5_000) { while (CalmPanelLogic.needsOAuthPolling(s.top)) { delay(20); send(s, "refresh") } }
        val form = s.top as OverlayModel.Form
        assertEquals("oauth:anthropic", form.id)
        assertFalse("NeedCode 表单不再轮询", CalmPanelLogic.needsOAuthPolling(form))
        val url = CalmPanelLogic.oauthUrl(form)
        assertTrue("NeedCode 表单带授权链接：$url", url?.startsWith("https://example.com/oauth/authorize") == true)

        // 用户粘贴授权码（FormView.set 写进 top.values）；即使中途来了两次 refresh，值也不丢
        s.replaceTop(form.copy(values = mapOf("code" to "ok")))
        repeat(2) { send(s, "refresh") }
        val typed = (s.top as OverlayModel.Form).values
        assertEquals("ok", typed["code"])

        // 提交 → 校验中（文字面板，恢复轮询）→ 已登录
        send(s, "submit", CalmPanelLogic.formJson(typed))
        withTimeout(5_000) {
            while (!CalmPanelLogic.isOAuthFinal(s.top!!)) { delay(20); if (CalmPanelLogic.needsOAuthPolling(s.top)) send(s, "refresh") }
        }
        assertTrue((s.top as OverlayModel.Text).text, (s.top as OverlayModel.Text).text.startsWith("已登录"))
        assertTrue(auth.state("anthropic") is AuthState.OAuth)
    }
}
