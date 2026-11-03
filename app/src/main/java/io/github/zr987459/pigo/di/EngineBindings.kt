package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.AppLog
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SessionRepository
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.core.engine.ActivityVisibility
import io.github.zr987459.pigo.core.engine.AndroidTaskNotifications
import io.github.zr987459.pigo.core.engine.TaskNotifier
import android.app.Application
import io.github.zr987459.pigo.contract.LaunchEnvProvider
import io.github.zr987459.pigo.contract.Phase
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
     * 返回套了 [WarmUpGate] 的 [GatedEngineClient]：冷启动预热结束前，用户操作不会抢先启动引擎（见 [warmUp]）。
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
        // N1：App 在后台时，一轮完成 / 出错 / 需要回答发一条 pigo-tasks 通知（停止不发）。
        (app as? Application)?.let { application ->
            runCatching { TaskNotifier(ActivityVisibility(application), AndroidTaskNotifications(application)).start(engine.runOutcomes, ctx.appScope) }
                .onFailure { log.w(TAG, "任务通知初始化失败", it) }
        }
        val ready = runtime.status.map { it.phase == Phase.READY }
            .stateIn(ctx.appScope, SharingStarted.Eagerly, runtime.status.value.phase == Phase.READY)
        val gate = WarmUpGate(bypass = { runtime.status.value.phase != Phase.READY })
        return GatedEngineClient(engine, gate, ready, ctx.appScope).also { gates[it] = gate }
    }

    /**
     * [EngineExtras]（W0 契约，W1 实现）：真实引擎即 [PiEngine] 本身（[GatedEngineClient] 包装前的那个），Fake 模式给 [FakeEngineExtras]。
     * 由 W5 在 `AppGraph` 装配；这里不改 `AppGraph.kt`。
     */
    fun extras(engine: EngineClient): EngineExtras =
        ((engine as? GatedEngineClient)?.inner ?: engine) as? EngineExtras ?: FakeEngineExtras()

    /** 每个包装后的引擎对应的预热闸门。 */
    private val gates: MutableMap<EngineClient, WarmUpGate> = Collections.synchronizedMap(WeakHashMap())

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
     * - 上一轮被中断但当时还没有会话文件（运行标记为空串）：普通启动新会话，提示落在新会话上，不去打开旧会话；
     * - 否则打开最近更新的会话（[ResumePolicy]），不再每次冷启动都新开一个空会话；
     * - 没有会话或打开失败：普通启动。
     *
     * 竞态（审查第 2 轮 Important）：整个预热期间 [WarmUpGate] 挡住用户的发送 / 新建 / 打开会话等命令，结束时（含失败、
     * 取消）一定放行；`openSession` 前再用 [ResumePolicy.mayAutoOpen] 确认引擎仍未启动、没有当前会话、不在生成、界面无消息，
     * 任何一条不满足就放弃打开旧会话，绝不在用户已开始的对话上 `switch_session`。
     * 真实引擎才需要；Fake 引擎无需启动。失败不抛出，聊天页会显示引擎状态与诊断。
     */
    suspend fun warmUp(engine: EngineClient, ctx: BindingContext, runtime: RuntimeManager, sessions: SessionRepository, log: AppLog) {
        val gate = gates[engine]
        val pi = (engine as? GatedEngineClient)?.inner as? PiEngine ?: engine as? PiEngine
        if (pi == null) {
            gate?.release()
            return
        }
        try {
            if (untouched(pi)) {
                // 运行标记与它指向的会话文件是否还在，只读一次。
                val (marker, markerFileExists) = withContext(Dispatchers.IO) {
                    val m = runCatching { SharedPrefsRunMarker(ctx.appContext).get() }.getOrNull()
                    m to (!m.isNullOrEmpty() && runCatching { runtime.hostFile(m).isFile }.getOrDefault(false))
                }
                val needList = marker == null || (marker.isNotEmpty() && !markerFileExists)
                val list = if (needList) runCatching { sessions.observe().first() }.getOrDefault(emptyList()) else emptyList()
                val target = ResumePolicy.coldStartTarget(marker, { markerFileExists }, list)
                // 读标记、扫目录期间状态可能已变（闸门之前就在途的命令）：打开前再确认一次。
                if (target != null && untouched(pi)) {
                    when (val r = pi.openSession(target.guestPath, target.cwd)) {
                        is Outcome.Ok -> return
                        is Outcome.Err -> log.w(TAG, "冷启动回到上次会话失败，改为新开会话：${r.message}")
                    }
                } else if (target != null) {
                    log.i(TAG, "预热期间引擎已被使用，不再自动打开上次会话")
                }
            }
            pi.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(TAG, "预热引擎失败", e)
        } finally {
            gate?.release()
        }
    }

    private fun untouched(pi: PiEngine): Boolean =
        ResumePolicy.mayAutoOpen(pi.currentSessionFile, pi.status.value, pi.chat.value)

    private const val TAG = "warmUp"
}
