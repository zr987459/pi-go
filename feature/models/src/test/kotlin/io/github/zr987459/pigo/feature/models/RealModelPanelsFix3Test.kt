package io.github.zr987459.pigo.feature.models

import io.github.zr987459.pigo.contract.AuthManager
import io.github.zr987459.pigo.contract.AuthState
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.OAuthStep
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.fake.FakeAuthManager
import io.github.zr987459.pigo.contract.fake.FakeCustomProviderStore
import io.github.zr987459.pigo.contract.fake.FakeEngineClient
import io.github.zr987459.pigo.contract.fake.FakeModelCatalog
import io.github.zr987459.pigo.contract.session.OverlayModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** PR #19 审查第 3 轮：OAuth 输入代码 / 选择提交后的进度态、防重复提交、失败重试；删除与清除先确认；Key 丢弃提示。 */
class RealModelPanelsFix3Test {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fake = FakeAuthManager(mapOf("deepseek" to AuthState.ApiKey("1234")))

    /** 包一层：记录提交次数；提交后要等 [release] 才把答案交给登录会话（模拟异步验证）。 */
    private class SlowAuth(private val d: FakeAuthManager) : AuthManager by d {
        val codes = AtomicInteger()
        val selects = AtomicInteger()
        var release = CompletableDeferred<Unit>()
        var selectFlow = false
        override suspend fun submitCode(provider: String, code: String) {
            codes.incrementAndGet()
            val r = release
            CoroutineScope(Dispatchers.Default).async { r.await(); d.submitCode(provider, code) }
        }
        private val sel = CompletableDeferred<String>()
        override fun login(provider: String): Flow<OAuthStep> = if (!selectFlow) d.login(provider) else flow {
            emit(OAuthStep.Starting)
            emit(OAuthStep.Select("选择账号类型", listOf("个人", "企业")))
            val v = sel.await()
            emit(OAuthStep.Progress("正在完成…"))
            emit(OAuthStep.Done(AuthState.OAuth("$v@example.com")))
        }
        override suspend fun submitSelection(provider: String, value: String) {
            selects.incrementAndGet()
            val r = release
            CoroutineScope(Dispatchers.Default).async { r.await(); sel.complete(value) }
        }
    }

    private val auth = SlowAuth(fake)
    private val catalog = FakeModelCatalog(fake)
    private val store = FakeCustomProviderStore()
    private val engine = FakeEngineClient(scope, stepDelayMs = 0)
    private val panels = RealModelPanels(catalog, auth, store, engine, scope)

    @After fun tearDown() = scope.cancel()

    private fun ActionResult.overlay() = (this as ActionResult.Overlay).model
    private suspend fun refresh(p: String = "anthropic") = panels.onPanelEvent("oauth:$p", "refresh", null).overlay()
    private fun OverlayModel.text() = (this as? OverlayModel.Text)?.text.orEmpty()

    private suspend fun untilForm() {
        panels.oauthFlow("anthropic")
        withTimeout(3_000) { while (refresh() !is OverlayModel.Form) delay(10) }
    }

    @Test fun codeSubmitShowsVerifyingThenSignedIn() = runBlocking {
        untilForm()
        val after = panels.onPanelEvent("oauth:anthropic", "submit", """{"code":"ok"}""").overlay()
        // 提交后立刻是「正在验证…」（Text，设置页会持续刷新），不再是同一张表单
        assertEquals(RealModelPanels.OAUTH_VERIFYING, after.text())
        assertEquals(RealModelPanels.OAUTH_VERIFYING, refresh().text())
        auth.release.complete(Unit)
        var p = refresh()
        withTimeout(3_000) { while (!p.text().startsWith("已登录")) { delay(10); p = refresh() } }
        assertTrue(fake.state("anthropic") is AuthState.OAuth)
    }

    @Test fun duplicateSubmitWhileVerifyingIsSentOnce() = runBlocking {
        untilForm()
        // 并发连点三次 + 在途时再点一次
        (1..3).map { scope.async { panels.onPanelEvent("oauth:anthropic", "submit", """{"code":"ok"}""") } }.awaitAll()
        panels.onPanelEvent("oauth:anthropic", "code", "ok")
        assertEquals(1, auth.codes.get())
        auth.release.complete(Unit)
        withTimeout(3_000) { while (!refresh().text().startsWith("已登录")) delay(10) }
        assertEquals(1, auth.codes.get())
    }

    @Test fun failureShowsReasonAndRetryRestarts() = runBlocking {
        untilForm()
        auth.release.complete(Unit)
        panels.onPanelEvent("oauth:anthropic", "submit", """{"code":"bad"}""")
        var t = ""
        withTimeout(3_000) { while (!t.startsWith("登录失败")) { delay(10); t = refresh().text() } }
        assertTrue(t, t.contains("授权码无效") && t.contains("重试"))
        // 重试：重新开始登录，回到输入代码，再提交正确代码可以成功
        panels.onPanelEvent("oauth:anthropic", "retry", null)
        withTimeout(3_000) { while (refresh() !is OverlayModel.Form) delay(10) }
        panels.onPanelEvent("oauth:anthropic", "submit", """{"code":"ok"}""")
        withTimeout(3_000) { while (!refresh().text().startsWith("已登录")) delay(10) }
        assertEquals(2, auth.codes.get())
    }

