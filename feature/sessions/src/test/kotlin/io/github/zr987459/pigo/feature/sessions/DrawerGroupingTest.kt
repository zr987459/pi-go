package io.github.zr987459.pigo.feature.sessions

import io.github.zr987459.pigo.contract.SessionSummary
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerGroupingTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-11-04 是星期三。 */
    private fun at(day: Int, hour: Int = 12): Instant = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, zone).toInstant()
    private val now = at(4, 14)

    private fun s(path: String, updated: Instant, name: String? = null, preview: String = "", cwd: String = "/root") =
        SessionSummary(path, "id", cwd, name, preview, updated, updated, 1)

    @Test fun dayBucketsFollowDshGo() {
        assertEquals(DrawerGrouping.Day.TODAY, DrawerGrouping.dayOf(at(4, 0), now, zone))
        assertEquals(DrawerGrouping.Day.TODAY, DrawerGrouping.dayOf(at(5, 9), now, zone)) // 时钟偏差：未来也算今天
        assertEquals(DrawerGrouping.Day.YESTERDAY, DrawerGrouping.dayOf(at(3, 23), now, zone))
        assertEquals(DrawerGrouping.Day.THIS_WEEK, DrawerGrouping.dayOf(at(2, 8), now, zone)) // 周一
        assertEquals(DrawerGrouping.Day.EARLIER, DrawerGrouping.dayOf(at(1, 23), now, zone)) // 上周日
    }

    @Test fun groupsAreOrderedAndSkipEmpty() {
        val list = listOf(s("/a", at(1)), s("/b", at(4, 10)), s("/c", at(4, 13)), s("/d", at(3)))
        val groups = DrawerGrouping.group(list, now, zone)
        assertEquals(listOf(DrawerGrouping.Day.TODAY, DrawerGrouping.Day.YESTERDAY, DrawerGrouping.Day.EARLIER), groups.map { it.day })
        assertEquals(listOf("/c", "/b"), groups[0].sessions.map { it.guestPath }) // 组内按更新时间倒序
    }

    @Test fun filterMatchesTitlePreviewAndCwd() {
        val list = listOf(
            s("/a", now, name = "修复登录"),
            s("/b", now, preview = "写一个 README"),
            s("/c", now, cwd = "/root/web-app"),
        )
        assertEquals(list, DrawerGrouping.filter(list, "  "))
        assertEquals(listOf("/a"), DrawerGrouping.filter(list, "登录").map { it.guestPath })
        assertEquals(listOf("/b"), DrawerGrouping.filter(list, "readme").map { it.guestPath })
        assertEquals(listOf("/c"), DrawerGrouping.filter(list, "web").map { it.guestPath })
    }
}
