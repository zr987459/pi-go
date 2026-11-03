package io.github.zr987459.pigo.feature.sessions

import io.github.zr987459.pigo.contract.SessionSummary
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFormatTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-03T06:00:00Z") // 北京时间 14:00

    private fun s(name: String?, preview: String, cwd: String = "/root/pi-cwd", updated: Instant = now, path: String = "/p") =
        SessionSummary(path, "id", cwd, name, preview, now, updated, 10)

    @Test fun titlePrefersNameThenPreview() {
        assertEquals("修 bug", SessionFormat.title(s("修 bug", "登录页")))
        assertEquals("登录页", SessionFormat.title(s(null, "登录页")))
        assertEquals("登录页", SessionFormat.title(s("  ", "登录页")))
        assertEquals("新会话（还没有消息）", SessionFormat.title(s(null, "")))
        assertEquals("登录页", SessionFormat.subtitlePreview(s("修 bug", "登录页")))
        assertNull(SessionFormat.subtitlePreview(s(null, "登录页")))
    }

    @Test fun cwdShortensRoot() {
        assertEquals("~/pi-cwd", SessionFormat.cwd(s(null, "", "/root/pi-cwd")))
        assertEquals("~", SessionFormat.cwd(s(null, "", "/root")))
        assertEquals("/srv/app", SessionFormat.cwd(s(null, "", "/srv/app")))
        assertEquals("未知目录", SessionFormat.cwd(s(null, "", "")))
    }

    @Test fun updatedTimeInChinese() {
        assertEquals("刚刚", SessionFormat.updated(now.minusSeconds(20), now, zone))
        assertEquals("5 分钟前", SessionFormat.updated(now.minusSeconds(300), now, zone))
        assertEquals("今天 09:30", SessionFormat.updated(Instant.parse("2026-11-03T01:30:00Z"), now, zone))
        assertEquals("昨天 23:59", SessionFormat.updated(Instant.parse("2026-11-02T15:59:00Z"), now, zone))
        assertEquals("10月29日 08:00", SessionFormat.updated(Instant.parse("2026-10-29T00:00:00Z"), now, zone))
        assertEquals("2025年1月2日", SessionFormat.updated(Instant.parse("2025-01-02T00:00:00Z"), now, zone))
    }

    @Test fun sortedByUpdatedDesc() {
        val a = s("a", "", updated = now.minusSeconds(100), path = "/a")
        val b = s("b", "", updated = now, path = "/b")
        val c = s("c", "", updated = now.minusSeconds(5000), path = "/c")
        assertEquals(listOf("b", "a", "c"), SessionFormat.sorted(listOf(a, c, b)).map { it.name })
    }

    @Test fun sizeFormatting() {
        assertEquals("512 B", SessionFormat.size(512))
        assertEquals("2 KB", SessionFormat.size(2048))
    }

    // ---------------- 删除保护（§5.6 L4） ----------------

    private fun at(path: String, minutesAgo: Long) = s(null, "x", updated = now.minusSeconds(minutesAgo * 60), path = path)

    @Test fun unknownCurrentBlocksNewestAndRecentSessions() {
        val newest = at("/a", 120)
        val recent = at("/b", 3)
        val old = at("/c", 600)
        // 最近修改的排第一：/b（3 分钟前）
        val all = listOf(newest, recent, old)
        val g1 = SessionFormat.deleteGuard(recent, all, null, canStartNewSession = false, now = now)
        assertTrue(g1 is SessionFormat.DeleteGuard.Blocked)
        assertTrue((g1 as SessionFormat.DeleteGuard.Blocked).reason.contains("最近修改"))
        // 不是最新、也不在 10 分钟内 → 可删
        assertEquals(SessionFormat.DeleteGuard.Allowed, SessionFormat.deleteGuard(old, all, null, false, now))
        assertEquals(SessionFormat.DeleteGuard.Allowed, SessionFormat.deleteGuard(newest, all, null, false, now))
    }

    @Test fun unknownCurrentBlocksNewestEvenIfOld() {
        val a = at("/a", 600)
        val b = at("/b", 900)
        val g = SessionFormat.deleteGuard(a, listOf(a, b), null, canStartNewSession = true, now = now)
        assertTrue(g is SessionFormat.DeleteGuard.Blocked)
        assertEquals(SessionFormat.DeleteGuard.Allowed, SessionFormat.deleteGuard(b, listOf(a, b), null, true, now))
    }

    @Test fun unknownCurrentBlocksRecentNonNewest() {
        val a = at("/a", 1)
        val b = at("/b", 5)
        val g = SessionFormat.deleteGuard(b, listOf(a, b), null, canStartNewSession = true, now = now)
        assertTrue(g is SessionFormat.DeleteGuard.Blocked)
        assertTrue((g as SessionFormat.DeleteGuard.Blocked).reason.contains("10 分钟"))
    }

    @Test fun knownCurrentNeedsNewSessionOrIsBlocked() {
        val cur = at("/cur", 1)
        val other = at("/other", 2)
        val all = listOf(cur, other)
        assertEquals(SessionFormat.DeleteGuard.NewSessionFirst, SessionFormat.deleteGuard(cur, all, "/cur", true, now))
        assertTrue(SessionFormat.deleteGuard(cur, all, "/cur", false, now) is SessionFormat.DeleteGuard.Blocked)
        // 知道当前会话时，其他会话即使刚改过也能删
        assertEquals(SessionFormat.DeleteGuard.Allowed, SessionFormat.deleteGuard(other, all, "/cur", false, now))
    }
}
