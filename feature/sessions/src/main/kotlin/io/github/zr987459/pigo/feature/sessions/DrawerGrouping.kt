package io.github.zr987459.pigo.feature.sessions

import io.github.zr987459.pigo.contract.SessionHit
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

    /** 接了 W4 SessionIndex 时的分节：先「置顶」，再按工作目录分组（组按最近更新倒序）。 */
    data class Section(val key: String, val label: String, val sessions: List<SessionSummary>)

    /**
     * 以仓库列表为准（删除 / 恢复立即生效），[pinned] 来自 SessionIndex 的元数据。
     * 置顶会话只出现在「置顶」节；其余按 cwd 分组，组内按更新时间倒序。
     */
    fun indexed(list: List<SessionSummary>, pinned: Set<String>): List<Section> {
        val sorted = SessionFormat.sorted(list)
        val (top, rest) = sorted.partition { it.guestPath in pinned }
        val out = ArrayList<Section>()
        if (top.isNotEmpty()) out += Section("pinned", "置顶", top)
        rest.groupBy { it.cwd }
            .entries
            .sortedByDescending { e -> e.value.maxOf { it.updatedAt } }
            .forEach { (cwd, ss) -> out += Section("cwd:$cwd", SessionFormat.cwd(ss.first()), ss) }
        return out
    }

    /** 搜索结果的一行：会话 + 命中片段（本地匹配时为 null）。 */
    data class Found(val session: SessionSummary, val snippet: String?)

    /**
     * 合并 SessionIndex 全文搜索结果与本地筛选：索引命中在前（换成仓库里的最新摘要，已删除的丢掉），
     * 再补上本地标题 / 预览 / 目录匹配但索引没返回的会话。[hits] 为 null（索引还没返回）时只用本地筛选。
     */
    fun mergeSearch(hits: List<SessionHit>?, list: List<SessionSummary>, query: String): List<Found> {
        if (query.isBlank()) return emptyList()
        val live = list.associateBy { it.guestPath }
        val seen = HashSet<String>()
        val out = ArrayList<Found>()
        hits.orEmpty().forEach { h ->
            val s = live[h.summary.guestPath] ?: return@forEach
            if (seen.add(s.guestPath)) out += Found(s, h.snippet?.takeIf { it.isNotBlank() })
        }
        SessionFormat.sorted(filter(list, query)).forEach { s -> if (seen.add(s.guestPath)) out += Found(s, null) }
        return out
    }
}
