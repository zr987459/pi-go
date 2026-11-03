package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.AppLog
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SessionRepository
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.LaunchEnvProvider
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.fake.FakeAppLog
import io.github.zr987459.pigo.contract.fake.FakeEngineClient
import io.github.zr987459.pigo.core.engine.AndroidWakeLock
import io.github.zr987459.pigo.core.engine.EngineConfig
import io.github.zr987459.pigo.core.engine.EngineService
import io.github.zr987459.pigo.core.engine.PiEngine
import io.github.zr987459.pigo.core.engine.RingAppLog
import io.github.zr987459.pigo.core.engine.ServiceForegroundPort
import io.github.zr987459.pigo.core.engine.SharedPrefsRunMarker
import io.github.zr987459.pigo.core.engine.deviceTotalMemBytes
import io.github.zr987459.pigo.core.store.ResumePolicy
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 引擎与日志（归 mvp/engine）：fake() 供界面开发，real() 为 :core:engine 的 [PiEngine]。 */
object EngineBindings {
    fun engine(
        mode: Mode,
        ctx: BindingContext,
        runtime: RuntimeManager,
        launchEnv: LaunchEnvProvider,
        log: AppLog,
    ): EngineClient = when (mode) {
        Mode.FAKE -> fake(ctx)
        Mode.REAL -> real(ctx, runtime, launchEnv, log)
    }

    fun log(mode: Mode, ctx: BindingContext): AppLog = when (mode) {
        Mode.FAKE -> FakeAppLog()
        Mode.REAL -> RingAppLog()
    }

    fun fake(ctx: BindingContext): EngineClient = FakeEngineClient(ctx.appScope)

    /**
     * 真实引擎。进程在首次使用（发送、打开会话等）或调用 [PiEngine.start] 时启动；
     * 引擎进程存在期间由 [EngineService] 保持前台，唤醒锁只在一轮运行期间持有（N1）。
     */
    fun real(ctx: BindingContext, runtime: RuntimeManager, launchEnv: LaunchEnvProvider, log: AppLog): EngineClient {
        val app = ctx.appContext
        val config = EngineConfig(nodeHeapMb = EngineConfig.heapMbFor(deviceTotalMemBytes(app)))
        val engine = PiEngine(
            runtime = runtime,
            launchEnv = launchEnv,
            log = log,
            scope = ctx.appScope,
            config = config,
            wakeLock = AndroidWakeLock(app),
            runMarker = SharedPrefsRunMarker(app),
            foreground = ServiceForegroundPort(app),
        )
        EngineService.boundEngine = engine
        return engine
    }

    /** 已经预热过的引擎（每个引擎只预热一次；Activity 重建不会取消或重复启动）。 */
    private val warmed: MutableSet<EngineClient> = Collections.newSetFromMap(WeakHashMap())

    /**
     * 运行环境就绪后预先拉起引擎，只做一次，跑在 [BindingContext.appScope] 里（不随页面重组/Activity 重建取消）。
     */
    fun warmUpOnce(engine: EngineClient, ctx: BindingContext, runtime: RuntimeManager, sessions: SessionRepository, log: AppLog) {
        synchronized(warmed) { if (!warmed.add(engine)) return }
        ctx.appScope.launch { warmUp(engine, ctx, runtime, sessions, log) }
    }

    /**
     * 预热引擎（get_state 握手），并在冷启动时回到上次会话（L5）：
     * - 上一轮被系统中断、会话文件还在：交给引擎恢复（带「上一轮被系统中断」提示）；
     * - 否则打开最近更新的会话（[ResumePolicy]），不再每次冷启动都新开一个空会话；
     * - 没有会话或打开失败：普通启动。
     * 真实引擎才需要；Fake 引擎无需启动。失败不抛出，聊天页会显示引擎状态与诊断。
     */
    suspend fun warmUp(engine: EngineClient, ctx: BindingContext, runtime: RuntimeManager, sessions: SessionRepository, log: AppLog) {
        val pi = engine as? PiEngine ?: return
        try {
            if (pi.currentSessionFile == null) {
                val willResume = withContext(Dispatchers.IO) {
                    val marker = runCatching { SharedPrefsRunMarker(ctx.appContext).get() }.getOrNull()
                    ResumePolicy.engineWillResume(marker) { runtime.hostFile(it).isFile }
                }
                val list = if (willResume) emptyList() else runCatching { sessions.observe().first() }.getOrDefault(emptyList())
                val target = ResumePolicy.coldStartTarget(willResume, list)
                if (target != null) {
                    when (val r = pi.openSession(target.guestPath, target.cwd)) {
                        is Outcome.Ok -> return
                        is Outcome.Err -> log.w(TAG, "冷启动回到上次会话失败，改为新开会话：${r.message}")
                    }
                }
            }
            pi.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(TAG, "预热引擎失败", e)
        }
    }

    private const val TAG = "warmUp"
}
