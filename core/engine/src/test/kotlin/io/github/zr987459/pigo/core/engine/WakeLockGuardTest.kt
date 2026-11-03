package io.github.zr987459.pigo.core.engine

import io.github.zr987459.pigo.contract.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N1：唤醒锁只在「运行中且进程活着」时持有，任何空闲/崩溃/关闭状态都不持有。 */
class WakeLockGuardTest {
    @Test
    fun heldOnlyWhileRunningAndAlive() {
        val port = InMemoryWakeLock()
        val g = WakeLockGuard(port)
        g.sync(running = false, processAlive = true)
        assertFalse(port.isHeld)
        g.sync(running = true, processAlive = true)
        assertTrue(port.isHeld)
        g.sync(running = true, processAlive = true) // 幂等
        assertEquals(1, port.acquireCount)
        g.sync(running = false, processAlive = true)
        assertFalse(port.isHeld)
        g.sync(running = true, processAlive = false) // 进程已死：即使状态还没来得及变也不持有
        assertFalse(port.isHeld)
        g.sync(running = true, processAlive = true)
        g.releaseNow()
        assertFalse(g.isHeld)
    }

    @Test
    fun exhaustiveTransitionsNeverLeaveLockHeldWhenIdle() {
        val port = InMemoryWakeLock()
        val g = WakeLockGuard(port)
        val states = listOf(true to true, true to false, false to true, false to false)
        for (a in states) for (b in states) for (c in states) {
            for ((r, alive) in listOf(a, b, c)) {
                g.sync(r, alive)
                assertEquals(r && alive, port.isHeld)
            }
        }
    }

    @Test
    fun runMarkerAndConfig() {
        val m = InMemoryRunMarker()
        assertEquals(null, m.get())
        m.set(null)
        assertEquals("", m.get())
        m.set("/root/.pi/agent/sessions/a.jsonl")
        assertEquals("/root/.pi/agent/sessions/a.jsonl", m.get())
        m.clear()
        assertEquals(null, m.get())

        assertEquals(768, EngineConfig.heapMbFor(4L shl 30))
        assertEquals(1024, EngineConfig.heapMbFor(8L shl 30))
        assertEquals(1024, EngineConfig.heapMbFor(0))
        val spec = EngineConfig(nodeHeapMb = 768).spec("/root/.pi/agent/sessions/x.jsonl", null, mapOf("DEEPSEEK_API_KEY" to "sk-1"))
        assertEquals(listOf("pi", "--mode", "rpc", "--session", "/root/.pi/agent/sessions/x.jsonl"), spec.argv)
        assertEquals("/root/pi-cwd", spec.cwd)
        assertEquals("/root/.pi/agent", spec.env["PI_CODING_AGENT_DIR"])
        assertEquals("1", spec.env["PI_OFFLINE"])
        assertEquals("1", spec.env["PI_SKIP_VERSION_CHECK"])
        assertEquals("0", spec.env["PI_TELEMETRY"])
        assertEquals("--max-old-space-size=768", spec.env["NODE_OPTIONS"])
        assertEquals("sk-1", spec.env["DEEPSEEK_API_KEY"])
        assertEquals(listOf("pi", "--mode", "rpc"), EngineConfig().spec(null, "/root/w", emptyMap()).argv)
        assertEquals(20_000L, EngineConfig().timeoutFor("abort"))
        assertEquals(30_000L, EngineConfig().timeoutFor("get_messages"))
        assertEquals(15_000L, EngineConfig().timeoutFor("prompt"))
        NoForeground.onEngineActive(true)
    }

    @Test
    fun ringAppLogRedactsAndKeepsTail() {
        val sunk = mutableListOf<String>()
        val log = RingAppLog(capacity = 3, knownSecrets = { listOf("raw-key-123456") }, sink = { _, _, m -> sunk += m }, clock = { 7 })
        log.i("T", "key=raw-key-123456")
        log.w("T", "Bearer abcdefghijk", IllegalStateException("sk-abcdefgh12"))
        log.i("T", "3")
        log.i("T", "4")
        val tail = log.tail(10)
        assertEquals(3, tail.size)
        assertEquals(LogLevel.WARN, tail[0].level)
        assertFalse(tail.any { it.message.contains("abcdefghijk") || it.message.contains("sk-abcdefgh12") })
        assertFalse(sunk.any { it.contains("raw-key-123456") })
        assertEquals(7L, tail[0].timeMillis)
        assertEquals(listOf("4"), log.tail(1).map { it.message })
    }
}