    @Test fun selectSubmitShowsVerifyingThenDoneAndSendsOnce() = runBlocking {
        auth.selectFlow = true
        panels.oauthFlow("anthropic")
        withTimeout(3_000) { while (refresh() !is OverlayModel.SelectList) delay(10) }
        assertEquals(RealModelPanels.OAUTH_VERIFYING, panels.onPanelEvent("oauth:anthropic", "select:企业", null).overlay().text())
        panels.onPanelEvent("oauth:anthropic", "select:企业", null)
        assertEquals(1, auth.selects.get())
        auth.release.complete(Unit)
        withTimeout(3_000) { while (!refresh().text().startsWith("已登录")) delay(10) }
    }

    @Test fun verifyingTimesOutBackToStep() = runBlocking {
        var now = 0L
        val p2 = RealModelPanels(catalog, auth, store, engine, scope, clock = { now })
        p2.oauthFlow("anthropic")
        withTimeout(3_000) { while (p2.onPanelEvent("oauth:anthropic", "refresh", null).overlay() !is OverlayModel.Form) delay(10) }
        p2.onPanelEvent("oauth:anthropic", "submit", """{"code":"ok"}""") // 不 release：登录会话一直不回应
        now += RealModelPanels.OAUTH_VERIFY_TIMEOUT_MS + 1
        assertTrue(p2.onPanelEvent("oauth:anthropic", "refresh", null).overlay() is OverlayModel.Form)
    }

    @Test fun removeCustomNeedsConfirmAndClosesForm() = runBlocking {
        panels.onPanelEvent("provider.custom", "submit", """{"id":"my","baseUrl":"http://a/v1","models":"m1"}""")
        assertTrue(store.list().any { it.id == "my" })
        val c = panels.onPanelEvent("provider.custom:my", "remove", """{"id":"my","baseUrl":"http://a/v1"}""").overlay()
        assertTrue(c is OverlayModel.Confirm && c.dangerous)
        assertTrue("未确认前不删除", store.list().any { it.id == "my" })
        // 取消（关闭确认框）不删，只关确认框（Done），表单不动（PR #25 审查第 1 轮 Nit 2：改回正确预期）
        assertEquals(ActionResult.Done, panels.onPanelEvent(c.id, "cancel", null))
        assertTrue(store.list().any { it.id == "my" })
        // 确认：删除并关闭面板（Done），不再留着可「保存」重建的表单
        assertEquals(ActionResult.Done, panels.onPanelEvent(c.id, "confirm", null))
        assertFalse(store.list().any { it.id == "my" })
    }

    @Test fun clearAndLogoutNeedConfirm() = runBlocking {
        assertTrue(panels.onPanelEvent("provider.form:deepseek", "clear", null).overlay() is OverlayModel.Confirm)
        assertTrue(fake.state("deepseek").configured)
        val back = panels.onPanelEvent("confirm.clear:deepseek", "confirm", null).overlay() as OverlayModel.Form
        assertEquals(AuthState.None, fake.state("deepseek"))
        assertFalse("已清除后不再显示「清除」", back.actions.any { it.actionId == "clear" })
        // 退出登录列表同样先确认
        fake.setApiKey("deepseek", "sk-x", emptyMap())
        assertTrue(panels.onPanelEvent("auth.logout", "provider:deepseek", null).overlay() is OverlayModel.Confirm)
        assertTrue(fake.state("deepseek").configured)
        panels.onPanelEvent("confirm.logout:deepseek", "confirm", null)
        assertEquals(AuthState.None, fake.state("deepseek"))
    }

    @Test fun droppedKeyIsAnnounced() = runBlocking {
        panels.onPanelEvent("auth.login", "custom", null)
        panels.onPanelEvent("provider.custom", "test", """{"id":"a","baseUrl":"http://a/v1","apiKey":"sk-AAAA"}""")
        // 改接口地址：Key 被丢弃，结果消息和表单里都提示重新填写
        val r = panels.onPanelEvent("provider.custom", "test", """{"id":"a","baseUrl":"http://b/v1"}""") as ActionResult.Toast
        assertTrue(r.text, r.text.contains(RealModelPanels.KEY_DROPPED))
        val form = panels.customProviderForm(null) as OverlayModel.Form
        assertEquals(RealModelPanels.KEY_DROPPED, form.fields.first { it.key == RealModelPanels.KEY_FIELD }.help)
        // 重新填了 Key 后不再提示
        val r2 = panels.onPanelEvent("provider.custom", "test", """{"id":"a","baseUrl":"http://b/v1","apiKey":"sk-BBBB"}""") as ActionResult.Toast
        assertFalse(r2.text.contains(RealModelPanels.KEY_DROPPED))
    }

    @Suppress("unused") private val unusedErr = ErrCode.PROVIDER
}
