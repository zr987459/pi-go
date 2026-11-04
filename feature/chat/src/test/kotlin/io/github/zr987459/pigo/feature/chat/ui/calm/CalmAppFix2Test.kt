package io.github.zr987459.pigo.feature.chat.ui.calm

import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.session.FlowState
import io.github.zr987459.pigo.contract.session.FormField
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.OverlayStack
import io.github.zr987459.pigo.contract.session.SelectGroup
import io.github.zr987459.pigo.contract.session.SelectItem
import io.github.zr987459.pigo.contract.settings.PiSettingType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** PR #21（r3/app）审查第 2 轮：OAuth 表单期间照常轮询、确认框防连点与按 id 关闭、返回键按画面层级、「＋」菜单文案。 */
class CalmAppFix2Test {
    private fun needCode() =
        OverlayModel.Form("oauth:openai-codex", "登录", listOf(FormField("code", "粘贴回调 URL", PiSettingType.Str)), submitLabel = "提交")

    private fun dlg() = OverlayModel.ExtDialog("ext:req-1", "确认？", ExtUiRequest(id = "req-1", method = "confirm"), 0L)

    /** Important 1：等输入的 NeedCode 表单 / Select 列表照常轮询，到终点（DONE / FAILED）停；非 OAuth 面板不轮询。 */
    @Test
    fun oauthInputStepsKeepPollingUntilFinal() {
        assertTrue(CalmPanelLogic.needsOAuthPolling(needCode()))
        assertTrue(CalmPanelLogic.needsOAuthPolling(OverlayModel.SelectList("oauth:x", "选择")))
        assertFalse(CalmPanelLogic.needsOAuthPolling(OverlayModel.Text("oauth:x", "登录", "已登录", flow = FlowState.DONE)))
        assertFalse(CalmPanelLogic.needsOAuthPolling(OverlayModel.Text("oauth:x", "登录", "登录失败：x", flow = FlowState.FAILED)))
        assertFalse(CalmPanelLogic.needsOAuthPolling(OverlayModel.Form("provider.form:x", "表单")))
        assertFalse(CalmPanelLogic.needsOAuthPolling(null))
    }

    /** Important 1：表单期间登录流自己走到 Done（浏览器回调），刷新结果替换表单 → 显示「已登录」，不再轮询。 */
    @Test
    fun formSwitchesToLoggedInWhenFlowCompletesByCallback() {
        val s = OverlayStack()
        s.push(needCode())
        s.replaceTop(needCode().copy(values = mapOf("code" to "http://localhost:1455/auth/callback?co")))
        repeat(3) { CalmPanelLogic.apply(s, "oauth:openai-codex", ActionResult.Overlay(needCode())) }
        assertEquals("http://localhost:1455/auth/callback?co", (s.top as OverlayModel.Form).values["code"])
        CalmPanelLogic.apply(s, "oauth:openai-codex", ActionResult.Overlay(OverlayModel.Text("oauth:openai-codex", "登录", "已登录", flow = FlowState.DONE)))
        val top = s.top as OverlayModel.Text
        assertEquals("已登录", top.text)
        assertEquals(1, s.items.value.size)
        assertFalse(CalmPanelLogic.needsOAuthPolling(top))
    }

    /** Important 1：Select 列表被轮询刷新时保留搜索词与高亮项。 */
    @Test
    fun selectRefreshKeepsQueryAndSelection() {
        val fresh = OverlayModel.SelectList("oauth:x", "选择", listOf(SelectGroup(null, listOf(SelectItem("select:a", "a"), SelectItem("select:b", "b")))))
        val s = OverlayStack()
        s.push(fresh.copy(query = "b", selectedKey = "select:b"))
        repeat(2) { CalmPanelLogic.apply(s, "oauth:x", ActionResult.Overlay(fresh)) }
        val top = s.top as OverlayModel.SelectList
        assertEquals("b", top.query)
        assertEquals("select:b", top.selectedKey)
        // 新模型自带的值优先
        CalmPanelLogic.apply(s, "oauth:x", ActionResult.Overlay(fresh.copy(selectedKey = "select:a")))
        assertEquals("select:a", (s.top as OverlayModel.SelectList).selectedKey)
    }

