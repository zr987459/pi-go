package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.AppLog
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
}
