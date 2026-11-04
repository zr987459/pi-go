package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.actions.PiActionCatalog
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.contract.fake.FakePiSettings
import io.github.zr987459.pigo.contract.fake.FakeScriptedEngine
import io.github.zr987459.pigo.contract.fake.FakeSessionCatalog
import io.github.zr987459.pigo.contract.models.ModelPanels
import io.github.zr987459.pigo.contract.models.ModelPickerTab
import io.github.zr987459.pigo.contract.session.FormField
import io.github.zr987459.pigo.contract.session.KeyCap
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SelectGroup
import io.github.zr987459.pigo.contract.session.SelectItem
import io.github.zr987459.pigo.contract.settings.PiSettingType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * overlay:* 面板（SessionHost 自己产出）与 ModelPanels 面板选中后的**真实效果**（PR #18 审查附注）。
 * 真实 [SessionHostImpl] + [PiActionsImpl] + Fake 引擎；两种形态的回传约定都测：
 * UI 形态 CalmSheet（`k=v` 行、面板仍在栈里、树节点发 `session.tree`）与终端形态（JSON / 先弹栈、`overlay:session.tree`）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OverlayPanelsTest {

    /** 记录面板事件的 ModelPanels 替身；面板 id 与真实 RealModelPanels 一致（`model.select` 等与动作 id 相同）。 */
    class RecordingPanels : ModelPanels {
        val events = mutableListOf<Triple<String, String, String?>>()
        override suspend fun modelPicker(tab: ModelPickerTab, query: String) = OverlayModel.SelectList(
            "model.select", "选择模型", listOf(SelectGroup(null, listOf(SelectItem("model:p/m1", "m1", starred = false), SelectItem("model:p/m2", "m2")))),
            selectedKey = "model:p/m1", keyCaps = listOf(KeyCap("Enter", "选择", "select"), KeyCap("Ctrl+S", "设为默认", "default"), KeyCap("Space", "常用", "star")),
        )
        override suspend fun providerList(query: String) = OverlayModel.SelectList("auth.login", "服务商", listOf(SelectGroup(null, listOf(SelectItem("provider:x", "X")))))
        override suspend fun providerForm(providerId: String) = OverlayModel.Form("provider.form:$providerId", providerId, listOf(FormField("apiKey", "Key", PiSettingType.Str, secret = true)))
        override suspend fun customProviderForm(providerId: String?) = OverlayModel.Form("provider.custom", "自定义",
            listOf(FormField("id", "名称", PiSettingType.Str), FormField("baseUrl", "地址", PiSettingType.Str)),
            actions = listOf(KeyCap("", "测试", "test")))
        override suspend fun oauthFlow(providerId: String) = OverlayModel.Text("oauth:$providerId", "登录", "")
        override suspend fun logoutPicker() = OverlayModel.SelectList("auth.logout", "退出登录", listOf(SelectGroup(null, listOf(SelectItem("provider:x", "X")))))
        override suspend fun onPanelEvent(overlayId: String, key: String, value: String?): ActionResult {
            events += Triple(overlayId, key, value)
            return when {
                overlayId == "auth.login" && key == "provider:x" -> ActionResult.Overlay(providerForm("x"))
                overlayId == "model.select" && key.startsWith("model:") -> ActionResult.Done
                else -> ActionResult.Toast("ok:$key")
            }
        }
    }

    private class Ctx(val host: SessionHostImpl, val engine: FakeScriptedEngine, val extras: FakeEngineExtras, val settings: FakePiSettings, val ops: RecordingOps, val panels: RecordingPanels) {
        suspend fun run(id: String, args: String? = null) = host.runAction(id, args)
        fun top() = host.overlay.top
        fun ids() = host.overlay.items.value.map { it.id }
        fun last(type: String) = engine.commands.value.last { it.type == type }.args
    }

    private fun TestScope.ctx(scope: CoroutineScope): Ctx {
        val engine = FakeScriptedEngine(scope).also { it.start() }
        val extras = FakeEngineExtras()
        val settings = FakePiSettings(schema = PiSettingsSchemaV150)
        val ops = RecordingOps(engine)
        val panels = RecordingPanels()
        val host = SessionHostImpl(engine, extras, scope, settings, FakeSessionCatalog(engine), TestChatPort(engine, scope),
            actionsFactory = { h -> PiActionsImpl(h, ops, panels) })
        testScheduler.runCurrent()
        return Ctx(host, engine, extras, settings, ops, panels)
    }

    private fun notUnknown(r: ActionResult) {
        assertFalse("不得是「未知动作」：$r", r is ActionResult.Toast && (r.text.startsWith("未知动作") || r.text.contains("没有可执行的操作")))
    }

    private fun firstKey(m: OverlayModel?) = (m as OverlayModel.SelectList).groups.flatMap { it.items }.first().key

    // ---------------------------------------------------------------- 会话

    @Test fun resumeSwitchesToPickedSession() = runTest {
        val c = ctx(backgroundScope)
        c.run("session.resume")
        val path = firstKey(c.top())
        notUnknown(c.run(OverlayPanels.RESUME, path))
        assertEquals(path, c.last("switch_session")["sessionPath"])
        assertEquals(path, c.host.chat.value.sessionFile)
        assertFalse("切换后关面板", OverlayPanels.RESUME in c.ids())
    }

    @Test fun forkFromPickedMessagePutsTextInDraft() = runTest {
        val c = ctx(backgroundScope)
        c.run("session.fork")
        val entry = firstKey(c.top())
        notUnknown(c.run(OverlayPanels.FORK, entry))
        assertTrue(c.extras.calls.contains("fork:$entry"))
        assertTrue("pi 把原文放回编辑器", c.host.draft.value.text.isNotBlank())
        assertFalse(OverlayPanels.FORK in c.ids())
    }

    @Test fun treePickAsksSummaryThenNavigatesInPlace_terminalConvention() = runTest {
        val c = ctx(backgroundScope)
        c.run("session.tree")
        val tree = c.top() as OverlayModel.Tree
        val node = tree.nodes.first { it.role == "user" }.id
        val r = c.run(OverlayPanels.TREE, node)
        assertEquals(OverlayPanels.SUMMARIZE + node, (r as ActionResult.Overlay).model.id)
        c.ops.navEditorText = "原来的问题"
        notUnknown(c.run(OverlayPanels.SUMMARIZE + node, OverlayPanels.SUMMARIZE_NO))
        assertEquals(mapOf("entryId" to node, "summarize" to "false"), c.last("tree_nav"))
        assertEquals("原来的问题", c.host.draft.value.text)
        assertTrue("切换后树与摘要选择都关闭", c.ids().none { it == OverlayPanels.TREE || it.startsWith(OverlayPanels.SUMMARIZE) })
    }

    @Test fun treePickWithSummary_uiConvention() = runTest {
        val c = ctx(backgroundScope)
        c.run("session.tree")
        val node = (c.top() as OverlayModel.Tree).nodes.first().id
        c.run("session.tree", node) // CalmSheet 点节点
        notUnknown(c.run(OverlayPanels.SUMMARIZE + node, OverlayPanels.SUMMARIZE_YES))
        assertEquals(mapOf("entryId" to node, "summarize" to "true"), c.last("tree_nav"))
        assertTrue(c.ids().isEmpty())
    }

    @Test fun treePickSkipsPromptWhenConfigured() = runTest {
        val c = ctx(backgroundScope)
        c.settings.set("branchSummary.skipPrompt", JsonPrimitive(true))
        c.run("session.tree")
        val node = (c.top() as OverlayModel.Tree).nodes.first().id
        notUnknown(c.run(OverlayPanels.TREE, node))
        assertEquals(mapOf("entryId" to node, "summarize" to "false"), c.last("tree_nav"))
        assertFalse(OverlayPanels.TREE in c.ids())
    }

    @Test fun treeFilterReplacesInsteadOfStacking() = runTest {
        val c = ctx(backgroundScope)
        c.run("session.tree"); c.run("tree.filter", "user-only"); c.run("tree.time")
        assertEquals(listOf(OverlayPanels.TREE), c.ids())
    }

    @Test fun treeLongPressOpensLabelFormAndSaves() = runTest {
        val c = ctx(backgroundScope)
        c.run("session.tree")
        val node = (c.top() as OverlayModel.Tree).nodes.first().id
        val r = c.run("tree.label", node)
        assertEquals("长按只打开标签表单，不清掉标签", OverlayPanels.LABEL + node, (r as ActionResult.Overlay).model.id)
        assertTrue(c.engine.commands.value.none { it.type == "label" })
        notUnknown(c.run(OverlayPanels.LABEL + node, "label=重要"))
        assertEquals(node, c.last("label")["entryId"])
        assertEquals("标签保存后树仍在", listOf(OverlayPanels.TREE), c.ids())
    }

    @Test fun nameFormRenames() = runTest {
        val c = ctx(backgroundScope)
        c.run("session.name")
        notUnknown(c.run(OverlayPanels.NAME, "name=新的名字"))
        assertEquals("新的名字", c.last("set_session_name")["name"])
        assertFalse(OverlayPanels.NAME in c.ids())
    }

    @Test fun cwdFormThenConfirmCreatesSessionInDir() = runTest {
        val c = ctx(backgroundScope)
        c.run("session.cwd")
        val r = c.run(OverlayPanels.CWD, "dir=/root/proj")
        assertEquals(OverlayPanels.CWD_CONFIRM + "/root/proj", (r as ActionResult.Overlay).model.id)
        assertEquals("表单换成确认框", listOf(OverlayPanels.CWD_CONFIRM + "/root/proj"), c.ids())
        notUnknown(c.run(OverlayPanels.CWD_CONFIRM + "/root/proj", "confirm"))
        assertEquals("/root/proj", c.last("new_session_in_dir")["dir"])
        assertTrue(c.ids().isEmpty())
    }

    // ---------------------------------------------------------------- 模型、编辑器、资源、诊断

    @Test fun thinkingPickSetsLevel() = runTest {
        val c = ctx(backgroundScope)
        c.run("thinking.select")
        notUnknown(c.run(OverlayPanels.THINKING, "high"))
        assertEquals("high", c.last("set_thinking_level")["level"])
        assertEquals("high", c.host.chat.value.thinkingLevel)
    }

    @Test fun historyPickRestoresPrompt() = runTest {
        val c = ctx(backgroundScope)
        c.host.draft.value = c.host.draft.value.withText("第一条提示")
        c.host.sendDraft()
        c.run("editor.history")
        notUnknown(c.run(OverlayPanels.HISTORY, firstKey(c.top())))
        assertEquals("第一条提示", c.host.draft.value.text)
        assertFalse(OverlayPanels.HISTORY in c.ids())
    }

    @Test fun skillAndTemplatePickInsertsSlashCommand() = runTest {
        val c = ctx(backgroundScope)
        c.run("skills")
        val skill = firstKey(c.top())
        c.host.draft.value = c.host.draft.value.withText("帮我看看")
        notUnknown(c.run("overlay:skills", skill))
        assertEquals("/$skill 帮我看看", c.host.draft.value.text)
        c.run("templates")
        val tpl = firstKey(c.top())
        c.run("overlay:templates", tpl)
        assertEquals("换模板时替换前面的命令", "/$tpl 帮我看看", c.host.draft.value.text)
    }

    @Test fun searchPickJumps() = runTest {
        val c = ctx(backgroundScope)
        c.host.draft.value = c.host.draft.value.withText("找得到的句子")
        c.host.sendDraft()
        testScheduler.advanceUntilIdle()
        val key = c.host.chat.value.items.first().key
        c.run("transcript.search", "找得到")
        notUnknown(c.run(OverlayPanels.SEARCH, key))
        assertEquals(key, c.host.scrollAnchor.value?.itemKey)
    }

    @Test fun oneshotFormRunsPiP() = runTest {
        val c = ctx(backgroundScope)
        c.run("oneshot")
        notUnknown(c.run(OverlayPanels.ONESHOT, "prompt=你好\njson=false"))
        assertTrue(c.ops.guestCommands.last().startsWith("pi -p "))
        c.run("oneshot")
        c.run(OverlayPanels.ONESHOT, """{"prompt":"x","json":"true"}""")
        assertTrue(c.ops.guestCommands.last().startsWith("pi --mode json -p "))
    }

    @Test fun bugFormWritesReport() = runTest {
        val c = ctx(backgroundScope)
        c.run("bug")
        val r = c.run(OverlayPanels.BUG, "hint=点了没反应")
        assertTrue(r is ActionResult.LocalBlock)
        assertTrue(c.ops.reports.single().second.contains("点了没反应"))
    }

    // ---------------------------------------------------------------- /settings

    @Test fun settingsGroupThenSubmitWritesOnlyChangedKeys() = runTest {
        val c = ctx(backgroundScope)
        c.run("settings")
        val r = c.run(OverlayPanels.SETTINGS, "group:队列")
        val form = (r as ActionResult.Overlay).model as OverlayModel.Form
        assertEquals("分组表单压在列表上（关掉回到列表）", listOf(OverlayPanels.SETTINGS, form.id), c.ids())
        // CalmSheet 的格式：表单仍在栈里，k=v 行
        c.host.overlay.replaceTop(form.copy(values = form.values + ("steeringMode" to "all")))
        notUnknown(c.run(form.id, (form.values + ("steeringMode" to "all")).entries.joinToString("\n") { "${it.key}=${it.value}" }))
        assertEquals(JsonPrimitive("all"), c.settings.values.value["steeringMode"])
        assertEquals("没改的键不写", null, c.settings.values.value["followUpMode"])
        assertEquals(listOf(OverlayPanels.SETTINGS), c.ids())
    }

    @Test fun settingsMultilineValuesSurviveKvFormat() = runTest {
        val c = ctx(backgroundScope)
        c.run("settings")
        c.run(OverlayPanels.SETTINGS, "group:工具")
        val form = c.top() as OverlayModel.Form
        c.host.overlay.pop() // 终端形态：提交前先弹栈
        notUnknown(c.run(form.id, "defaultTools=read\nbash\ncodemode.inlineBudget=3000"))
        assertEquals(JsonArray(listOf(JsonPrimitive("read"), JsonPrimitive("bash"))), c.settings.values.value["defaultTools"])
    }

    @Test fun settingsInvalidValueKeepsForm() = runTest {
        val c = ctx(backgroundScope)
        c.run("settings")
        c.run(OverlayPanels.SETTINGS, "group:队列")
        val form = c.top() as OverlayModel.Form
        val r = c.run(form.id, "steeringMode=乱填")
        assertTrue(r is ActionResult.Toast && r.text.startsWith("保存失败"))
        assertEquals("失败时表单不关，已填的不丢", form.id, c.top()?.id)
    }

    // ---------------------------------------------------------------- ModelPanels 面板（两种形态的回传）

    @Test fun modelPickerItemAndKeyCapsGoToPanelEvents() = runTest {
        val c = ctx(backgroundScope)
        c.run("model.select")
        notUnknown(c.run("model.select", "model:p/m2")) // CalmSheet 点项
        assertEquals(Triple("model.select", "model:p/m2", null), c.panels.events.last())
        assertFalse("Done 关面板", "model.select" in c.ids())
        c.run("model.select")
        notUnknown(c.run("default")) // 键帽
        assertEquals(Triple("model.select", "default", "model:p/m1"), c.panels.events.last())
        c.run("model.select", "star:model:p/m2") // 星标
        assertEquals(Triple("model.select", "star", "model:p/m2"), c.panels.events.last())
        assertEquals("Toast 不关面板", "model.select", c.top()?.id)
    }

    @Test fun loginListOpensProviderFormAndCustomFormSubmits() = runTest {
        val c = ctx(backgroundScope)
        c.run("auth.login")
        c.run("auth.login", "provider:x")
        assertEquals("provider.form:x", c.top()?.id)
        c.run("provider.custom")
        notUnknown(c.run("test")) // Form.actions 键帽 → 表单值 JSON
        assertEquals("provider.custom" to "test", c.panels.events.last().let { it.first to it.second })
        c.run("provider.custom", "id=my\nbaseUrl=https://x/v1")
        val (id, key, value) = c.panels.events.last()
        assertEquals("provider.custom" to "submit", id to key)
        assertEquals(mapOf("id" to "my", "baseUrl" to "https://x/v1"), (Json.parseToJsonElement(value!!) as JsonObject).mapValues { (it.value as JsonPrimitive).content })
    }

    @Test fun modelSelectActionWithQueryStillOpensPicker() = runTest {
        val c = ctx(backgroundScope)
        val r = c.run("model.select", "son") // 没有面板时：动作本身（带搜索词）
        assertEquals("model.select", (r as ActionResult.Overlay).model.id)
        assertTrue(c.panels.events.isEmpty())
    }

    // ---------------------------------------------------------------- 覆盖：任何 overlay:* 面板选中后都不是「未知动作」

    /**
     * 跑动作表里每个动作；凡是产出 `overlay:*` 面板的，就按面板类型「选中第一项 / 原样提交表单 / 确认 / 选第一个树节点」，
     * 结果不得是「未知动作」或「没有可执行的操作」。新增面板而没在 [PiActionsImpl.onPanel] 里实现，这里就会失败。
     */
    @Test fun everyOverlayPanelAcceptsItsSelection() = runTest {
        val seen = mutableSetOf<String>()
        for (spec in PiActionCatalog.all) {
            val c = ctx(backgroundScope)
            c.host.draft.value = c.host.draft.value.withText("历史一条"); c.host.sendDraft(); testScheduler.advanceUntilIdle()
            val r = runCatching { c.run(spec.id) }.getOrNull() as? ActionResult.Overlay ?: continue
            var m: OverlayModel = r.model
            repeat(3) { // 多步面板（分组 → 表单、目录 → 确认、树 → 摘要选择）
                if (!m.id.startsWith(OverlayPanels.PREFIX)) return@repeat
                seen += m.id
                val value: String = when (val x = m) {
                    is OverlayModel.SelectList -> x.groups.flatMap { it.items }.firstOrNull()?.key ?: return@repeat
                    is OverlayModel.Form -> x.fields.joinToString("\n") { f -> "${f.key}=" + (x.values[f.key]?.ifEmpty { null } ?: "x") }
                    is OverlayModel.Confirm -> "confirm"
                    is OverlayModel.Tree -> x.nodes.firstOrNull()?.id ?: return@repeat
                    else -> return@repeat
                }
                val res = c.run(m.id, value)
                notUnknown(res)
                m = (res as? ActionResult.Overlay)?.model ?: return@repeat
            }
        }
        for (must in listOf(OverlayPanels.TREE, OverlayPanels.RESUME, OverlayPanels.FORK, OverlayPanels.THINKING, OverlayPanels.HISTORY,
            OverlayPanels.SETTINGS, OverlayPanels.NAME, OverlayPanels.CWD, OverlayPanels.ONESHOT, OverlayPanels.BUG, "overlay:skills", "overlay:templates", "overlay:ext.commands")) {
            assertTrue("覆盖里缺 $must（实际：$seen）", must in seen)
        }
        assertNotNull(seen)
    }
}
