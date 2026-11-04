package io.github.zr987459.pigo

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.attach.ImageAttach
import io.github.zr987459.pigo.contract.actions.ActionResult
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
import io.github.zr987459.pigo.form.TermPlaceholder
import io.github.zr987459.pigo.form.toggleForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * r3/app 端到端仪器测试（DESIGN-r3 §7.1 第 3 条的 AppCoverageTest；PR 2 接线终端后在这里补全覆盖表）。
 * 本轮（PR #21 审查第 1 轮）覆盖：返回键顺序与退到后台、「＋ › 图片」进共用草稿、面板行长按 › 设为默认。
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
                term = { TermPlaceholder(onBackToUi = { host.toggleForm() }) },
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

    /** Important 1：点「＋ › 图片 / 文件附件」调起装配层的图片选择器，选中的图片进 host.draft（两形态共用）。 */
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
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() {
    // 会话列表关闭有 180ms 退出动画：节点可能仍在树里但已不可见，二者都算关闭。
    val gone = runCatching { assertDoesNotExist() }.isSuccess || runCatching { assertIsDisplayed() }.isFailure
    assertTrue("会话列表已关闭", gone)
}