    /** Nit 1：确认框上方压着 ExtDialog 时按 id 找到它——Done 连同父面板一起关；其他结果确认框也不残留在栈里。 */
    @Test
    fun confirmBelowPinnedExtDialogIsRemovedById() {
        val s = OverlayStack()
        s.push(OverlayModel.SelectList("auth.login", "服务商"))
        s.push(OverlayModel.Form("provider.custom:my", "自定义"))
        s.push(OverlayModel.Confirm("confirm.remove:my", "删除", "确定删除？", dangerous = true))
        s.push(dlg())
        assertNull(CalmPanelLogic.apply(s, "confirm.remove:my", ActionResult.Done))
        assertEquals(listOf("auth.login", "ext:req-1"), s.items.value.map { it.id })

        val s2 = OverlayStack()
        s2.push(OverlayModel.Form("provider.form:deepseek", "DeepSeek"))
        s2.push(OverlayModel.Confirm("confirm.clear:deepseek", "清除", "确定？"))
        s2.push(dlg())
        CalmPanelLogic.apply(s2, "confirm.clear:deepseek", ActionResult.Overlay(OverlayModel.Form("provider.form:deepseek", "DeepSeek（已清除）")))
        assertEquals(listOf("provider.form:deepseek", "ext:req-1"), s2.items.value.map { it.id })
        assertEquals("DeepSeek（已清除）", s2.items.value.first().title)
        s2.remove("ext:req-1") // ExtDialog 回答后不会再露出旧确认框
        assertFalse(s2.items.value.any { it is OverlayModel.Confirm })
    }

    /** Nit 1：连点「确定」只发一次 confirm（在途时与处理完后都不再发）；refresh 等其他事件不受限。 */
    @Test
    fun confirmDoubleTapSendsOnce() = runBlocking {
        val s = OverlayStack()
        s.push(OverlayModel.Form("provider.custom:my", "自定义"))
        s.push(OverlayModel.Confirm("confirm.remove:my", "删除", "确定删除？", dangerous = true))
        val calls = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val sink = object : PanelEventSink {
            override fun owns(overlayId: String) = true
            override suspend fun onEvent(overlayId: String, key: String, value: String?): ActionResult {
                calls.incrementAndGet(); release.await(); return ActionResult.Done
            }
        }
        val gate = PanelSendGate()
        val first = async(Dispatchers.Default) { CalmPanelLogic.dispatch(s, gate, sink, "confirm.remove:my", "confirm", null) }
        withTimeout(2_000) { while (calls.get() == 0) yield() }
        // 在途时第二次点击：不发
        assertNull(CalmPanelLogic.dispatch(s, gate, sink, "confirm.remove:my", "confirm", null))
        release.complete(Unit)
        first.await()
        assertTrue(s.items.value.isEmpty())
        // 处理完后（确认框已不在栈里）再点：也不发
        assertNull(CalmPanelLogic.dispatch(s, gate, sink, "confirm.remove:my", "confirm", null))
        assertEquals(1, calls.get())
        // 非 confirm 事件不受限
        assertTrue(gate.tryBegin(s, "oauth:x", "refresh"))
        assertTrue(gate.tryBegin(s, "oauth:x", "refresh"))
    }

    /** Nit 2：返回键按画面层级——面板画在会话列表与展开编辑器之上，先关；扩展对话框在最上时不越过它关下面看不见的层。 */
    @Test
    fun backFollowsVisualLayers() {
        val panel = OverlayModel.Text("help.hotkeys", "快捷键", "Esc 中止")
        assertEquals("面板压在展开编辑器上：先关面板", CalmBack.Step.OVERLAY, CalmBack.step(editorExpanded = true, top = panel, drawerOpen = false, searchOpen = false))
        assertEquals("面板压在会话列表上：先关面板", CalmBack.Step.OVERLAY, CalmBack.step(editorExpanded = false, top = panel, drawerOpen = true, searchOpen = false))
        assertEquals("编辑器画在会话列表之上", CalmBack.Step.EDITOR, CalmBack.step(editorExpanded = true, top = null, drawerOpen = true, searchOpen = true))
        assertEquals(CalmBack.Step.DRAWER, CalmBack.step(editorExpanded = false, top = null, drawerOpen = true, searchOpen = true))
        assertEquals("ExtDialog 在最上：不关下面的编辑器", CalmBack.Step.NONE, CalmBack.step(editorExpanded = true, top = dlg(), drawerOpen = true, searchOpen = false))
    }

    /** Nit 3：「＋」菜单文案与能力一致——只能选图片。 */
    @Test
    fun plusAttachLabelMatchesCapability() {
        assertEquals("图片", PLUS_ATTACH_LABEL)
        assertFalse(PLUS_ATTACH_LABEL.contains("文件"))
    }
}
