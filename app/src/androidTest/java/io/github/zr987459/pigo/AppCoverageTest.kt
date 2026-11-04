package io.github.zr987459.pigo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.attach.ImageAttach
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.fake.FakeAppSettingsStore
import io.github.zr987459.pigo.contract.fake.FakeSessionHost
import io.github.zr987459.pigo.contract.session.DraftImage
import io.github.zr987459.pigo.contract.session.KeyCap
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SelectGroup
import io.github.zr987459.pigo.contract.session.SelectItem
import io.github.zr987459.pigo.contract.session.UiForm
import io.github.zr987459.pigo.feature.chat.ui.calm.CalmSurface
import io.github.zr987459.pigo.feature.chat.ui.calm.PanelEventSink
import io.github.zr987459.pigo.form.FormHost
import io.github.zr987459.pigo.feature.term.TermSurface
import io.github.zr987459.pigo.form.toggleForm
import io.github.zr987459.pigo.settings.ModelsPageExtras
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * r3/app 端到端仪器测试（DESIGN-r3 §7.1 第 3 条的 AppCoverageTest；PR 2 接线终端后在这里补全覆盖表）。
 * PR #21 审查第 1 轮覆盖：返回键顺序与退到后台、「＋ › 图片」进共用草稿、面板行长按 › 设为默认；
 * 第 2 轮：面板画在展开编辑器之上并先关、确认框连点只发一次、代理保存后「立即重启 pi」。
 */
