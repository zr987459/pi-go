package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.AppLog
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.LaunchEnvProvider
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.fake.FakeAppLog
import io.github.zr987459.pigo.contract.fake.FakeEngineClient

/** 引擎与日志（归 mvp/engine）。scaffold 初版只含 fake()。 */
object EngineBindings {
    fun engine(
        mode: Mode,
        ctx: BindingContext,
        runtime: RuntimeManager,
        launchEnv: LaunchEnvProvider,
        log: AppLog,
    ): EngineClient = when (mode) {
        Mode.FAKE -> fake(ctx)
        Mode.REAL -> notWired("Engine")
    }

    fun log(mode: Mode, ctx: BindingContext): AppLog = when (mode) {
        Mode.FAKE -> FakeAppLog()
        Mode.REAL -> notWired("Engine")
    }

    fun fake(ctx: BindingContext): EngineClient = FakeEngineClient(ctx.appScope)
}
