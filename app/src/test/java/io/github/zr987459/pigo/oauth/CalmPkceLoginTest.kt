package io.github.zr987459.pigo.oauth

import io.github.zr987459.pigo.contract.AuthManager
import io.github.zr987459.pigo.contract.AuthState
import io.github.zr987459.pigo.contract.OAuthStep
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR #21 审查第 1 轮 Important 4：UI 形态的 PKCE 登录能走通（真实 RealModelPanels + UI 形态的落栈逻辑 CalmPanelLogic，
 * 与 CalmSheet 的轮询规则相同：只有 needsOAuthPolling 为 true 时每秒发 refresh；第 2 轮起 NeedCode 表单也轮询）。
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
        withTimeout(5_000) { while (s.top !is OverlayModel.Form) { delay(20); if (CalmPanelLogic.needsOAuthPolling(s.top)) send(s, "refresh") } }
        val form = s.top as OverlayModel.Form
        assertEquals("oauth:anthropic", form.id)
        assertTrue("NeedCode 表单照常轮询（第 2 轮 Important 1）", CalmPanelLogic.needsOAuthPolling(form))
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

    /**
     * 本地回调服务器 + 「粘贴回调 URL」兜底的登录（Codex、Gemini CLI、Antigravity…）：RealAuthManager 在 OpenUrl 后紧跟 NeedCode，
     * 浏览器授权完成后由 pi 自己推进到 Done。第 1 个流程：authorizeUrl → NeedCode → 等回调（或代码）→ Done；
     * 第 2 个流程（[secondFlowAsksDomainFirst] 时）：不带 OpenUrl 的 NeedCode（先问企业域名）。
     */
    private class CallbackAuth(private val base: FakeAuthManager = FakeAuthManager()) : AuthManager by base {
        val callback = CompletableDeferred<Unit>()
        var logins = 0
        var secondFlowAsksDomainFirst = false
        private var code = CompletableDeferred<String>()
        override fun login(provider: String): Flow<OAuthStep> = flow {
            logins++
            emit(OAuthStep.Starting)
            if (logins > 1 && secondFlowAsksDomainFirst) {
                code = CompletableDeferred()
                emit(OAuthStep.NeedCode("企业域名"))
                code.await()
                return@flow
            }
            emit(OAuthStep.OpenUrl("https://auth.openai.com/oauth/authorize?flow=$logins", "在浏览器里完成授权"))
            emit(OAuthStep.NeedCode("粘贴回调 URL（浏览器回调会自动完成）"))
            callback.await()
            val st = AuthState.OAuth("me@example.com")
            emit(OAuthStep.Done(st))
        }
        override suspend fun submitCode(provider: String, code: String) { this.code.complete(code) }
    }

    private fun panelsWith(a: AuthManager) =
        RealModelPanels(FakeModelCatalog(auth), a, FakeCustomProviderStore(), FakeEngineClient(scope, stepDelayMs = 0), scope, FakePiSettings())

    /** 第 2 轮 Important 1：停在 NeedCode 表单、用户没提交，浏览器回调完成登录 → 轮询把面板切到「已登录」；输了一半的代码在多次刷新中不丢。 */
    @Test
    fun callbackCompletionSwitchesFormToLoggedIn() = runBlocking {
        val a = CallbackAuth()
        val p = panelsWith(a)
        val s = OverlayStack()
        s.push(p.oauthFlow("openai-codex"))
        suspend fun poll() { val t = s.top!!; CalmPanelLogic.apply(s, t.id, p.onPanelEvent(t.id, "refresh", null)) }
        withTimeout(5_000) { while (s.top !is OverlayModel.Form) { delay(20); if (CalmPanelLogic.needsOAuthPolling(s.top)) poll() } }
        val form = s.top as OverlayModel.Form
        assertTrue(CalmPanelLogic.needsOAuthPolling(form))
        // 用户粘贴了一半
        s.replaceTop(form.copy(values = mapOf("code" to "http://localhost:1455/auth/call")))
        repeat(5) { poll() }
        assertEquals("http://localhost:1455/auth/call", (s.top as OverlayModel.Form).values["code"])
        // 浏览器回调完成登录（没有提交表单）；CalmSheet 的轮询规则：needsOAuthPolling 为 true 就 refresh
        a.callback.complete(Unit)
        withTimeout(5_000) { while (CalmPanelLogic.needsOAuthPolling(s.top)) { delay(20); poll() } }
        val done = s.top as OverlayModel.Text
        assertTrue(done.text, done.text.startsWith("已登录"))
        assertTrue(CalmPanelLogic.isOAuthFinal(done))
        assertEquals(1, s.items.value.size)
    }

    /** 第 2 轮 Nit 4：取消 / 重试后新流程先出现不带 OpenUrl 的 NeedCode 时，表单里不显示上一次已失效的授权链接。 */
    @Test
    fun staleOauthUrlIsClearedOnCancelAndRetry() = runBlocking {
        for (via in listOf("cancel", "retry")) {
            val a = CallbackAuth().apply { secondFlowAsksDomainFirst = true }
            val p = panelsWith(a)
            val s = OverlayStack()
            s.push(p.oauthFlow("openai-codex"))
            suspend fun poll() { val t = s.top!!; CalmPanelLogic.apply(s, t.id, p.onPanelEvent(t.id, "refresh", null)) }
            withTimeout(5_000) { while (s.top !is OverlayModel.Form) { delay(20); poll() } }
            assertTrue(CalmPanelLogic.oauthUrl(s.top)!!.contains("flow=1"))
            if (via == "cancel") {
                CalmPanelLogic.apply(s, "oauth:openai-codex", p.onPanelEvent("oauth:openai-codex", "cancel", null))
                assertTrue(s.items.value.isEmpty())
                s.push(p.oauthFlow("openai-codex"))
            } else {
                CalmPanelLogic.apply(s, "oauth:openai-codex", p.onPanelEvent("oauth:openai-codex", "retry", null))
            }
            withTimeout(5_000) { while ((s.top as? OverlayModel.Form)?.fields?.firstOrNull()?.label != "企业域名") { delay(20); poll() } }
            assertNull("$via 后不显示旧链接", CalmPanelLogic.oauthUrl(s.top))
            assertNull((s.top as OverlayModel.Form).fields.first().help)
        }
    }
}
