package io.github.zr987459.pigo.feature.models

import io.github.zr987459.pigo.contract.ModelRef
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.ProviderApi
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.fake.FakeAuthManager
import io.github.zr987459.pigo.contract.fake.FakeCustomProviderStore
import io.github.zr987459.pigo.contract.fake.FakeEngineClient
import io.github.zr987459.pigo.contract.fake.FakeModelCatalog
import io.github.zr987459.pigo.contract.session.FlowState
import io.github.zr987459.pigo.contract.session.OverlayModel
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** r3/nits-models：PR #19 定向复核 Nit 1–3、PR #24 审查 Nit 1（结构化成败标志）的回归测试。 */
class RealModelPanelsNitsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val auth = FakeAuthManager()
    private val catalog = FakeModelCatalog(auth)
    private val store = FakeCustomProviderStore()
    private val engine = FakeEngineClient(scope, stepDelayMs = 0)
    private val panels = RealModelPanels(catalog, auth, store, engine, scope)

    @After fun tearDown() = scope.cancel()

    private fun ActionResult.toast() = this as ActionResult.Toast
    private fun ActionResult.overlay() = (this as ActionResult.Overlay).model
    private fun keyHelp(f: OverlayModel) = (f as OverlayModel.Form).fields.first { it.key == RealModelPanels.KEY_FIELD }.help

    // ---- Nit 1：Key 被丢弃后保存成功，提示里不再要求重新填写 Key

    @Test fun saveAfterDroppedKeyDoesNotAskToRefillKey() = runBlocking {
        panels.onPanelEvent(RealModelPanels.ID_PROVIDERS, "custom", null)
        panels.onPanelEvent(RealModelPanels.ID_CUSTOM, "test", """{"id":"a","baseUrl":"http://a/v1","apiKey":"sk-AAAA"}""")
        // 改接口地址 → Key 被丢弃（失败 / 测试提示里照常提醒）
        val dropped = panels.onPanelEvent(RealModelPanels.ID_CUSTOM, "test", """{"id":"a","baseUrl":"http://b/v1"}""").toast()
        assertTrue(dropped.text, dropped.text.contains(RealModelPanels.KEY_DROPPED))
        // 保存成功：只有「已保存」，不附「请重新填写」；成功标志为 false
        val saved = panels.onPanelEvent(RealModelPanels.ID_CUSTOM, "submit", """{"id":"a","baseUrl":"http://b/v1","models":"m1"}""").toast()
        assertEquals("已保存 a", saved.text)
        assertFalse(saved.failed)
        assertTrue(store.list().any { it.id == "a" })
        // 表单帮助文字也已恢复正常
        assertFalse(keyHelp(panels.customProviderForm(null)) == RealModelPanels.KEY_DROPPED)
    }

    @Test fun failedSaveAfterDroppedKeyStillAnnouncesIt() = runBlocking {
        panels.onPanelEvent(RealModelPanels.ID_PROVIDERS, "custom", null)
        panels.onPanelEvent(RealModelPanels.ID_CUSTOM, "test", """{"id":"a","baseUrl":"http://a/v1","apiKey":"sk-AAAA"}""")
        // 名称为空 → 保存失败：失败提示照样带上 Key 已清除，且标为失败
        val r = panels.onPanelEvent(RealModelPanels.ID_CUSTOM, "submit", """{"id":"","baseUrl":"http://b/v1"}""").toast()
        assertTrue(r.text, r.text.startsWith("请填写名称") && r.text.contains(RealModelPanels.KEY_DROPPED))
        assertTrue(r.failed)
    }

    // ---- Nit 3（复核编号）：确认框「关闭 / 取消」= 取消并清理已填 Key 与待定状态

    @Test fun cancelRemoveConfirmDropsTypedKeyAndDraft() = runBlocking {
        panels.onPanelEvent(RealModelPanels.ID_CUSTOM, "submit", """{"id":"my","baseUrl":"http://a/v1","models":"m1"}""")
        val formId = "${RealModelPanels.ID_CUSTOM}:my"
        // 编辑：填了新 Key（未保存）后点「删除」
        val c = panels.onPanelEvent(formId, "remove", """{"id":"my","baseUrl":"http://a/v1","apiKey":"sk-NEW1"}""").overlay()
        assertTrue(c is OverlayModel.Confirm)
        assertTrue(keyHelp(panels.customProviderForm("my"))!!.startsWith("已填写"))
        // 关闭确认框：不删除，但已填未存的 Key 被清掉；返回清理后的表单
        val back = panels.onPanelEvent(c.id, "cancel", null).overlay()
        assertEquals(formId, back.id)
        assertTrue(store.list().any { it.id == "my" })
        assertFalse("关闭确认框后不应再显示「已填写」", keyHelp(back)!!.startsWith("已填写"))
        // 再保存不会把被放弃的 Key 写进去
        panels.onPanelEvent(formId, "submit", """{"id":"my","baseUrl":"http://a/v1","models":"m1"}""")
        assertTrue("没有 Key 被写入", store.discover("http://a/v1", ProviderApi.OPENAI_COMPLETIONS, null, "my") is Outcome.Err)
    }

    @Test fun cancelClearConfirmDropsPendingModel() = runBlocking {
        val form = panels.onPanelEvent(RealModelPanels.ID_MODEL, "model:qwen-token-plan-cn/qwen3-coder-plus").overlay()
        assertEquals(ModelRef("qwen-token-plan-cn", "qwen3-coder-plus"), panels.pending.value)
        val c = panels.onPanelEvent(form.id, "clear", null).overlay()
        assertTrue(c is OverlayModel.Confirm)
        val back = panels.onPanelEvent(c.id, "cancel", null).overlay()
        assertEquals(form.id, back.id)
        assertNull("关闭确认框后待选模型不再自动选回", panels.pending.value)
    }

    // ---- Nit 2（复核编号）：OAuth 失败面板同时满足两种形态画「重试」的契约

    @Test fun oauthFailedPanelCarriesRetryContractForBothForms() = runBlocking {
        panels.oauthFlow("anthropic")
        withTimeout(3_000) { while (panels.onPanelEvent("oauth:anthropic", "refresh", null).overlay() !is OverlayModel.Form) delay(10) }
        panels.onPanelEvent("oauth:anthropic", "submit", """{"code":"bad"}""")
        var p: OverlayModel = panels.onPanelEvent("oauth:anthropic", "refresh", null).overlay()
        withTimeout(3_000) { while ((p as? OverlayModel.Text)?.flow != FlowState.FAILED) { delay(10); p = panels.onPanelEvent("oauth:anthropic", "refresh", null).overlay() } }
        val failed = p as OverlayModel.Text
        // 终端 ⌘（TermInput.oauthCaps）按 flow == FAILED 画「enter 重试」；UI（CalmPanelLogic.oauthRetryable）按 oauth: + 「登录失败」画「重试」
        assertTrue(failed.id.startsWith("oauth:"))
        assertEquals(FlowState.FAILED, failed.flow)
        assertTrue(failed.text, failed.text.startsWith("登录失败"))
        // 两个按钮发的都是面板事件 retry：重新开始登录
        val again = panels.onPanelEvent("oauth:anthropic", "retry", null).overlay()
        assertEquals("oauth:anthropic", again.id)
        assertFalse((again as? OverlayModel.Text)?.flow == FlowState.FAILED)
    }

    // ---- PR #24 Nit 1：结构化成败标志，与文案无关

    @Test fun toastFailedFlagIsStructuredNotTextual() = runBlocking {
        // 成功提示里含「失败」二字（服务商名）也不算失败
        val ok = panels.onPanelEvent(RealModelPanels.ID_CUSTOM, "submit", """{"id":"失败测试","baseUrl":"http://a/v1","models":"m1"}""").toast()
        assertEquals("已保存 失败测试", ok.text)
        assertFalse(ok.failed)
        // 各种失败都带标志
        assertTrue(panels.onPanelEvent(RealModelPanels.ID_CUSTOM, "submit", """{"id":"","baseUrl":"http://a/v1"}""").toast().failed)
        assertTrue(panels.onPanelEvent(RealModelPanels.ID_MODEL, "nope", null).toast().failed)
        assertTrue(panels.onPanelEvent(RealModelPanels.ID_MODEL, RealModelPanels.CAP_SELECT, null).toast().failed)
        assertTrue(panels.onPanelEvent("provider.form:qwen-token-plan-cn", "nope", null).toast().failed)
    }
}
