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

    /** 删除前的判断结果（§5.6 L4）。 */
    sealed interface DeleteGuard {
        /** 可以直接移入回收站。 */
        data object Allowed : DeleteGuard
        /** 是当前会话：先让引擎 `new_session`，成功后再移入回收站。 */
        data object NewSessionFirst : DeleteGuard
        /** 不允许删除，[reason] 显示在确认框里。 */
        data class Blocked(val reason: String) : DeleteGuard
    }

    /** 拿不到当前会话路径时，这个时间窗内改过的会话都当作「可能正在使用」。 */
    val RECENT_WINDOW: Duration = Duration.ofMinutes(10)

    /**
     * 能不能删除 [target]。pi 1.5.0 按路径追加写会话文件，正在使用的会话被移走后会在原路径生成
     * 没有 header 的碎片文件，所以：
     * - 知道当前会话（[currentGuestPath] 非 null）：删当前会话需要 [canStartNewSession]（先 `new_session`），否则禁止；其他会话可删。
     * - 不知道当前会话（导航层没传，例如引擎未接入）：保守处理，**最近修改的那个会话**以及
     *   [RECENT_WINDOW] 内改过的会话都可能是 pi 正在写的，禁止删除。
     */
    fun deleteGuard(
        target: SessionSummary,
        all: List<SessionSummary>,
        currentGuestPath: String?,
        canStartNewSession: Boolean,
        now: Instant,
        recentWindow: Duration = RECENT_WINDOW,
    ): DeleteGuard {
        if (currentGuestPath != null) {
            if (target.guestPath != currentGuestPath) return DeleteGuard.Allowed
            return if (canStartNewSession) {
                DeleteGuard.NewSessionFirst
            } else {
                DeleteGuard.Blocked("这是正在使用的会话，需要先新建一个会话才能删除。请先到「聊天」页新建会话。")
            }
        }
        val newest = all.maxWithOrNull(compareBy<SessionSummary> { it.updatedAt }.thenByDescending { it.guestPath })
        val isNewest = newest?.guestPath == target.guestPath
        val age = Duration.between(target.updatedAt, now)
        val isRecent = age < recentWindow // 时间在未来（时钟偏差）也算最近
        if (!isNewest && !isRecent) return DeleteGuard.Allowed
        val why = if (isNewest) "它是最近修改的会话" else "它在 ${recentWindow.toMinutes()} 分钟内有改动"
        return DeleteGuard.Blocked(
            "暂时不能删除：无法确认 pi 是否正在使用这个会话（$why）。删除正在写入的会话会把它弄坏。" +
                "请先到「聊天」页新建一个会话并发一条消息，再回来删除；其他较早的会话可以直接删。",
        )
    }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
