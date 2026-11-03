package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.AppLog
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.fake.FakeRuntimeManager

/** 运行环境（归 mvp/runtime）。scaffold 初版只含 fake()。 */
object RuntimeBindings {
    fun runtime(mode: Mode, ctx: BindingContext, log: AppLog): RuntimeManager = when (mode) {
        Mode.FAKE -> fake()
        Mode.REAL -> notWired("Runtime")
    }

    fun fake(): RuntimeManager = FakeRuntimeManager()
}
