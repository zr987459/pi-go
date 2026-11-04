package io.github.zr987459.pigo.feature.chat.ui.calm

import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.session.KeyCap
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.OverlayStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** r3/app 第 3 步：UI 形态的面板事件（Form.actions、确认框、OAuth 重试 / 轮询、关闭时取消）结果落栈逻辑。 */
class CalmPanelLogicTest {

    private fun form(id: String) = OverlayModel.Form(id, "表单", actions = listOf(KeyCap("t", "测试", "test"), KeyCap("c", "清除", "clear")))

    @Test
    fun confirmIsPushedAndCancelReturnsToForm() {
        val s = OverlayStack()
        s.push(form("provider.form:deepseek"))
        CalmPanelLogic.apply(s, "provider.form:deepseek", ActionResult.Overlay(OverlayModel.Confirm("confirm.clear:deepseek", "清除", "确定？", dangerous = true)))
        assertEquals(listOf("provider.form:deepseek", "confirm.clear:deepseek"), s.items.value.map { it.id })
        s.remove("confirm.clear:deepseek") // 取消
        assertEquals("provider.form:deepseek", s.top?.id)
    }

    @Test
    fun confirmResultReplacesTopAndDoneCloses() {
        val s = OverlayStack()
        s.push(form("provider.form:deepseek"))
        // 确认后确认框已移除，面板方返回刷新后的表单 → 替换栈顶，不叠两层。
        CalmPanelLogic.apply(s, "confirm.clear:deepseek", ActionResult.Overlay(form("provider.form:deepseek")))
        assertEquals(1, s.items.value.size)
        // 下一步面板（如服务商列表 → 服务商表单）替换栈顶。
        s.replaceTop(OverlayModel.SelectList("auth.login", "服务商"))
        CalmPanelLogic.apply(s, "auth.login", ActionResult.Overlay(form("provider.form:qwen")))
        assertEquals(listOf("provider.form:qwen"), s.items.value.map { it.id })
        assertNull(CalmPanelLogic.apply(s, "provider.form:qwen", ActionResult.Done))
        assertTrue(s.items.value.isEmpty())
    }

    @Test
    fun toastKeepsPanelOpen() {
        val s = OverlayStack()
        s.push(form("provider.form:deepseek"))
        assertEquals("连接成功", CalmPanelLogic.apply(s, "provider.form:deepseek", ActionResult.Toast("连接成功")))
        assertEquals("provider.form:deepseek", s.top?.id)
    }

    @Test
    fun formActionsAndJsonKeepSecretsOutOfValues() {
        val f = form("provider.form:deepseek")
        assertEquals(listOf("test" to "测试", "clear" to "清除"), CalmPanelLogic.formActions(f))
        val json = CalmPanelLogic.formJson(mapOf("baseUrl" to "https://x"), mapOf("apiKey" to "sk-1"))
        assertTrue(json.contains("\"apiKey\":\"sk-1\"") && json.contains("\"baseUrl\":\"https://x\""))
    }

    @Test
    fun oauthRetryPollingAndDismiss() {
        val waiting = OverlayModel.Text("oauth:anthropic", "登录", "请在浏览器打开 https://claude.ai/oauth?x=1 完成登录")
        val failed = OverlayModel.Text("oauth:anthropic", "登录", "登录失败：超时")
        val ok = OverlayModel.Text("oauth:anthropic", "登录", "已登录 anthropic")
        assertTrue(CalmPanelLogic.needsOAuthPolling(waiting))
        assertFalse(CalmPanelLogic.needsOAuthPolling(failed))
        assertFalse(CalmPanelLogic.needsOAuthPolling(ok))
        assertTrue(CalmPanelLogic.oauthRetryable(failed))
        assertFalse(CalmPanelLogic.oauthRetryable(waiting))
        assertEquals("https://claude.ai/oauth?x=1", CalmPanelLogic.oauthUrl(waiting))
        assertEquals("cancel", CalmPanelLogic.dismissEvent(waiting))
        assertNull(CalmPanelLogic.dismissEvent(ok))
        assertEquals("cancel", CalmPanelLogic.dismissEvent(form("provider.custom")))
        assertNull(CalmPanelLogic.dismissEvent(form("provider.form:deepseek")))
    }

    @Test
    fun keyCapValueIsSelectedItem() {
        assertEquals("model:deepseek/deepseek-chat", CalmPanelLogic.keyCapValue(OverlayModel.SelectList("model.select", "模型", selectedKey = "model:deepseek/deepseek-chat")))
    }
}
