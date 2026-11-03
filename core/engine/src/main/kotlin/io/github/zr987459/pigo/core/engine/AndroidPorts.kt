package io.github.zr987459.pigo.core.engine

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.zr987459.pigo.contract.AppLog
import io.github.zr987459.pigo.contract.LogLevel
import io.github.zr987459.pigo.contract.LogLine
import io.github.zr987459.pigo.core.protocol.Redactor

/**
 * 部分唤醒锁（N1）。由 [WakeLockGuard] 驱动：只在 `agent_start`→`agent_settled` 之间持有。
 * 不计引用次数；acquire 带 [SAFETY_TIMEOUT_MS] 上限，万一状态机出错也不会无限期持锁。
 */
class AndroidWakeLock(context: Context) : WakeLockPort {
    private val lock: PowerManager.WakeLock =
        (context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pigo:engine-run")
            .apply { setReferenceCounted(false) }

    override val isHeld: Boolean get() = lock.isHeld

    override fun acquire() {
        if (!lock.isHeld) lock.acquire(SAFETY_TIMEOUT_MS)
    }

    override fun release() {
        if (lock.isHeld) lock.release()
    }

    companion object {
        /** 单轮生成的安全上限：3 小时。 */
        const val SAFETY_TIMEOUT_MS = 3L * 60 * 60 * 1000
    }
}

/**
 * 运行标记：放在 App 私有 SharedPreferences（只是一个会话路径，不含敏感信息）。
 * DESIGN.md §3.9 写的是 DataStore，但 `:core:engine` 没有 DataStore 依赖（见 PR「需要 scaffold 处理」）。
 * 用 `commit()` 同步写入：被系统杀之前必须已经落盘。
 */
class SharedPrefsRunMarker(context: Context) : RunMarkerStore {
    private val prefs = context.applicationContext.getSharedPreferences("pigo_engine", Context.MODE_PRIVATE)

    override fun get(): String? = if (prefs.contains(KEY)) prefs.getString(KEY, "") ?: "" else null

    @SuppressLint("ApplySharedPref")
    override fun set(sessionFile: String?) {
        prefs.edit().putString(KEY, sessionFile ?: "").commit()
    }

    @SuppressLint("ApplySharedPref")
    override fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    private companion object {
        const val KEY = "running_session"
    }
}

/**
 * 引擎「希望运行」期间保持前台服务 [EngineService]（由 PiEngine 在首次启动时打开、close / 放弃恢复时关闭，
 * 崩溃恢复中的多次 boot 不会反复启停）。经 [ForegroundGate] 保证服务进入前台之前不会被 stopService。
 */
class ServiceForegroundPort(context: Context, private val gate: ForegroundGate = EngineService.gate) : ForegroundPort {
    private val app = context.applicationContext

    override fun onEngineActive(active: Boolean) {
        val intent = Intent(app, EngineService::class.java)
        when (gate.request(active)) {
            ForegroundGate.Action.START -> try {
                ContextCompat.startForegroundService(app, intent)
            } catch (e: Exception) {
                // Android 12+ 后台启动前台服务受限：引擎照常运行，只是不在前台（见「需真机验证」）。
                gate.onStartFailed()
                Log.w("PiGoEngine", "前台服务启动失败：${e.message}")
            }
            ForegroundGate.Action.STOP -> try {
                app.stopService(intent)
            } catch (e: Exception) {
                Log.w("PiGoEngine", "前台服务停止失败：${e.message}")
            }
            // 服务还没 startForeground：它进入前台后会看到「不再需要」并 stopSelf。
            ForegroundGate.Action.NONE -> Unit
        }
    }
}

/** 设备总内存，用于选择 Node 堆上限（§3.2）。 */
fun deviceTotalMemBytes(context: Context): Long {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    return info.totalMem
}

/**
 * 真实 [AppLog]：内存环形缓冲（诊断页、复制诊断）+ logcat。写入前统一脱敏（`sk-…`、`Bearer …`、已知 Key）。
 * @param knownSecrets 当前已知的 Key 原文（由装配层提供），只用于替换，不记录。
 */
class RingAppLog(
    private val capacity: Int = 2_000,
    knownSecrets: () -> Collection<String> = { emptyList() },
    private val sink: (LogLevel, String, String) -> Unit = ::logcat,
    private val clock: () -> Long = System::currentTimeMillis,
) : AppLog {
    private val redactor = Redactor(knownSecrets)
    private val lines = ArrayDeque<LogLine>(capacity)

    override fun i(tag: String, msg: String) = add(LogLevel.INFO, tag, msg, null)

    override fun w(tag: String, msg: String, t: Throwable?) = add(LogLevel.WARN, tag, msg, t)

    @Synchronized
    override fun tail(n: Int): List<LogLine> = lines.toList().takeLast(n.coerceAtLeast(0))

    private fun add(level: LogLevel, tag: String, msg: String, t: Throwable?) {
        val text = redactor.redact(if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message}")
        synchronized(this) {
            if (lines.size == capacity) lines.removeFirst()
            lines.addLast(LogLine(clock(), level, tag, text))
        }
        try {
            sink(level, tag, text)
        } catch (_: RuntimeException) {
            // JVM 单测里 android.util.Log 是桩实现，忽略。
        }
    }

    private companion object {
        fun logcat(level: LogLevel, tag: String, msg: String) {
            if (level == LogLevel.WARN) Log.w(tag, msg) else Log.i(tag, msg)
        }
    }
}

/** 从 APK assets 读 `pigo-bridge.ts`（DESIGN-r3 §5.6）。 */
class AndroidBridgeSource(context: Context) : BridgeSource {
    private val assets = context.applicationContext.assets
    override fun open(): java.io.InputStream? = runCatching { assets.open(PigoBridge.ASSET_PATH) }.getOrNull()
}
