package io.github.zr987459.pigo.feature.chat.ui.calm

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import io.github.zr987459.pigo.contract.SessionSummary
import io.github.zr987459.pigo.contract.actions.PiActionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** r5/openminis：会话列表分节、菜单定位、聊天页菜单入口的纯逻辑。 */
class OpenMinisLayoutTest {
    private fun s(path: String, ms: Long) = SessionSummary(path, path, "/root", null, "p", Instant.ofEpochMilli(ms), Instant.ofEpochMilli(ms), 1)

    @Test
    fun sessionListGroupsByDayLikeOpenMinis() {
        val tz = java.util.TimeZone.getDefault()
        val day = 86_400_000L
        // 「现在」取某天中午（本地时区），避免跨午夜的边界抖动
        val noon = (Math.floorDiv(1_800_000_000_000L + tz.getOffset(1_800_000_000_000L), day) * day) + day / 2 - tz.getOffset(1_800_000_000_000L)
        val list = listOf(s("a", noon - 1_000), s("b", noon - day), s("c", noon - 3 * day), s("d", noon - 10 * day), s("e", noon - 90 * day))
        val g = groupByDate(list, byDate = true, now = noon)
        assertEquals(listOf("今天", "昨天", "7 天内", "30 天内", "更早"), g.map { it.first })
        assertEquals(listOf("a", "b", "c", "d", "e"), g.flatMap { it.second }.map { it.guestPath })
        // 按名称排序时不分节、原顺序
        assertEquals(listOf<String?>(null), groupByDate(list, byDate = false, now = noon).map { it.first })
    }

    @Test
    fun avatarColorIsStablePerTitle() {
        assertEquals(avatarColor("修复登录"), avatarColor("修复登录"))
    }

    @Test
    fun menuFlipsAboveWhenNoRoomBelowAndClampsToMargins() {
        val window = IntSize(1000, 2000)
        val popup = IntSize(400, 600)
        // 锚点靠下：翻到上方
        val above = MinisMenuPosition.compute(IntRect(100, 1800, 200, 1850), window, popup, alignEnd = false, offsetX = 0, offsetY = 0, marginX = 16, marginY = 16)
        assertEquals(IntOffset(100, 1200), above)
        // 右对齐且贴右边：右缘对齐锚点右缘，不出屏
        val end = MinisMenuPosition.compute(IntRect(900, 100, 990, 150), window, popup, alignEnd = true, offsetX = 0, offsetY = 0, marginX = 16, marginY = 16)
        assertEquals("右对齐后再按 16px 边距夹回屏内", 584, end.x)
        assertEquals(150, end.y)
    }

    @Test
    fun chatMenuReachesTerminalAndEveryEntryIsAnAction() {
        val ids = SESSION_MENU.map { it.first }
        assertTrue("⋯ 菜单里有「打开终端」", "form.toggle" in ids)
        assertTrue("⋯ 菜单里有「新对话」", "session.new" in ids)
        for (id in ids) assertTrue("菜单项 $id 必须是动作表里的动作", PiActionCatalog[id] != null)
    }

    @Test
    fun backFromChatGoesToSessionListNotBackground() {
        assertEquals(CalmBack.Step.LIST, CalmBack.step(editorExpanded = false, top = null, listOpen = false, searchOpen = false))
        assertEquals(CalmBack.Step.NONE, CalmBack.step(editorExpanded = false, top = null, listOpen = true, searchOpen = false))
    }
}
