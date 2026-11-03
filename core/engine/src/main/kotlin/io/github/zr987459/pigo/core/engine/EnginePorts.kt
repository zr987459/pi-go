package io.github.zr987459.pigo.core.engine

/*
 * 引擎依赖的平台能力，抽成接口：Android 实现在本模块（AndroidWakeLock 等），
 * JVM 单测与桌面集成测试用内存实现。PiEngine 本身不引用任何 android.* 类。
 */

/** 部分唤醒锁。实现必须幂等：重复 acquire 只持有一把，release 未持有时无操作。 */
interface WakeLockPort {
    val isHeld: Boolean
    fun acquire()
    fun release()
}

/**
 * 「本轮运行中」标记（DESIGN.md §3.9「App 被系统杀」）：`agent_start` 写入当前会话文件，`agent_settled` 清除。
 * 冷启动时仍在 → 上一轮被系统中断。
 */
interface RunMarkerStore {
    fun get(): String?
    fun set(sessionFile: String?)
    fun clear()
}

/** 前台服务的启停（EngineService）。引擎进程存在期间保持前台。 */
interface ForegroundPort {
    fun onEngineActive(active: Boolean)
}

class InMemoryWakeLock : WakeLockPort {
    @Volatile
    override var isHeld: Boolean = false
        private set

    /** 历史上 acquire 过多少次（测试用）。 */
    @Volatile
    var acquireCount: Int = 0
        private set

    @Synchronized
    override fun acquire() {
        if (!isHeld) acquireCount++
        isHeld = true
    }

    @Synchronized
    override fun release() {
        isHeld = false
    }
}

class InMemoryRunMarker(initial: String? = null) : RunMarkerStore {
    @Volatile
    private var value: String? = initial
    @Volatile
    private var present: Boolean = initial != null

    override fun get(): String? = if (present) value ?: "" else null
    override fun set(sessionFile: String?) {
        value = sessionFile
        present = true
    }

    override fun clear() {
        value = null
        present = false
    }
}

object NoForeground : ForegroundPort {
    override fun onEngineActive(active: Boolean) = Unit
}

/**
 * N1 的代码级保证（DESIGN.md §5.7）：唤醒锁只由这里根据「是否有一轮正在运行且进程活着」决定，
 * 所有状态变化（事件批、崩溃、停止、关闭）后都调用 [sync]；空闲时一定释放。
 */
class WakeLockGuard(private val port: WakeLockPort) {
    @Synchronized
    fun sync(running: Boolean, processAlive: Boolean) {
        val want = running && processAlive
        if (want && !port.isHeld) port.acquire()
        if (!want && port.isHeld) port.release()
    }

    @Synchronized
    fun releaseNow() {
        if (port.isHeld) port.release()
    }

    val isHeld: Boolean get() = port.isHeld
}
