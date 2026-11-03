package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.AppLog
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.fake.FakeRuntimeManager
import io.github.zr987459.pigo.core.runtime.RealRuntimeManager

/** 运行环境（归 mvp/runtime）。 */
object RuntimeBindings {
    fun runtime(mode: Mode, ctx: BindingContext, log: AppLog): RuntimeManager = when (mode) {
        Mode.FAKE -> fake()
        Mode.REAL -> real(ctx, log)
    }

    fun fake(): RuntimeManager = FakeRuntimeManager()

    /** PRoot + Ubuntu + Node + pi 的真实实现（DESIGN.md §4）。 */
    fun real(ctx: BindingContext, log: AppLog): RuntimeManager = RealRuntimeManager.android(ctx.appContext, log)
}
