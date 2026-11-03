package io.github.zr987459.pigo.feature.sessions

import io.github.zr987459.pigo.contract.SessionSummary
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 抽屉里会话列表的分组与搜索（纯函数，JVM 单测覆盖）。
 *
 * 分组规则照 dsh-Go `app/lib/state/drawer_view_state.dart` 的 `sessionDayOf`：
 * 今天 / 昨天 / 本周（周一起算）/ 更早，按更新时间判断。
 */
object DrawerGrouping {
    enum class Day(val label: String) { TODAY("今天"), YESTERDAY("昨天"), THIS_WEEK("本周"), EARLIER("更早") }

    data class Group(val day: Day, val sessions: List<SessionSummary>)

    fun dayOf(at: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Day {
        val today = now.atZone(zone).toLocalDate()
        val day = at.atZone(zone).toLocalDate()
        val diff = ChronoUnit.DAYS.between(day, today)
        if (diff <= 0) return Day.TODAY
        if (diff == 1L) return Day.YESTERDAY
        val monday = today.minusDays((today.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
        if (!day.isBefore(monday)) return Day.THIS_WEEK
        return Day.EARLIER
    }

    /** 按更新时间倒序后分组，空组不出现，组的顺序固定为 今天 → 更早。 */
    fun group(list: List<SessionSummary>, now: Instant, zone: ZoneId = ZoneId.systemDefault()): List<Group> {
        val byDay = SessionFormat.sorted(list).groupBy { dayOf(it.updatedAt, now, zone) }
        return Day.entries.mapNotNull { d -> byDay[d]?.let { Group(d, it) } }
    }

    /** 搜索：标题、预览、工作目录里包含关键字（忽略大小写和首尾空白）；空关键字返回原列表。 */
    fun filter(list: List<SessionSummary>, query: String): List<SessionSummary> {
        val q = query.trim()
        if (q.isEmpty()) return list
        return list.filter { s ->
            SessionFormat.title(s).contains(q, ignoreCase = true) ||
                s.preview.contains(q, ignoreCase = true) ||
                SessionFormat.cwd(s).contains(q, ignoreCase = true)
        }
    }
}
