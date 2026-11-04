package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.models.ModelPanels
import io.github.zr987459.pigo.contract.fake.FakeScriptedEngine
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.OverlayStack
import io.github.zr987459.pigo.contract.session.SelectGroup
import io.github.zr987459.pigo.contract.session.SelectItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * /model 选完自动关（PR #26 审查 Nit 2）：判据是「本次事件期间 set_model 成功」（SetModelSignal），不看提示文案；
 * 覆盖直接选中与「选未配置模型 → 填 Key → 自动选回」两条路径。
 */
class PickCloseTest {
    private fun picker() = OverlayModel.SelectList("model.select", "选择模型", listOf(SelectGroup(null, listOf(SelectItem("model:a/b", "b")))))
    private fun form() = OverlayModel.Text("provider.form:a", "a", "")

    @Test
    fun directPickUsesFailedFlagNotText() {
        val s = OverlayStack().apply { push(picker()) }
        val r = ActionResult.Toast("Switched to b") // 文案随便改：只看 failed
        assertEquals(r, PickClose.after(s, "model.select", "model:a/b", r, switched = false))
        assertEquals(emptyList<String>(), s.items.value.map { it.id })
        // 失败（failed = true）即使文案像成功也不关
        val s2 = OverlayStack().apply { push(picker()) }
        PickClose.after(s2, "model.select", "select", ActionResult.Toast("已切换到 b", failed = true), switched = false)
        assertEquals(listOf("model.select"), s2.items.value.map { it.id })
        // 星标 / 设为默认（成功提示）不关
        PickClose.after(s2, "model.select", "star", ActionResult.Toast("已加入常用"), switched = false)
        PickClose.after(s2, "model.select", "default:a/b", ActionResult.Toast("已设为默认"), switched = false)
        assertEquals(listOf("model.select"), s2.items.value.map { it.id })
    }

    @Test
    fun autoSelectBackAfterKeyClosesPickerAndForm() {
        val s = OverlayStack().apply { push(picker()); push(form()) }
        PickClose.after(s, "provider.form:a", "submit", ActionResult.Toast("已切换到 b"), switched = true)
        assertEquals(emptyList<String>(), s.items.value.map { it.id })
    }

    @Test
    fun noSwitchOrOverlayKeepsPanels() {
        val s = OverlayStack().apply { push(picker()) }
        PickClose.after(s, "model.select", "model:a/b", ActionResult.Overlay(form()), switched = false) // 未配置：去填 Key，不关
        assertEquals(listOf("model.select"), s.items.value.map { it.id })
        // 填 Key 保存成功但没自动选回（「已保存」failed = false、set_model 没发生）：不关
        s.push(form())
        PickClose.after(s, "provider.form:a", "submit", ActionResult.Toast("已保存"), switched = false)
        assertEquals(listOf("model.select", "provider.form:a"), s.items.value.map { it.id })
        s.remove("provider.form:a")
        assertEquals(listOf("model.select"), s.items.value.map { it.id })
        // 选择面板不在栈里（例如在设置页保存 Key）：什么都不关
        val s2 = OverlayStack().apply { push(form()) }
        PickClose.after(s2, "provider.form:a", "submit", ActionResult.Toast("已保存"), switched = true)
        assertEquals(listOf("provider.form:a"), s2.items.value.map { it.id })
    }

    @Test
    fun signalCountsOnlySuccessfulSetModelAndEventsSeeIt() = runBlocking {
        val engine = SetModelSignal(FakeScriptedEngine(CoroutineScope(Dispatchers.Unconfined)))
        assertEquals(0, engine.count)
        engine.setModel("a", "b")
        assertEquals(1, engine.count)

        // ModelPanelEvents 在事件前后比较计数：面板宿主里调了 set_model → switched = true
        var seen: Boolean? = null
        val panels = Proxy.newProxyInstance(ModelPanels::class.java.classLoader, arrayOf(ModelPanels::class.java)) { _, m, args ->
            if (m.name == "onPanelEvent") { runBlocking { engine.setModel("a", "c") }; ActionResult.Toast("ok") } else throw UnsupportedOperationException(m.name)
        } as ModelPanels
        val events = ModelPanelEvents(panels, switchCount = { engine.count }) { _, _, r, sw -> seen = sw; r }
        events.onEvent("provider.form:a", "submit", "{}")
        assertEquals(true, seen)
        assertEquals(2, engine.count)
    }
}
