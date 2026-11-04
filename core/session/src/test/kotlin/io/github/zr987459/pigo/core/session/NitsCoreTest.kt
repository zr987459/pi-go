package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.contract.fake.FakePiSettings
import io.github.zr987459.pigo.contract.fake.FakeScriptedEngine
import io.github.zr987459.pigo.contract.fake.FakeSessionCatalog
import io.github.zr987459.pigo.contract.session.OverlayModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r3/nits-core：PR #23 第 2 轮 Nit 1/2、PR #20 R3-N1 的回归测试（真实 [SessionHostImpl] + [PiActionsImpl]）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class NitsCoreTest {

    private class Ctx(val host: SessionHostImpl, val engine: FakeScriptedEngine, val ops: RecordingOps, val panels: OverlayFix1Test.ConfirmingPanels, val port: TestChatPort) {
        suspend fun run(id: String, args: String? = null) = host.runAction(id, args)
        fun ids() = host.overlay.items.value.map { it.id }
    }

    private fun TestScope.ctx(): Ctx {
        val engine = FakeScriptedEngine(backgroundScope).also { it.start() }
        val ops = RecordingOps(engine)
        val panels = OverlayFix1Test.ConfirmingPanels()
        val port = TestChatPort(engine, backgroundScope)
        val host = SessionHostImpl(engine, FakeEngineExtras(), backgroundScope, FakePiSettings(schema = PiSettingsSchemaV150), FakeSessionCatalog(engine), port,
            actionsFactory = { h -> PiActionsImpl(h, ops, panels) })
        testScheduler.runCurrent()
        return Ctx(host, engine, ops, panels, port)
    }

    // ---------------------------------------------------------------- PR #23 第 2 轮 Nit 1

    @Test fun modelFormSaveSuccess_terminalPoppedForm_isNotPushedBack() = runTest {
        val c = ctx()
        c.run("auth.login"); c.run("auth.login", "provider:x")
        assertEquals("provider.form:x", c.host.overlay.top?.id)
        c.host.overlay.pop() // 终端：提交前先弹栈
        val r = c.run("provider.form:x", "apiKey=sk-1\nnote=备注")
        assertTrue("保存成功：$r", r is ActionResult.Toast && !r.failed)
        assertEquals("保存成功后表单不再弹回（Key 已清空的表单会让人以为没保存）", listOf("auth.login"), c.ids())
        assertEquals(Triple("provider.form:x", "submit", null as String?), c.panels.events.last().let { Triple(it.first, it.second, null) })
    }

    // ---------------------------------------------------------------- PR #24 审查 Nit 1：结构化成败标志，不按文案判断

    @Test fun landModelPanel_successWhoseTextSaysFailure_isNotPushedBack() = runTest {
        val c = ctx()
        // 服务商名里带「失败」：旧的文案判断会误判为失败并把表单压回
        c.panels.formSubmit = ActionResult.Toast("已保存 失败测试")
        c.run("auth.login"); c.run("auth.login", "provider:x")
        c.host.overlay.pop() // 终端：提交前先弹栈
        val r = c.run("provider.form:x", "apiKey=sk-1\nnote=备注")
        assertTrue("$r", r is ActionResult.Toast && !r.failed)
        assertEquals(listOf("auth.login"), c.ids())
    }

    @Test fun landModelPanel_failedFlagWithNeutralText_pushesFormBackWithoutSecret() = runTest {
        val c = ctx()
        // 新增的失败文案没有任何「失败」字样：旧判断会漏标并丢掉已填内容；现在按 failed 标志压回
        c.panels.formSubmit = ActionResult.Toast("服务商暂时不可用", failed = true)
        c.run("auth.login"); c.run("auth.login", "provider:x")
        c.host.overlay.pop()
        c.run("provider.form:x", "apiKey=sk-1\nnote=备注")
        val back = c.host.overlay.top as OverlayModel.Form
        assertEquals("provider.form:x", back.id)
        assertEquals("备注", back.values["note"])
        assertFalse("密钥字段不回填", back.values.containsKey("apiKey"))
    }

    // ---------------------------------------------------------------- PR #23 第 2 轮 Nit 2

    @Test fun treeFilteredDuringSummarizeIsStillClosedAfterSwitch() = runTest {
        val c = ctx()
        c.run("session.tree")
        val node = (c.host.overlay.top as OverlayModel.Tree).nodes.first().id
        c.run(OverlayPanels.TREE, node)
        val gate = CompletableDeferred<Unit>().also { c.ops.navGate = it }
        val job = launch { c.run(OverlayPanels.SUMMARIZE + node, OverlayPanels.SUMMARIZE_YES) }
        testScheduler.runCurrent()
        // 等待摘要期间改了筛选与折叠：树被就地换成新实例
        c.run("tree.filter", "user-only"); c.run("tree.fold")
        assertEquals(listOf(OverlayPanels.TREE), c.ids())
        gate.complete(Unit); job.join()
        assertTrue("切换成功后当次的树照样关闭：${c.ids()}", c.ids().isEmpty())
    }

    @Test fun treePickWithoutPromptClosesTreeEvenIfReplacedMeanwhile() = runTest {
        val c = ctx()
        c.host.settings.set("branchSummary.skipPrompt", kotlinx.serialization.json.JsonPrimitive(true))
        c.run("session.tree")
        val node = (c.host.overlay.top as OverlayModel.Tree).nodes.first().id
        val gate = CompletableDeferred<Unit>().also { c.ops.navGate = it }
        val job = launch { c.run(OverlayPanels.TREE, node) }
        testScheduler.runCurrent()
        c.run("tree.time")
        gate.complete(Unit); job.join()
        assertTrue(c.ids().isEmpty())
    }

    // ---------------------------------------------------------------- PR #20 R3-N1

    @Test fun stopFailureIsReportedOnce() = runTest {
        val c = ctx()
        c.port.stopResult = Outcome.Err(ErrCode.UNKNOWN, "管道断了")
        val r = c.run("abort")
        testScheduler.runCurrent()
        assertFalse("动作结果不再重复提示：$r", r is ActionResult.Toast)
        assertEquals(listOf("停止失败：管道断了"), c.host.toasts.value)
    }
}
