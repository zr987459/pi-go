package io.github.zr987459.pigo.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 测试替身 [ListLog] 的并发约束：引擎线程写日志的同时，测试在别的线程里遍历 `lines`（`any` / `none`）。
 * 原来是 synchronizedList，迭代器不加锁 → PiEngineTest.promptReachesSettledAndWakeLockOnlyDuringRun 偶发
 * ConcurrentModificationException（PR #26 run 41799848679）。这里确定性地复现那种交错。
 */
class ListLogConcurrencyTest {
    @Test
    fun iteratingLinesWhileAnotherThreadLogsNeverThrows() {
        val log = ListLog()
        val writes = 20_000
        val writer = Thread {
            repeat(writes) { log.i("T", "line $it") }
        }.apply { start() }
        while (writer.isAlive) {
            assertFalse(log.lines.any { it.message == "never" })
            log.tail(5)
        }
        writer.join()
        assertEquals(writes, log.lines.size)
    }
}
