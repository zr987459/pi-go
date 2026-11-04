package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.OverlayStack
import io.github.zr987459.pigo.contract.session.SelectGroup
import io.github.zr987459.pigo.contract.session.SelectItem
import io.github.zr987459.pigo.form.TermBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 交接残留：/model 选中并切换成功后关闭选择面板（两形态共用）；终端返回键关面板但不关扩展对话框。 */
class PickCloseTest {
    private fun picker() = OverlayModel.SelectList("model.select", "选择模型", listOf(SelectGroup(null, listOf(SelectItem("model:a/b", "b")))))

    @Test
    fun successfulPickClosesPicker() {
        val s = OverlayStack().apply { push(picker()) }
        val r = PickClose.after(s, "model.select", "model:a/b", ActionResult.Toast("已切换到 b"))
        assertEquals(ActionResult.Toast("已切换到 b"), r)
        assertEquals(emptyList<String>(), s.items.value.map { it.id })
        s.push(picker())
        PickClose.after(s, "model.select", "select", ActionResult.Toast("已切换到 b，下一轮生效"))
        assertEquals(emptyList<String>(), s.items.value.map { it.id })
    }

    @Test
    fun failuresKeyCapsAndOtherPanelsStayOpen() {
        val s = OverlayStack().apply { push(picker()) }
        PickClose.after(s, "model.select", "model:a/b", ActionResult.Toast("切换失败：x"))
        PickClose.after(s, "model.select", "star:a/b", ActionResult.Toast("已切换到 b"))
        PickClose.after(s, "model.select", "model:a/b", ActionResult.Overlay(OverlayModel.Text("provider.form:a", "a", "")))
        PickClose.after(s, "auth.logout", "model:a/b", ActionResult.Toast("已切换到 b"))
        assertEquals(listOf("model.select"), s.items.value.map { it.id })
    }

    @Test
    fun termBackSkipsPinnedExtDialog() {
        val ext = OverlayModel.ExtDialog("ext:1", "确认", io.github.zr987459.pigo.contract.ExtUiRequest("1", "confirm"), 0L)
        assertEquals("model.select", TermBack.topClosable(listOf(picker(), ext))?.id)
        assertNull(TermBack.topClosable(listOf(ext)))
        assertNull(TermBack.topClosable(emptyList()))
    }
}
