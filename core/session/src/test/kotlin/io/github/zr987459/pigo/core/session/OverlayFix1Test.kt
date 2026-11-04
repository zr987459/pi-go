package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.TreeNode
import io.github.zr987459.pigo.contract.actions.ActionResult
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
import io.github.zr987459.pigo.contract.settings.PiSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR #23 审查第 1 轮（I1、I2、N1–N4）的回归测试：真实 [SessionHostImpl] + [PiActionsImpl] + Fake 引擎。
 * UI 形态按 CalmSheet 的约定（确认框先 remove 再 runAction、表单不弹栈），终端形态按 PR #18 的约定（先弹栈 / 面板仍在栈里）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OverlayFix1Test {

    /** 与 RealModelPanels 同样的确认框协议：删除 / 清除 / 退出登录先弹 Confirm，确认后按 overlayId 前缀分派。 */
    class ConfirmingPanels : ModelPanels {
        val events = mutableListOf<Triple<String, String, String?>>()
        val removed = mutableListOf<String>()
        val loggedOut = mutableListOf<String>()
        /** 不为 null 时作为 `provider.form:*` 提交的结果（NitsCoreTest 用它验证结构化成败标志）。 */
        var formSubmit: ActionResult? = null
        override suspend fun modelPicker(tab: ModelPickerTab, query: String) = OverlayModel.SelectList("model.select", "选择模型", listOf(SelectGroup(null, listOf(SelectItem("model:p/m1", "m1")))))
        override suspend fun providerList(query: String) = OverlayModel.SelectList("auth.login", "服务商", listOf(SelectGroup(null, listOf(SelectItem("provider:x", "X")))))
        override suspend fun providerForm(providerId: String) = OverlayModel.Form("provider.form:$providerId", providerId,
            listOf(FormField("apiKey", "Key", PiSettingType.Str, secret = true), FormField("note", "备注", PiSettingType.Str)), actions = listOf(KeyCap("", "清除", "clear")))
        override suspend fun customProviderForm(providerId: String?) = OverlayModel.Form("provider.custom", "自定义",
            listOf(FormField("id", "名称", PiSettingType.Str), FormField("baseUrl", "地址", PiSettingType.Str), FormField("apiKey", "Key", PiSettingType.Str, secret = true)),
            actions = listOf(KeyCap("", "删除", "remove")))
        override suspend fun oauthFlow(providerId: String) = OverlayModel.Text("oauth:$providerId", "登录", "")
        override suspend fun logoutPicker() = OverlayModel.SelectList("auth.logout", "退出登录", listOf(SelectGroup(null, listOf(SelectItem("provider:x", "X")))))
        private fun confirm(id: String) = OverlayModel.Confirm(id, "确认", "确定？", "确定", dangerous = true)
        override suspend fun onPanelEvent(overlayId: String, key: String, value: String?): ActionResult {
            events += Triple(overlayId, key, value)
            return when {
                overlayId == "auth.login" && key == "provider:x" -> ActionResult.Overlay(providerForm("x"))
                overlayId == "auth.logout" && key.startsWith("provider:") -> ActionResult.Overlay(confirm("confirm.logout:" + key.removePrefix("provider:")))
                overlayId.startsWith("provider.form:") && key == "clear" -> ActionResult.Overlay(confirm("confirm.clear:" + overlayId.removePrefix("provider.form:")))
                overlayId == "provider.custom" && key == "remove" -> ActionResult.Overlay(confirm("confirm.remove:my"))
                overlayId == "provider.custom" && key == "submit" -> ActionResult.Toast("保存失败：地址不可用", failed = true)
                overlayId.startsWith("provider.form:") && key == "submit" && formSubmit != null -> formSubmit!!
                overlayId.startsWith("confirm.logout:") -> if (key == "confirm") { loggedOut += overlayId; ActionResult.Overlay(logoutPicker()) } else ActionResult.Done
                overlayId.startsWith("confirm.clear:") -> if (key == "confirm") { loggedOut += overlayId; ActionResult.Overlay(providerForm(overlayId.removePrefix("confirm.clear:"))) } else ActionResult.Done
                overlayId.startsWith("confirm.remove:") -> if (key == "confirm") { removed += overlayId; ActionResult.Done } else ActionResult.Done
                overlayId.startsWith("confirm.") -> ActionResult.Toast("未知面板操作：$key")
                else -> ActionResult.Toast("ok:$key")
            }
        }
    }

    private class Ctx(val host: SessionHostImpl, val engine: FakeScriptedEngine, val extras: FakeEngineExtras, val settings: PiSettings, val ops: RecordingOps, val panels: ConfirmingPanels, val port: TestChatPort) {
        suspend fun run(id: String, args: String? = null) = host.runAction(id, args)
        fun top() = host.overlay.top
        fun ids() = host.overlay.items.value.map { it.id }
        /** CalmSheet 的 ConfirmView「确定」：先 remove 再 runAction(<确认框 id>, "confirm")。 */
        suspend fun calmConfirm(): ActionResult { val id = top()!!.id; host.overlay.remove(id); return run(id, "confirm") }
    }

    private fun ctx(scope: CoroutineScope, settings: PiSettings = FakePiSettings(schema = PiSettingsSchemaV150), extras: FakeEngineExtras = FakeEngineExtras()): Ctx {
        val engine = FakeScriptedEngine(scope).also { it.start() }
        val ops = RecordingOps(engine)
        val panels = ConfirmingPanels()
        val port = TestChatPort(engine, scope)
        val host = SessionHostImpl(engine, extras, scope, settings, FakeSessionCatalog(engine), port,
            actionsFactory = { h -> PiActionsImpl(h, ops, panels) })
        return Ctx(host, engine, extras, settings, ops, panels, port)
    }

    private fun TestScope.ctxT(settings: PiSettings = FakePiSettings(schema = PiSettingsSchemaV150), extras: FakeEngineExtras = FakeEngineExtras()) =
        ctx(backgroundScope, settings, extras).also { testScheduler.runCurrent() }

    private fun dialog(id: String) = ExtUiRequest(id = id, method = "confirm", title = "扩展确认 $id")

    private fun notUnknown(r: ActionResult) =
        assertFalse("不得是未知动作 / 未知面板操作：$r", r is ActionResult.Toast && (r.text.startsWith("未知") || r.text.contains("没有可执行的操作")))

    // ================================================================ I1：ModelPanels 确认框，两形态一致

    @Test fun logoutConfirm_calm_reachesConfirmPanel() = runTest {
        val c = ctxT()
        c.run("auth.logout")
        c.run("auth.logout", "provider:x")
        assertEquals("confirm.logout:x", c.top()?.id)
        val r = c.calmConfirm()
        notUnknown(r)
        assertEquals(Triple("confirm.logout:x", "confirm", null), c.panels.events.last())
        assertEquals(listOf("confirm.logout:x"), c.panels.loggedOut)
        assertEquals("确认后回到退出登录列表，不叠层", listOf("auth.logout"), c.ids())
    }

    @Test fun logoutConfirm_terminal_sameEventAndConfirmRemoved() = runTest {
        val c = ctxT()
        c.run("auth.logout")
        c.run("auth.logout", "provider:x")
        notUnknown(c.run("confirm.logout:x", "confirm")) // 终端：确认框仍在栈里
        assertEquals(Triple("confirm.logout:x", "confirm", null), c.panels.events.last())
        assertEquals(listOf("auth.logout"), c.ids())
    }

    @Test fun clearConfirm_calm_returnsToProviderForm() = runTest {
        val c = ctxT()
        c.run("auth.login"); c.run("auth.login", "provider:x")
        notUnknown(c.run("clear")) // 表单键帽
        assertEquals("confirm.clear:x", c.top()?.id)
        notUnknown(c.calmConfirm())
        assertEquals(listOf("confirm.clear:x"), c.panels.loggedOut)
        assertEquals(listOf("auth.login", "provider.form:x"), c.ids())
    }

    @Test fun removeCustomConfirm_calm_closesDeletedForm_cancelKeepsIt() = runTest {
        val c = ctxT()
        c.run("provider.custom")
        c.run("remove")
        assertEquals("confirm.remove:my", c.top()?.id)
        // 终端形态取消：只关确认框，表单还在
        notUnknown(c.run("confirm.remove:my", "cancel"))
        assertEquals(listOf("provider.custom"), c.ids())
        assertTrue(c.panels.removed.isEmpty())
        // UI 形态确认：删除执行，已删服务商的表单一并关闭（不能再被「保存」重建）
        c.run("remove")
        notUnknown(c.calmConfirm())
        assertEquals(listOf("confirm.remove:my"), c.panels.removed)
        assertTrue(c.ids().isEmpty())
    }

    // ================================================================ I2：扩展对话框不被替换 / 回退误伤；栈操作原子

    @Test fun treeFilterUnderExtDialogKeepsDialogOnTopAndUnanswered() = runTest {
        val c = ctxT()
        c.run("session.tree")
        c.port.setDialogs(listOf(dialog("d1"))); testScheduler.runCurrent()
        assertEquals("ext:d1", c.top()?.id)
        c.run("tree.filter", "user-only") // 结果是同 id 的树
        assertEquals(listOf(OverlayPanels.TREE, "ext:d1"), c.ids())
        assertTrue("没有替用户应答（也没有 cancelled）", c.port.answers.isEmpty())
    }

    @Test fun returningToDeeperSameIdKeepsExtDialogAndEditorOffer() = runTest {
        val c = ctxT()
        c.run("session.tree")
        val node = (c.top() as OverlayModel.Tree).nodes.first().id
        c.run("tree.label", node) // 标签表单压在树上
        c.port.setDialogs(listOf(dialog("d1"))); testScheduler.runCurrent()
        c.host.draft.value = c.host.draft.value.withText("已有草稿")
        c.engine.pushUiRequest(ExtUiRequest(id = "s1", method = "set_editor_text", prefill = "扩展的文字")); testScheduler.runCurrent()
        assertEquals(listOf(OverlayPanels.TREE, OverlayPanels.LABEL + node, "ext:d1", SessionHostImpl.EDITOR_OFFER_ID), c.ids())
        c.run("tree.time") // 回到更深处的树：中间的普通面板（标签表单）移除，对话框与 offer 保留在上面
        assertEquals(listOf(OverlayPanels.TREE, "ext:d1", SessionHostImpl.EDITOR_OFFER_ID), c.ids())
        assertTrue(c.port.answers.isEmpty())
        assertEquals("已有草稿", c.host.draft.value.text)
    }

    @Test fun newPanelWhileDialogOpenGoesUnderDialog() = runTest {
        val c = ctxT()
        c.port.setDialogs(listOf(dialog("d1"))); testScheduler.runCurrent()
        c.run("model.select")
        assertEquals(listOf("model.select", "ext:d1"), c.ids())
        c.port.setDialogs(emptyList()); testScheduler.runCurrent()
        assertEquals(listOf("model.select"), c.ids())
    }

    /** 真并发：面板替换（多线程 runAction）与对话框同步交错，最终对话框集合与端口一致、无重复、在最上面，树只有一份。 */
    @Test fun concurrentShowAndDialogSyncStayConsistent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = ctx(scope)
            c.run("session.tree")
            // 一个线程连续替换面板（PiActionsImpl 本身按单线程调用设计），另一个线程同时同步扩展对话框。
            val jobs = listOf(
                scope.launch { repeat(300) { i -> c.run(if (i % 2 == 0) "tree.time" else "tree.fold"); yield() } },
            ) + scope.launch {
                repeat(300) { i -> c.port.setDialogs((0..(i % 3)).map { dialog("d$it") }); yield() }
                c.port.setDialogs(listOf(dialog("d0"), dialog("d2")))
            }
            jobs.joinAll()
            withTimeout(5_000) {
                while (c.host.overlay.items.value.filterIsInstance<OverlayModel.ExtDialog>().map { it.id }.toSet() != setOf("ext:d0", "ext:d2")) delay(10)
            }
            val ids = c.ids()
            assertEquals("没有重复：$ids", ids.size, ids.toSet().size)
            assertEquals(1, ids.count { it == OverlayPanels.TREE })
            assertEquals("对话框在最上面：$ids", setOf("ext:d0", "ext:d2"), ids.takeLast(2).toSet())
            assertTrue(c.port.answers.isEmpty())
        } finally { scope.cancel() }
    }

    // ================================================================ N1：摘要后切换防重入、按实例关闭

    @Test fun summarizeIsSentOnceAndClosesOnlyTheOriginalTree() = runTest {
        val c = ctxT()
        c.run("session.tree")
        val node = (c.top() as OverlayModel.Tree).nodes.first().id
        c.run(OverlayPanels.TREE, node)
        val gate = CompletableDeferred<Unit>().also { c.ops.navGate = it }
        val first = launch { c.run(OverlayPanels.SUMMARIZE + node, OverlayPanels.SUMMARIZE_YES) }
        testScheduler.runCurrent()
        assertFalse("请求发出时摘要选择框已移除", c.ids().any { it.startsWith(OverlayPanels.SUMMARIZE) })
        assertEquals(PiActionsImpl.NAV_BUSY, c.run(OverlayPanels.SUMMARIZE + node, OverlayPanels.SUMMARIZE_YES))
        assertEquals("只发一次 tree_nav", 1, c.engine.commands.value.count { it.type == "tree_nav" })
        // 等待期间用户关掉树又重新打开
        c.host.overlay.remove(OverlayPanels.TREE)
        c.run("session.tree")
        gate.complete(Unit); first.join()
        assertEquals("新打开的树不被关", listOf(OverlayPanels.TREE), c.ids())
    }

    // ================================================================ N2：提交失败保持表单与已填内容（两形态）

    @Test fun settingsInvalid_terminalPoppedForm_isRestoredWithValues() = runTest {
        val c = ctxT()
        c.run("settings")
        c.run(OverlayPanels.SETTINGS, "group:队列")
        val form = c.top() as OverlayModel.Form
        c.host.overlay.pop() // 终端：提交前先弹栈
        val r = c.run(form.id, "steeringMode=乱填")
        assertTrue(r is ActionResult.Toast && r.text.startsWith("保存失败"))
        val back = c.top() as OverlayModel.Form
        assertEquals(form.id, back.id)
        assertEquals("乱填", back.values["steeringMode"])
    }

    @Test fun emptyName_bothForms_keepForm() = runTest {
        val c = ctxT()
        c.run("session.name")
        c.run(OverlayPanels.NAME, "name=") // UI：表单在栈里
        assertEquals(listOf(OverlayPanels.NAME), c.ids())
        c.host.overlay.pop()
        c.run(OverlayPanels.NAME, "name=   ") // 终端：已弹栈
        assertEquals(listOf(OverlayPanels.NAME), c.ids())
    }

    @Test fun modelFormFailure_terminal_restoresValuesButNotSecret() = runTest {
        val c = ctxT()
        c.run("provider.custom")
        c.host.overlay.pop()
        val r = c.run("provider.custom", "id=my\nbaseUrl=https://x/v1\napiKey=sk-secret")
        assertTrue(r is ActionResult.Toast)
        val back = c.top() as OverlayModel.Form
        assertEquals("provider.custom", back.id)
        assertEquals("https://x/v1", back.values["baseUrl"])
        assertNull("密钥不回填进栈", back.values["apiKey"])
    }

    // ================================================================ N3：saveSettings 原子写

    /** 某个键写入失败的设置（其余委托给 Fake）。 */
    private class FailingSettings(val inner: FakePiSettings, val failKey: String) : PiSettings by inner {
        override suspend fun set(key: String, value: JsonElement?): Outcome<Unit> =
            if (key == failKey) Outcome.Err(io.github.zr987459.pigo.contract.ErrCode.UNKNOWN, "磁盘满") else inner.set(key, value)
    }

    @Test fun midWriteFailureRollsBackEarlierKeys() = runTest {
        val inner = FakePiSettings(schema = PiSettingsSchemaV150)
        val c = ctxT(settings = FailingSettings(inner, "followUpMode"))
        c.run("settings")
        val form = (c.run(OverlayPanels.SETTINGS, "group:队列") as ActionResult.Overlay).model as OverlayModel.Form
        val r = c.run(form.id, "steeringMode=all\nfollowUpMode=all")
        assertTrue("$r", r is ActionResult.Toast && r.text.startsWith("保存失败（未保存任何更改）"))
        assertNull("已写的 steeringMode 被撤回", inner.values.value["steeringMode"])
        assertEquals(form.id, c.top()?.id)
    }

    @Test fun sideKeyRejectedBeforeAnyWrite() = runTest {
        val inner = FakePiSettings(schema = PiSettingsSchemaV150)
        val c = ctxT(settings = inner)
        c.run("settings")
        val form = (c.run(OverlayPanels.SETTINGS, "group:网络") as ActionResult.Overlay).model as OverlayModel.Form
        val other = form.fields.first { !isSideKey(it.key) && it.type is PiSettingType.Str }.key
        val r = c.run(form.id, "$other=http://p:1\nenv.PI_OFFLINE=true")
        assertTrue("$r", r is ActionResult.Toast && r.text.startsWith("保存失败（未保存任何更改）"))
        assertTrue("一个键都没写：${inner.writes}", inner.writes.isEmpty())
    }

    // ================================================================ N4：消息长按的标签表单预填已有标签

    @Test fun labelFormFromMessagePrefillsExistingLabel() = runTest {
        val extras = FakeEngineExtras()
        fun relabel(n: TreeNode): TreeNode = n.copy(label = if (n.entry.id == "e3") "重要" else n.label, children = n.children.map(::relabel))
        extras.treeFixture = extras.treeFixture.copy(roots = extras.treeFixture.roots.map(::relabel))
        val c = ctxT(extras = extras)
        val r = c.run("tree.label", "e3") // 树没打开（从消息菜单进来）
        val form = (r as ActionResult.Overlay).model as OverlayModel.Form
        assertEquals("重要", form.values["label"])
    }
}
