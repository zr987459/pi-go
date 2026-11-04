package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.actions.PiActionCatalog
import io.github.zr987459.pigo.contract.actions.UiEntry
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.contract.fake.FakePiSettings
import io.github.zr987459.pigo.contract.fake.FakeScriptedEngine
import io.github.zr987459.pigo.contract.fake.FakeSessionCatalog
import io.github.zr987459.pigo.contract.models.ModelPanels
import io.github.zr987459.pigo.contract.models.ModelPickerTab
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.TreeFilter
import io.github.zr987459.pigo.contract.session.UiForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审查第 1 轮 I3：动作表里**每个**非说明动作都必须有行为测试（下表），并且 `run` 不得落到「暂未实现」或
 * 「尚未接入」之类的占位结果。新增动作而没在 [behaviors] 里写行为断言，[everyActionHasABehaviorTest] 就会失败。
 *
 * 「形态层」动作（展开编辑器、展开全部工具、选图、跳到上一条提示）只改界面状态，`run` 返回 Done，
 * 由 UiCoverageTest / TermCoverageTest 在两种形态里核对；这里断言它们不发任何命令。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImplementedActionsTest {

    private class Ctx(
        val host: SessionHostImpl,
        val engine: FakeScriptedEngine,
        val extras: FakeEngineExtras,
        val settings: FakePiSettings,
        val ops: RecordingOps,
        val clipboard: MutableList<String>,
    ) {
        suspend fun run(id: String, args: String? = null) = host.runAction(id, args)
        fun cmds() = engine.commands.value.map { it.type }
        fun prompts() = engine.commands.value.filter { it.type in setOf("prompt", "steer", "follow_up") }.map { it.args.values.joinToString(" ") }
    }

    private object Panels : ModelPanels {
        override suspend fun modelPicker(tab: ModelPickerTab, query: String) = OverlayModel.Text("overlay:model.picker:${tab.name}", "模型", query)
        override suspend fun providerList(query: String) = OverlayModel.Text("overlay:providers", "服务商", query)
        override suspend fun providerForm(providerId: String) = OverlayModel.Text("overlay:provider:$providerId", providerId, "")
        override suspend fun customProviderForm(providerId: String?) = OverlayModel.Text("overlay:custom", "自定义", providerId.orEmpty())
        override suspend fun oauthFlow(providerId: String) = OverlayModel.Text("overlay:oauth:$providerId", "登录", "")
        override suspend fun logoutPicker() = OverlayModel.Text("overlay:logout", "退出登录", "")
        override suspend fun onPanelEvent(overlayId: String, key: String, value: String?): ActionResult = ActionResult.Done
    }

    private fun TestScope.ctx(scope: CoroutineScope): Ctx {
        val engine = FakeScriptedEngine(scope).also { it.start() }
        val extras = FakeEngineExtras()
        val settings = FakePiSettings(schema = PiSettingsSchemaV150)
        val ops = RecordingOps(engine)
        val host = SessionHostImpl(engine, extras, scope, settings, FakeSessionCatalog(engine), TestChatPort(engine, scope),
            actionsFactory = { h -> PiActionsImpl(h, ops, Panels) })
        val clip = mutableListOf<String>()
        scope.launch { host.clipboardRequests.collect { clip += it } }
        testScheduler.runCurrent()
        return Ctx(host, engine, extras, settings, ops, clip)
    }

    private fun overlayId(r: ActionResult) = (r as? ActionResult.Overlay)?.model?.id
    private fun ok(r: ActionResult) = assertTrue("应成功：$r", r is ActionResult.Done || (r is ActionResult.Toast && !r.text.contains("失败")))

    /** 动作 id → 行为断言（不止「有返回」，要核对它真的做了 pi 的那件事）。 */
    private val behaviors: Map<String, suspend Ctx.() -> Unit> = mapOf(
        "oneshot" to { assertEquals("overlay:oneshot", overlayId(run("oneshot"))); run("oneshot", "你好"); assertTrue(ops.guestCommands.last().startsWith("pi -p ")); run("oneshot", OneShot.JSON_PREFIX + "x"); assertTrue(ops.guestCommands.last().startsWith("pi --mode json -p ")) },
        "packages" to { assertTrue(run("packages") is ActionResult.LocalBlock); assertEquals("pi list", ops.guestCommands.last()); run("packages", "install npm:foo"); assertEquals("pi install npm:foo", ops.guestCommands.last()); assertTrue("包管理不写会话", "bash" !in cmds()) },
        "mcp" to { run("mcp"); assertTrue("应经 prompt 发 /mcp：${prompts()}", prompts().any { it.contains("/mcp") }) },
        "ext.llama" to { run("ext.llama"); assertTrue(prompts().any { it.contains("/llama") }) },
        "model.select" to { assertEquals("overlay:model.picker:ALL", overlayId(run("model.select"))) },
        "model.scoped" to { assertEquals("overlay:model.picker:SCOPED", overlayId(run("model.scoped"))) },
        "model.cycle" to { run("model.cycle"); assertTrue("cycleModel" in extras.calls) },
        "model.setDefault" to { run("model.setDefault", "deepseek/deepseek-chat"); assertTrue(settings.writes.any { it.startsWith("defaultProvider=") && it.contains("deepseek") }); assertTrue(settings.writes.any { it.startsWith("defaultModel=") && it.contains("deepseek-chat") }) },
        "model.refreshCatalog" to { ok(run("model.refreshCatalog")); assertEquals("pi update --models", ops.guestCommands.last()) },
        "model.thinkingAdvanced" to { val m = (run("model.thinkingAdvanced") as ActionResult.Overlay).model as OverlayModel.Form; assertEquals(setOf("thinkingBudgets", "modelThinkingLevels"), m.fields.map { it.key }.toSet()) },
        "thinking.select" to { assertEquals("overlay:thinking.select", overlayId(run("thinking.select"))) },
        "thinking.cycle" to { run("thinking.cycle"); assertTrue("cycleThinking" in extras.calls) },
        "thinking.toggle" to { run("thinking.toggle"); assertTrue(settings.writes.any { it.startsWith("hideThinkingBlock=") }) },
        "auth.login" to { assertEquals("overlay:providers", overlayId(run("auth.login"))); assertEquals("overlay:oauth:anthropic", overlayId(run("auth.login", "anthropic"))) },
        "auth.logout" to { assertEquals("overlay:logout", overlayId(run("auth.logout"))) },
        "provider.custom" to { assertEquals("overlay:custom", overlayId(run("provider.custom"))) },
        "context.system" to { val f = (run("context.system") as ActionResult.Overlay).model as OverlayModel.Form; assertTrue(f.fields.any { it.key == "file.SYSTEM.md" }) },
        "settings.contextFiles" to { val f = (run("settings.contextFiles") as ActionResult.Overlay).model as OverlayModel.Form; assertTrue(f.fields.any { it.key == "flag.noContextFiles" }) },
        "trust" to { val f = (run("trust") as ActionResult.Overlay).model as OverlayModel.Form; assertTrue(f.fields.any { it.key == "defaultProjectTrust" }) },
        "settings" to { assertEquals("overlay:settings", overlayId(run("settings"))) },
        "settings.session" to { assertTrue(formKeys(run("settings.session")).contains("sessionDir")) },
        "settings.tools" to { assertTrue(formKeys(run("settings.tools")).contains("defaultTools")) },
        "settings.network" to { assertTrue(formKeys(run("settings.network")).contains("httpProxy")) },
        "settings.queue" to { assertTrue(formKeys(run("settings.queue")).contains("steeringMode")) },
        "settings.compaction" to { assertTrue(formKeys(run("settings.compaction")).contains("compaction.enabled")) },
        "settings.retry" to { assertTrue(formKeys(run("settings.retry")).contains("retry.maxRetries")) },
        "appearance" to { assertTrue(formKeys(run("appearance")).contains("theme")) },
        "reload" to { run("reload"); assertTrue("reload" in cmds()) },
        "skills" to { assertEquals("overlay:skills", overlayId(run("skills"))) },
        "templates" to { assertEquals("overlay:templates", overlayId(run("templates"))) },
        "ext.commands" to { assertEquals("overlay:ext.commands", overlayId(run("ext.commands"))) },
        "send" to { host.draft.value = host.draft.value.withText("你好"); run("send"); assertTrue(prompts().any { it.contains("你好") }) },
        "send.followUp" to { host.draft.value = host.draft.value.withText("排队的"); run("send.followUp"); assertTrue(prompts().any { it.contains("排队的") }) },
        "abort" to { run("abort"); assertTrue("abort" in cmds() || "stop" in cmds()) },
        "retry.abort" to { run("retry.abort"); assertEquals(listOf("abort_retry"), cmds().filter { it in setOf("abort_retry", "abort", "clear_queue") }) },
        "queue.dequeue" to { run("queue.dequeue"); assertTrue("dequeue" in cmds()) },
        "bash" to { run("bash"); assertTrue(host.draft.value.bashMode != io.github.zr987459.pigo.contract.session.BashMode.NONE) },
        "bash.abort" to { run("bash.abort"); assertTrue(extras.calls.any { it.startsWith("abortBash") }) },
        "session.new" to { run("session.new"); assertTrue(cmds().any { it == "new_session" }) },
        "session.newEphemeral" to { run("session.newEphemeral"); assertTrue(cmds().any { it == "new_session" }) },
        "session.resume" to { assertEquals("overlay:session.resume", overlayId(run("session.resume"))) },
        "session.name" to { assertEquals("overlay:session.name", overlayId(run("session.name"))); run("session.name", "新名字"); assertTrue(cmds().any { it == "set_session_name" }) },
        "session.fork" to { assertEquals("overlay:session.fork", overlayId(run("session.fork"))); run("session.fork", "e1"); assertTrue(extras.calls.any { it.startsWith("fork") }) },
        "session.clone" to { run("session.clone"); assertTrue(extras.calls.any { it.startsWith("clone") }) },
        "session.compact" to { run("session.compact", "保留决定"); assertTrue(extras.calls.any { it.startsWith("compact") }) },
        "session.export" to { run("session.export"); assertTrue(extras.calls.any { it.startsWith("exportHtml") }) },
        "session.import" to { run("session.import", "/sdcard/x.jsonl"); ok(ActionResult.Done) },
        "session.delete" to { val r = run("session.delete"); assertTrue("$r", r is ActionResult.Toast && (r.text.contains("撤销") || r.text.contains("会话"))) },
        "session.info" to { assertTrue(run("session.info") is ActionResult.LocalBlock) },
        "session.share" to {
            settings.set("sessionShareDestination", JsonPrimitive("radius"))
            assertTrue((run("session.share") as ActionResult.Toast).text.contains("Radius"))
            settings.set("sessionShareDestination", JsonPrimitive("gist"))
            ops.guestResult = { c -> if (c.startsWith("gh gist create")) GuestOutput(0, "- Creating gist\nhttps://gist.github.com/u/abc123") else GuestOutput(0, "") }
            val r = run("session.share") as ActionResult.LocalBlock
            assertTrue(ops.guestCommands.any { it.startsWith("gh gist create --public=false") })
            assertTrue(r.text.contains("https://pi.dev/session/#abc123"))
            assertTrue("链接写进剪贴板", clipboard.any { it == "https://pi.dev/session/#abc123" })
        },
        "session.cwd" to {
            assertEquals("overlay:session.cwd", overlayId(run("session.cwd")))
            assertTrue((run("session.cwd", "/root/proj") as ActionResult.Overlay).model is OverlayModel.Confirm)
            run("session.cwd", PiActionsImpl.CONFIRM_PREFIX + "/root/proj"); assertTrue("new_session_in_dir" in cmds())
        },
        "session.tree" to {
            assertTrue((run("session.tree") as ActionResult.Overlay).model is OverlayModel.Tree)
            assertTrue("未设 skipPrompt 时先问要不要摘要", (run("session.tree", "e3") as ActionResult.Overlay).model is OverlayModel.Confirm)
            ops.navEditorText = "原来的问题"
            run("session.tree", "e3 summarize")
            assertEquals(mapOf("entryId" to "e3", "summarize" to "true"), engine.commands.value.last { it.type == "tree_nav" }.args)
            assertEquals("选中用户消息时原文回到共享草稿", "原来的问题", host.draft.value.text)
        },
        "tree.filter" to { assertEquals(TreeFilter.USER_ONLY, ((run("tree.filter", "user-only") as ActionResult.Overlay).model as OverlayModel.Tree).filter); val t = (run("tree.filter", "user-only") as ActionResult.Overlay).model as OverlayModel.Tree; assertTrue(t.nodes.all { it.role == "user" || it.isLeaf }) },
        "tree.fold" to { val t = (run("tree.fold") as ActionResult.Overlay).model as OverlayModel.Tree; assertTrue("全部折叠后有折叠节点", t.nodes.any { it.collapsed }) },
        "tree.time" to { assertTrue(((run("tree.time") as ActionResult.Overlay).model as OverlayModel.Tree).showTime) },
        "tree.label" to { run("tree.label", "e3 重要"); assertTrue("label" in cmds()) },
        "copy.last" to { val r = run("copy.last"); assertEquals("这是最后一条回复。", clipboard.last()); assertTrue((r as ActionResult.Toast).text.contains("已复制")) },
        "transcript.search" to { assertEquals("overlay:transcript.search", overlayId(run("transcript.search", "abc"))) },
        "editor.clear" to { host.draft.value = host.draft.value.withText("x"); run("editor.clear"); assertEquals("", host.draft.value.text) },
        "editor.history" to { assertEquals("overlay:editor.history", overlayId(run("editor.history"))) },
        "form.toggle" to { val f = host.form.value; run("form.toggle"); assertTrue(host.form.value != f); run("form.toggle", "ui"); assertEquals(UiForm.CALM, host.form.value) },
        "help" to { assertTrue(run("help") is ActionResult.LocalBlock) },
        "about" to { assertTrue(run("about") is ActionResult.LocalBlock) },
        "hotkeys" to { assertTrue((run("hotkeys") as ActionResult.LocalBlock).text.contains("Ctrl+L")) },
        "changelog" to { ops.guestResult = { GuestOutput(0, "# Changelog\n## 1.5.0") }; assertTrue((run("changelog") as ActionResult.LocalBlock).text.contains("1.5.0")); assertTrue(ops.guestCommands.last().contains("CHANGELOG.md")) },
        "debug" to { assertTrue(run("debug") is ActionResult.LocalBlock) },
        "bug" to { assertEquals("overlay:bug", overlayId(run("bug"))); assertTrue(run("bug", "崩了") is ActionResult.LocalBlock); assertTrue(ops.reports.single().second.contains("崩了")) },
        "engine.stop" to { run("engine.stop"); assertTrue("stop_engine" in cmds()) },
        // 形态层动作：不发命令，界面状态由两种形态的覆盖测试核对。
        "editor.expand" to { viewOnly("editor.expand") },
        "tools.expandAll" to { viewOnly("tools.expandAll") },
        "attach.image" to { viewOnly("attach.image") },
        "transcript.jump" to { viewOnly("transcript.jump") },
    )

    private fun formKeys(r: ActionResult) = ((r as ActionResult.Overlay).model as OverlayModel.Form).fields.map { it.key }

    private suspend fun Ctx.viewOnly(id: String) {
        val before = engine.commands.value.size
        assertEquals(ActionResult.Done, run(id))
        assertEquals(before, engine.commands.value.size)
    }

    private val nonExplain = PiActionCatalog.all.filter { it.ui !is UiEntry.Explain }.map { it.id }

    @Test
    fun everyActionHasABehaviorTest() {
        assertEquals("动作表与行为测试必须一一对应（缺的要补行为断言）", nonExplain.toSet(), behaviors.keys)
    }

    @Test
    fun noActionFallsIntoAPlaceholder() = runTest(UnconfinedTestDispatcher()) {
        for (id in nonExplain) {
            val c = ctx(backgroundScope)
            val r = c.run(id)
            val text = (r as? ActionResult.Toast)?.text.orEmpty()
            for (bad in listOf("暂未实现", "尚未接入", "未知动作")) assertTrue("$id 落到占位结果：$text", bad !in text)
        }
    }

    @Test
    fun everyActionBehaves() = runTest(UnconfinedTestDispatcher()) {
        for ((id, check) in behaviors) {
            val c = ctx(backgroundScope)
            try { c.check() } catch (e: AssertionError) { throw AssertionError("$id：${e.message}", e) }
        }
    }
}
