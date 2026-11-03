package io.github.zr987459.pigo.feature.sessions

import io.github.zr987459.pigo.contract.SessionSummary
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 会话列表的显示规则（纯函数，JVM 单测覆盖）。 */
object SessionFormat {
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val md = DateTimeFormatter.ofPattern("M月d日 HH:mm")
    private val ymd = DateTimeFormatter.ofPattern("yyyy年M月d日")

    /** 标题：会话名 > 首条消息预览 > 「新会话（还没有消息）」。 */
    fun title(s: SessionSummary): String =
        s.name?.takeIf { it.isNotBlank() } ?: s.preview.takeIf { it.isNotBlank() } ?: "新会话（还没有消息）"

    /** 有名字时，副标题再显示一行预览。 */
    fun subtitlePreview(s: SessionSummary): String? =
        if (!s.name.isNullOrBlank() && s.preview.isNotBlank()) s.preview else null

    /** 工作目录：`/root/xxx` 显示为 `~/xxx`，更好懂。 */
    fun cwd(s: SessionSummary): String = when {
        s.cwd.isBlank() -> "未知目录"
        s.cwd == "/root" -> "~"
        s.cwd.startsWith("/root/") -> "~/" + s.cwd.removePrefix("/root/")
        else -> s.cwd
    }

    /** 更新时间：刚刚 / N 分钟前 / 今天 14:05 / 昨天 09:12 / 10月29日 08:00 / 2025年1月2日。 */
    fun updated(at: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = Duration.between(at, now)
        if (!d.isNegative && d.toMinutes() < 1) return "刚刚"
        if (!d.isNegative && d.toMinutes() < 60) return "${d.toMinutes()} 分钟前"
        val t = at.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        return when (t.toLocalDate()) {
            today -> "今天 " + hm.format(t)
            today.minusDays(1) -> "昨天 " + hm.format(t)
            else -> if (t.year == today.year) md.format(t) else ymd.format(t)
        }
    }

    /** 按更新时间倒序（L1）。 */
    fun sorted(list: List<SessionSummary>): List<SessionSummary> =
        list.sortedWith(compareByDescending<SessionSummary> { it.updatedAt }.thenBy { it.guestPath })

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
