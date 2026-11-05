package io.github.zr987459.pigo.feature.chat.ui.calm

import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.session.FormField
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.OverlayStack
import io.github.zr987459.pigo.contract.settings.PiSettingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PR #21（r3/app）审查第 1 轮：OAuth 授权码不被轮询清空、确认框 Done 关父面板、返回键顺序。 */
class CalmAppFix1Test {
    private fun needCode(help: String? = "授权链接：https://claude.ai/oauth/authorize?code=true&x=1") =
        OverlayModel.Form("oauth:anthropic", "登录 anthropic", listOf(FormField("code", "粘贴授权码", PiSettingType.Str, help = help)), submitLabel = "提交")

    /** Important 4：即使面板方连续两次 refresh 返回不带值的新表单，用户输入的授权码仍在。 */
    @Test
    fun typedCodeSurvivesTwoRefreshes() {
        val s = OverlayStack()
        s.push(needCode())
        // 第 2 轮 Important 1：NeedCode 表单与 Select 列表也照常轮询（浏览器回调可能自己完成登录），靠 keepTyped 保留输入。
        assertTrue(CalmPanelLogic.needsOAuthPolling(s.top))
        assertTrue(CalmPanelLogic.needsOAuthPolling(OverlayModel.SelectList("oauth:x", "选择")))
        assertTrue(CalmPanelLogic.needsOAuthPolling(OverlayModel.Text("oauth:x", "登录", "正在开始登录…")))
        // FormView.set() 写进 top.values
        s.replaceTop(needCode().copy(values = mapOf("code" to "abc#def")))
        repeat(2) { CalmPanelLogic.apply(s, "oauth:anthropic", ActionResult.Overlay(needCode())) }
        assertEquals("abc#def", (s.top as OverlayModel.Form).values["code"])
        assertEquals(1, s.items.value.size)
        // 换成别的表单（字段不同）不带旧值
        val other = OverlayModel.Form("oauth:anthropic", "登录", listOf(FormField("otp", "验证码", PiSettingType.Str)))
        CalmPanelLogic.apply(s, "oauth:anthropic", ActionResult.Overlay(other))
        assertTrue((s.top as OverlayModel.Form).values.isEmpty())
    }

    /** Important 4：NeedCode 表单里能看到并打开授权链接。 */
    @Test
    fun needCodeFormExposesAuthUrl() {
        assertEquals("https://claude.ai/oauth/authorize?code=true&x=1", CalmPanelLogic.oauthUrl(needCode()))
        assertNull(CalmPanelLogic.oauthUrl(needCode(help = null)))
        assertNull(CalmPanelLogic.oauthUrl(needCode().copy(id = "provider.form:x")))
    }

    /** Nit 1：删除自定义服务商的确认框返回 Done → 确认框与它的编辑表单一起关，不能再「保存」重建。 */
    @Test
    fun confirmDoneClosesParentForm() {
        val s = OverlayStack()
        s.push(OverlayModel.SelectList("auth.login", "服务商"))
        s.push(OverlayModel.Form("provider.custom:my", "自定义"))
        s.push(OverlayModel.Confirm("confirm.remove:my", "删除", "确定删除？", dangerous = true))
        assertNull(CalmPanelLogic.apply(s, "confirm.remove:my", ActionResult.Done))
        assertEquals(listOf("auth.login"), s.items.value.map { it.id })
    }

    /** 确认框的非 Done 结果：先关确认框，再按原面板处理（同 id 替换、Toast 留在原面板）。 */
    @Test
    fun confirmOtherResultsReturnToParent() {
        val s = OverlayStack()
        s.push(OverlayModel.Form("provider.form:deepseek", "DeepSeek"))
        s.push(OverlayModel.Confirm("confirm.clear:deepseek", "清除", "确定？"))
        CalmPanelLogic.apply(s, "confirm.clear:deepseek", ActionResult.Overlay(OverlayModel.Form("provider.form:deepseek", "DeepSeek（已清除）")))
        assertEquals(listOf("DeepSeek（已清除）"), s.items.value.map { it.title })
        s.push(OverlayModel.Confirm("confirm.clear:deepseek", "清除", "确定？"))
        assertEquals("已清除", CalmPanelLogic.apply(s, "confirm.clear:deepseek", ActionResult.Toast("已清除")))
        assertEquals(listOf("provider.form:deepseek"), s.items.value.map { it.id })
    }

    /**
     * Important 3：返回键逐层关（第 2 轮 Nit 2 起按画面层级：面板在最上）；扩展对话框不替用户作答。
     * r5（OpenMinis 导航）：会话列表是首页——列表在前台时不拦截（外层退到后台）；聊天页什么都没开时返回 = 回到会话列表（LIST）。
     */
    @Test
    fun backOrder() {
        val panel = OverlayModel.SelectList("model.select", "模型")
        assertEquals(CalmBack.Step.OVERLAY, CalmBack.step(true, panel, true, true))
        assertEquals(CalmBack.Step.EDITOR, CalmBack.step(true, null, true, true))
        assertEquals(CalmBack.Step.OVERLAY, CalmBack.step(false, panel, true, true))
        assertEquals("会话列表在前台：交给外层退到后台", CalmBack.Step.NONE, CalmBack.step(false, null, true, true))
        assertEquals(CalmBack.Step.SEARCH, CalmBack.step(false, null, false, true))
        assertEquals("聊天页什么都没开：回会话列表", CalmBack.Step.LIST, CalmBack.step(false, null, false, false))
        val dlg = OverlayModel.ExtDialog("ext:1", "确认", ExtUiRequest(id = "1", method = "confirm", title = "确认"), 0L)
        assertEquals(CalmBack.Step.NONE, CalmBack.step(false, dlg, false, false))
    }
}