@RunWith(AndroidJUnit4::class)
class AppCoverageTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun newHost() = FakeSessionHost(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), initialForm = UiForm.CALM)

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    /** Important 3：返回键依次关闭 面板 → 会话列表，都关着时退到后台（不结束 Activity、不打断生成）；终端形态同理。 */
    @Test
    fun backClosesPanelThenSessionListThenGoesToBackground() {
        val host = newHost()
        var background = 0
        rule.setContent {
            FormHost(
                host,
                calm = { CalmSurface(host, backHandler = { enabled, onBack -> BackHandler(enabled, onBack) }) },
                term = { TermSurface(host) },
                onBackground = { background++ },
            )
        }
        rule.onNodeWithTag("persist:session.resume").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("calm:drawer").assertIsDisplayed()
        rule.runOnUiThread { host.overlay.push(OverlayModel.Text("help.hotkeys", "快捷键", "Esc 中止")) }
        rule.waitForIdle()
        val startsBefore = host.fakeEngine.startCount

        pressBack()
        assertTrue("第一次返回关面板", host.overlay.items.value.isEmpty())
        rule.onNodeWithTag("calm:drawer").assertIsDisplayed()
        assertEquals(0, background)

        pressBack()
        rule.onNodeWithTag("calm:drawer").assertDoesNotExistCompat()
        assertEquals(0, background)

        pressBack()
        assertEquals("都关着：退到后台", 1, background)
        assertTrue("Activity 没有结束", !rule.activity.isFinishing)

        rule.runOnUiThread { host.form.value = UiForm.TERM }
        rule.waitForIdle()
        pressBack()
        assertEquals("终端形态同理", 2, background)
        assertEquals("不重启引擎", startsBefore, host.fakeEngine.startCount)
        assertEquals(0, host.fakeEngine.restartCount)
    }

    /** Important 1：点「＋ › 图片」（第 2 轮 Nit 3：文案与能力一致，只能选图片）调起装配层的图片选择器，选中的图片进 host.draft（两形态共用）。 */
    @Test
    fun plusAttachPutsPickedImageIntoSharedDraft() {
        val host = newHost()
        var opened = 0
        rule.setContent {
            CalmSurface(host, onAttachImage = {
                opened++
                host.draft.value = ImageAttach.add(host.draft.value, listOf(DraftImage("content://media/picker/0/1", "image/png")))
            })
        }
        rule.onNodeWithTag("calm:plus").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("图片").assertExists()
        rule.onNodeWithText("文件附件", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("plus:attach").performClick()
        rule.waitForIdle()
        assertEquals(1, opened)
        assertEquals(listOf("content://media/picker/0/1"), host.draft.value.images.map { it.uri })
        assertTrue(host.draft.value.hasSendableContent)
    }

    /** Nit 2：模型面板行长按 › 设为默认：只对这一行发键帽事件，不发点行事件（不切会话模型）。 */
    @Test
    fun rowLongPressSetsDefaultWithoutSwitchingModel() {
        val host = newHost()
        val events = mutableListOf<Triple<String, String, String?>>()
        val sink = object : PanelEventSink {
            override fun owns(overlayId: String) = overlayId == "model.select"
            override suspend fun onEvent(overlayId: String, key: String, value: String?): ActionResult {
                events += Triple(overlayId, key, value); return ActionResult.Toast("ok")
            }
        }
        rule.setContent { CalmSurface(host, panelEvents = sink) }
        rule.runOnUiThread {
            host.overlay.push(
                OverlayModel.SelectList(
                    "model.select", "模型",
                    listOf(SelectGroup(null, listOf(SelectItem("model:a/x", "x"), SelectItem("model:b/y", "y")))),
                    selectedKey = "model:a/x",
                    keyCaps = listOf(KeyCap("d", "设为默认", "default")),
                ),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithTag("item:model:b/y").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithTag("rowMenu:default").performClick()
        rule.waitForIdle()
        assertEquals(listOf(Triple("model.select", "default", "model:b/y")), events)
        assertEquals("model:a/x", (host.overlay.top as OverlayModel.SelectList).selectedKey)
    }

    /** 第 2 轮 Nit 2：展开编辑器上方弹出的面板画在最上（能点到它的关闭键），返回键先关它，再收起编辑器。 */
    @Test
    fun panelAboveExpandedEditorIsOnTopAndClosesFirst() {
        val host = newHost()
        rule.setContent { CalmSurface(host, backHandler = { enabled, onBack -> BackHandler(enabled, onBack) }) }
        rule.onNodeWithTag("calm:expand").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("calm:expandedEditor").assertIsDisplayed()
        // FakePiActions 对 editor.expand 也压一个占位面板（真实 PiActionsImpl 返回 Done）：先清掉，只留测试自己的面板。
        rule.runOnUiThread { host.overlay.clear(); host.overlay.push(OverlayModel.Text("help.hotkeys", "快捷键", "Esc 中止")) }
        rule.waitForIdle()
        pressBack()
        assertTrue("返回先关面板", host.overlay.items.value.isEmpty())
        rule.onNodeWithTag("calm:expandedEditor").assertIsDisplayed()
        pressBack()
        rule.onNodeWithTag("calm:expandedEditor").assertDoesNotExist()
        // 面板确实画在编辑器之上：点它的关闭键能关掉（被编辑器盖住时点不到）
        rule.onNodeWithTag("calm:expand").performClick()
        rule.waitForIdle()
        // FakePiActions 对 editor.expand 也压一个占位面板（真实 PiActionsImpl 返回 Done）：先清掉，只留测试自己的面板。
        rule.runOnUiThread { host.overlay.clear(); host.overlay.push(OverlayModel.Text("help.hotkeys", "快捷键", "Esc 中止")) }
        rule.waitForIdle()
        rule.onNodeWithTag("overlay:close").performClick()
        rule.waitForIdle()
        assertTrue("面板在最上层，关闭键可点", host.overlay.items.value.isEmpty())
    }

    /** 第 2 轮 Nit 1：面板确认框连点「确定」只发一次 confirm；处理完确认框关闭。 */
    @Test
    fun confirmDoubleTapSendsOnce() {
        val host = newHost()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val sink = object : PanelEventSink {
            override fun owns(overlayId: String) = overlayId.startsWith("confirm.") || overlayId.startsWith("provider.")
            override suspend fun onEvent(overlayId: String, key: String, value: String?): ActionResult {
                events += "$overlayId/$key"; release.await(); return ActionResult.Done
            }
        }
        rule.setContent { CalmSurface(host, panelEvents = sink) }
        rule.runOnUiThread {
            host.overlay.push(OverlayModel.Form("provider.custom:my", "自定义"))
            host.overlay.push(OverlayModel.Confirm("confirm.remove:my", "删除", "确定删除？", dangerous = true))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("confirm:ok").performClick()
        rule.onNodeWithTag("confirm:ok").performClick()
        rule.waitForIdle()
        release.complete(Unit)
        rule.waitForIdle()
        rule.waitUntil(3_000) { host.overlay.items.value.isEmpty() }
        assertEquals(listOf("confirm.remove:my/confirm"), events)
    }

    /** 第 2 轮 Nit 4：保存代理后给出可用的「立即重启 pi」入口（复用 engine.restart）。 */
    @Test
    fun proxySaveOffersRestartPi() {
        val settings = FakeAppSettingsStore()
        var restarts = 0
        // 当前生效的代理 = 引擎启动时注入的（装配层在每次启动时记下）；重启后变成已保存的。
        val applied = kotlinx.coroutines.flow.MutableStateFlow<io.github.zr987459.pigo.contract.NetworkSettings?>(settings.network.value)
        var shown by androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            if (shown) ModelsPageExtras(settings, onRepairRuntime = null, aboutText = "pi-go", effective = applied,
                onRestartEngine = { restarts++; applied.value = settings.network.value; "pi 已重启，代理设置已生效" })
        }
        rule.onNodeWithTag("settings:restartPi").assertDoesNotExist()
        rule.onNodeWithTag("settings:proxy").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("proxy:url").performTextInput("http://127.0.0.1:7890")
        rule.onNodeWithTag("proxy:save").performClick()
        rule.waitForIdle()
        assertEquals("http://127.0.0.1:7890", settings.network.value.proxyUrl)
        // 第 3 轮 Nit：离开设置页再回来（remember 状态丢失），生效值仍与已保存不一致 → 入口仍在
        shown = false
        rule.waitForIdle()
        shown = true
        rule.waitForIdle()
        rule.onNodeWithTag("settings:restartPi").assertExists()
        rule.onNodeWithTag("settings:restartPi").performClick()
        rule.waitForIdle()
        rule.waitUntil(3_000) { restarts == 1 }
        rule.onNodeWithText("pi 已重启，代理设置已生效").assertExists()
        rule.onNodeWithTag("settings:restartPi").assertDoesNotExist()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() {
    // 会话列表关闭有 180ms 退出动画：节点可能仍在树里但已不可见，二者都算关闭。
    val gone = runCatching { assertDoesNotExist() }.isSuccess || runCatching { assertIsDisplayed() }.isFailure
    assertTrue("会话列表已关闭", gone)
}
