package io.github.zr987459.pigo.core.store

import java.io.File
import java.io.IOException

/**
 * 与 pi 写 `auth.json` 时相同的锁约定（pi 用 proper-lockfile：在旁边建 `auth.json.lock` 目录，
 * 超过 10 秒未更新视为残留）。宿主改 auth.json 前先拿这把锁，避免与容器里的 pi 同时写。
 */
internal object AuthJsonLock {
    private const val STALE_MS = 10_000L

    fun <T> withLock(file: File, timeoutMs: Long = 5_000, block: () -> T): T {
        file.parentFile?.mkdirs()
        val lock = File(file.parentFile, file.name + ".lock")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!lock.mkdir()) {
            if (lock.exists() && System.currentTimeMillis() - lock.lastModified() > STALE_MS) {
                lock.delete()
                continue
            }
            if (System.currentTimeMillis() > deadline) throw IOException("auth.json 正被 pi 占用，请稍后再试")
            Thread.sleep(50)
        }
        try {
            return block()
        } finally {
            lock.delete()
        }
    }
}
