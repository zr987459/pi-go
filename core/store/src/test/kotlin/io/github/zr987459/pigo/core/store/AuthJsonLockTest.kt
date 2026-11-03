package io.github.zr987459.pigo.core.store

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 审查第 1 轮：宿主不能抢走 pi 仍在使用（30 秒内刷新过）的 auth.json 锁。 */
class AuthJsonLockTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun staleThresholdIsAbovePiThirtySeconds() {
        assertTrue(AuthJsonLock.STALE_MS > 30_000)
        assertTrue(AuthJsonLock.WAIT_MS > AuthJsonLock.STALE_MS)
    }

    @Test
    fun liveLockHeldByPiForFifteenSecondsIsNotStolen() {
        val auth = File(tmp.root, "auth.json").apply { writeText("{}") }
        val lock = File(tmp.root, "auth.json.lock").apply { mkdir() }
        // pi 持锁 15 秒（proper-lockfile 刚好要刷新 mtime 的时刻），旧实现（10 秒）会删掉它。
        lock.setLastModified(System.currentTimeMillis() - 15_000)
        try {
            AuthJsonLock.withLock(auth, timeoutMs = 300) { fail("不应拿到 pi 仍持有的锁") }
            fail("应超时")
        } catch (_: IOException) {
        }
        assertTrue("有效锁不能被删", lock.isDirectory)
    }

    @Test
    fun lockOlderThanStaleIsReclaimedAndReleased() {
        val auth = File(tmp.root, "auth.json").apply { writeText("{}") }
        val lock = File(tmp.root, "auth.json.lock").apply { mkdir() }
        lock.setLastModified(System.currentTimeMillis() - AuthJsonLock.STALE_MS - 5_000)
        val r = AuthJsonLock.withLock(auth, timeoutMs = 1_000) { 42 }
        assertEquals(42, r)
        assertFalse(lock.exists())
    }

    @Test
    fun waitsForReleaseInsteadOfFailing() {
        val auth = File(tmp.root, "auth.json").apply { writeText("{}") }
        val lock = File(tmp.root, "auth.json.lock").apply { mkdir() }
        val t = Thread { Thread.sleep(400); lock.delete() }.apply { start() }
        val r = AuthJsonLock.withLock(auth, timeoutMs = 5_000) { "ok" }
        t.join()
        assertEquals("ok", r)
    }
}
