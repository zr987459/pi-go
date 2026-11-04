package io.github.zr987459.pigo.feature.settings

import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.fake.FakePiSettings
import io.github.zr987459.pigo.contract.models.ModelPickerTab
import io.github.zr987459.pigo.contract.session.KeyCap
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SelectGroup
import io.github.zr987459.pigo.contract.session.SelectItem
import io.github.zr987459.pigo.contract.settings.PiSettingSpec
import io.github.zr987459.pigo.contract.settings.PiSettingType
import io.github.zr987459.pigo.contract.settings.PiSettingsSchema
import io.github.zr987459.pigo.feature.settings.SettingsPanelLogic.Click
import io.github.zr987459.pigo.feature.settings.SettingsPanelLogic.PickMode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PR #19 审查第 2 轮：设置页面板交互（默认 / 常用不切会话模型、键帽作用于高亮项、Toast 不关、OAuth、高级可改）。 */
class SettingsPanelLogicTest {
    private val picker = OverlayModel.SelectList(
        id = "model.select", title = "选择模型",
        groups = listOf(SelectGroup(null, listOf(SelectItem("model:a/x", "x"), SelectItem("model:b/y", "y")))),
        selectedKey = "model:a/x",
        keyCaps = listOf(KeyCap("Enter", "选择", "select"), KeyCap("Ctrl+S", "设为默认", "default"), KeyCap("Space", "常用", "star")),
    )

    @Test fun defaultModeWritesDefaultInsteadOfSwitchingModel() {
        assertEquals(Click.Event("default:b/y"), SettingsPanelLogic.rowClick(picker, "model:b/y", PickMode.DEFAULT))
    }

    @Test fun scopedModeTogglesStar() {
        assertEquals(Click.Event("star:b/y"), SettingsPanelLogic.rowClick(picker, "model:b/y", PickMode.SCOPED))
    }

    @Test fun withoutModeClickOnlyHighlightsAndKeyCapsUseHighlight() {
        assertEquals(Click.Highlight("model:b/y"), SettingsPanelLogic.rowClick(picker, "model:b/y", null))
        assertEquals("model:b/y", SettingsPanelLogic.keyCapValue(picker, "model:b/y"))
        assertEquals("model:a/x", SettingsPanelLogic.keyCapValue(picker, null))
    }

    @Test fun pickModeHidesSessionSwitchingCap() {
        assertEquals(listOf("default", "star"), SettingsPanelLogic.visibleKeyCaps(picker, PickMode.DEFAULT).map { it.actionId })
        assertEquals(3, SettingsPanelLogic.visibleKeyCaps(picker, null).size)
    }

    @Test fun otherListsSendRowKeyDirectly() {
        val list = OverlayModel.SelectList("auth.login", "服务商", listOf(SelectGroup(null, listOf(SelectItem("provider:deepseek", "DeepSeek")))))
        assertEquals(Click.Event("provider:deepseek"), SettingsPanelLogic.rowClick(list, "provider:deepseek", PickMode.DEFAULT))
    }

    @Test fun toastNeverClosesPanel() {
        assertFalse(SettingsPanelLogic.closesPanel(ActionResult.Toast("✓ 连接成功")))
        assertFalse(SettingsPanelLogic.closesPanel(ActionResult.Toast("保存失败")))
        assertTrue(SettingsPanelLogic.closesPanel(ActionResult.Done))
    }

    @Test fun dismissCancelsOAuthAndCustomDraft() {
        assertEquals("cancel", SettingsPanelLogic.dismissEvent(OverlayModel.Text("oauth:anthropic", "登录", "正在开始登录…")))
        assertNull(SettingsPanelLogic.dismissEvent(OverlayModel.Text("oauth:anthropic", "登录", "已登录：a@b")))
        assertEquals("cancel", SettingsPanelLogic.dismissEvent(OverlayModel.Form("provider.custom", "添加")))
        assertNull(SettingsPanelLogic.dismissEvent(OverlayModel.Form("provider.form:deepseek", "DeepSeek")))
    }

    @Test fun oauthPollingAndUrl() {
        val start = OverlayModel.Text("oauth:anthropic", "登录", "正在开始登录…")
        val url = OverlayModel.Text("oauth:anthropic", "登录", "在浏览器中打开：\nhttps://example.com/oauth/authorize?provider=anthropic\n在浏览器里完成授权")
        assertTrue(SettingsPanelLogic.needsOAuthPolling(start))
        assertTrue(SettingsPanelLogic.needsOAuthPolling(url))
        assertFalse(SettingsPanelLogic.needsOAuthPolling(OverlayModel.Text("oauth:anthropic", "登录", "登录失败：授权码无效")))
        assertEquals("https://example.com/oauth/authorize?provider=anthropic", SettingsPanelLogic.oauthUrl(url))
        assertNull(SettingsPanelLogic.oauthUrl(start))
    }

    /** 审查第 3 轮 Important：输入代码表单、选择列表、「正在验证…」都要持续刷新，关闭时取消登录。 */
    @Test fun oauthFormAndSelectKeepPolling() {
        val form = OverlayModel.Form("oauth:anthropic", "登录", submitLabel = "提交")
        val sel = OverlayModel.SelectList("oauth:anthropic", "选择")
        val verifying = OverlayModel.Text("oauth:anthropic", "登录", "正在验证…")
        assertTrue(SettingsPanelLogic.needsOAuthPolling(form))
        assertTrue(SettingsPanelLogic.needsOAuthPolling(sel))
        assertTrue(SettingsPanelLogic.needsOAuthPolling(verifying))
        assertFalse(SettingsPanelLogic.needsOAuthPolling(OverlayModel.Form("provider.form:deepseek", "DeepSeek")))
        assertEquals("cancel", SettingsPanelLogic.dismissEvent(form))
        assertEquals("cancel", SettingsPanelLogic.dismissEvent(sel))
        assertTrue(SettingsPanelLogic.guardsSubmit(form))
        assertFalse(SettingsPanelLogic.guardsSubmit(OverlayModel.Form("provider.form:deepseek", "DeepSeek")))
        assertTrue(SettingsPanelLogic.oauthRetryable(OverlayModel.Text("oauth:anthropic", "登录", "登录失败：授权码无效")))
        assertFalse(SettingsPanelLogic.oauthRetryable(verifying))
    }

