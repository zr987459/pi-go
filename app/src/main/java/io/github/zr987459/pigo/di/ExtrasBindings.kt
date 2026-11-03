package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras

/**
 * pi 1.5.0 其余 RPC 的门面（W1 feat/engine-rpc：图片、斜杠命令、`!` 命令、分叉/树、用量、压缩、导出、任务通知）。
 *
 * W1（PR #13）合入 main 之前，engine 模块还没有 [EngineExtras] 的真实实现，[BindingMode.extras] 为 FAKE。
 * 合入后在 [real] 里返回 engine 侧的实现（例如 `(engine as PiEngine).extras`，以 W1 的公开入口为准），并把 BindingMode.extras 切 REAL。
 */
object ExtrasBindings {
    fun extras(mode: Mode, ctx: BindingContext, engine: EngineClient): EngineExtras = when (mode) {
        Mode.FAKE -> FakeEngineExtras()
        Mode.REAL -> real(ctx, engine)
    }

    @Suppress("UNUSED_PARAMETER")
    fun real(ctx: BindingContext, engine: EngineClient): EngineExtras = notWired("Extras")
}
