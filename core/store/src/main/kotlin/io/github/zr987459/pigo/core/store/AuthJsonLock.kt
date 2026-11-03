package io.github.zr987459.pigo.core.store

import java.io.File
import java.io.IOException

/**
 * 与 pi 写 `auth.json` 时相同的锁约定（pi 用 proper-lockfile：在旁边建 `auth.json.lock` 目录）。
 * 宿主改 auth.json 前先拿这把锁，避免与容器里的 pi 同时写。
 *
 * pi 1.5.0 `dist/core/auth-storage.js` 的 `acquireLockAsync`：`stale: 30_000`（proper-lockfile 每 stale/2 = 15 秒
 * 刷新一次锁目录的 mtime），等别人的锁最多等 30 秒。pi 在锁内刷新 OAuth 令牌要联网，可能持锁十几秒，
 * 所以宿主只把 **mtime 超过 [STALE_MS]（30 秒 + 余量）** 的锁当残留，等锁的上限 [WAIT_MS] 也放宽到超过它，
 * 保证真正的残留锁能在等待期内被清掉，而仍在刷新的有效锁绝不会被抢。
 */
internal object AuthJsonLock {
    /** pi 的 stale 是 30 秒；多留 15 秒余量（宿主与容器同一时钟，只防 mtime 刷新的抖动）。 */
    const val STALE_MS = 45_000L

    /** 默认最多等这么久（大于 [STALE_MS]，残留锁一定能在等待期内过期被清）。只在 IO 线程调用。 */
    const val WAIT_MS = 50_000L

    fun <T> withLock(file: File, timeoutMs: Long = WAIT_MS, staleMs: Long = STALE_MS, block: () -> T): T {
        file.parentFile?.mkdirs()
        val lock = File(file.parentFile, file.name + ".lock")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!lock.mkdir()) {
            if (isStale(lock, staleMs)) {
                // 删除前再看一次 mtime：pi 可能刚刷新过（proper-lockfile 的 update）。
                if (isStale(lock, staleMs)) lock.delete()
                continue
            }
            if (System.currentTimeMillis() > deadline) throw IOException("auth.json 正被 pi 占用，请稍后再试")
            Thread.sleep(100)
        }
        try {
            return block()
        } finally {
            lock.delete()
        }
    }

    private fun isStale(lock: File, staleMs: Long): Boolean {
        val m = lock.lastModified()
        return m > 0 && System.currentTimeMillis() - m > staleMs
    }
}