    /** 审查第 3 轮 Nit 2：每模型思考档是多行输入，提示与解析一致（换行或逗号）。 */
    @Test fun modelThinkingLevelsIsMultiline() {
        val f = SettingsPanelLogic.advancedForm("modelThinkingLevels", emptyMap())!!.fields.single()
        assertTrue(SettingsPanelLogic.multiline(f.type))
        assertTrue(f.label.contains("每行一个") && f.label.contains("逗号"))
        val ok = SettingsPanelLogic.parseAdvanced("modelThinkingLevels", mapOf("entries" to "a/x=high\nb/y=low, c/z=off"))
        assertEquals(3, ((ok as io.github.zr987459.pigo.contract.Outcome.Ok).value as kotlinx.serialization.json.JsonObject).size)
    }

    @Test fun formActionsComeFromPanel() {
        val f = OverlayModel.Form("provider.form:anthropic", "Anthropic", actions = listOf(KeyCap("", "测试", "test"), KeyCap("", "账号登录", "oauth"), KeyCap("", "清除", "clear")))
        assertEquals(listOf("test" to "测试", "oauth" to "账号登录", "clear" to "清除"), SettingsPanelLogic.formActions(f))
    }

    private val schema = object : PiSettingsSchema {
        override val specs = listOf("thinkingBudgets", "modelThinkingLevels", "enabledModels").map { PiSettingSpec(it, "模型", it, PiSettingType.Str) }
    }

    @Test fun advancedRowsAreEditableInPlace() = runTest {
        val s = FakePiSettings(schema)
        val adv = SettingsModelBuilder.build(FakeModelPanelsForSettings(), s).section("advanced")!!.rows
        assertEquals(SettingsModel.Open.Advanced("thinkingBudgets"), adv[0].open)
        assertEquals(SettingsModel.Open.Advanced("modelThinkingLevels"), adv[1].open)
        // 没有 pi 设置时不指向不存在的入口
        assertEquals(SettingsModel.Open.None, SettingsModelBuilder.build(FakeModelPanelsForSettings(), null).section("advanced")!!.rows[0].open)
    }

    @Test fun thinkingBudgetsSubmitWritesPiSettings() = runTest {
        val s = FakePiSettings(schema)
        val form = SettingsPanelLogic.advancedForm("thinkingBudgets", s.values.value)!!
        assertEquals(SettingsPanelLogic.BUDGET_LEVELS, form.fields.map { it.key })
        val r = SettingsPanelLogic.submitAdvanced(s, form.id, mapOf("low" to "2048", "high" to "32000"))
        assertEquals(ActionResult.Done, r)
        val v = s.values.value["thinkingBudgets"] as JsonObject
        assertEquals(2048, v["low"]!!.jsonPrimitive.int)
        assertEquals(32000, v["high"]!!.jsonPrimitive.int)
        // 回显
        assertEquals("2048", SettingsPanelLogic.advancedForm("thinkingBudgets", s.values.value)!!.values["low"])
        // 非法输入不写
        assertTrue(SettingsPanelLogic.submitAdvanced(s, form.id, mapOf("low" to "abc")) is ActionResult.Toast)
        assertEquals(2048, (s.values.value["thinkingBudgets"] as JsonObject)["low"]!!.jsonPrimitive.int)
        // 全部留空 = 删除该键
        assertEquals(ActionResult.Done, SettingsPanelLogic.submitAdvanced(s, form.id, emptyMap()))
        assertNull(s.values.value["thinkingBudgets"])
    }

    @Test fun modelThinkingLevelsSubmitWritesPiSettings() = runTest {
        val s = FakePiSettings(schema)
        val id = SettingsPanelLogic.ADVANCED_PREFIX + "modelThinkingLevels"
        assertEquals(ActionResult.Done, SettingsPanelLogic.submitAdvanced(s, id, mapOf("entries" to "anthropic/claude-sonnet-4-5=high\ndeepseek/deepseek-chat=off")))
        val v = s.values.value["modelThinkingLevels"] as JsonObject
        assertEquals(JsonPrimitive("high"), v["anthropic/claude-sonnet-4-5"])
        assertEquals(JsonPrimitive("off"), v["deepseek/deepseek-chat"])
        assertTrue(SettingsPanelLogic.submitAdvanced(s, id, mapOf("entries" to "x=turbo")) is ActionResult.Toast)
        assertTrue(SettingsPanelLogic.parseAdvanced("modelThinkingLevels", mapOf("entries" to "")) == Outcome.Ok(null))
    }

    @Test fun builderOpensPickersInPickModes() = runTest {
        val m = SettingsModelBuilder.build(FakeModelPanelsForSettings(), FakePiSettings(schema))
        assertEquals(SettingsModel.Open.Picker(ModelPickerTab.ALL, PickMode.DEFAULT), m.section("default")!!.rows.single().open)
        assertEquals(SettingsModel.Open.Picker(ModelPickerTab.ALL, PickMode.SCOPED), m.section("scoped")!!.rows.single().open)
    }
}
