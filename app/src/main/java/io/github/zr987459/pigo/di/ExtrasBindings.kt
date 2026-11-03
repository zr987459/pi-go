package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras

/**
 * pi 1.5.0 其余 RPC 的门面（W1 feat/engine-rpc：图片、斜杠命令、`!` 命令、分叉/树、用量、压缩、导出、任务通知）。
 *
 * W1（PR #13）已合入 main：真实实现是 [io.github.zr987459.pigo.core.engine.PiEngine] 本身，经 W1 的
 * [EngineBindings.extras] 取出（[GatedEngineClient] 包装前的那个引擎）。[fake] 保留给单测与界面开发。
 */
object ExtrasBindings {
    fun extras(mode: Mode, ctx: BindingContext, engine: EngineClient): EngineExtras = when (mode) {
        Mode.FAKE -> fake()
        Mode.REAL -> real(ctx, engine)
    }

    fun fake(): EngineExtras = FakeEngineExtras()

    /**
     * W1 的真实 extras。引擎套了冷启动预热闸门时，extras 也走同一个闸门（[GatedEngineExtras]）：
     * extras 的命令同样经 `ensureStarted` 按需拉起引擎，不挡的话会抢在预热「回到上次会话」之前启动引擎。
     */
    @Suppress("UNUSED_PARAMETER")
    fun real(ctx: BindingContext, engine: EngineClient): EngineExtras {
        val raw = EngineBindings.extras(engine)
        val gate = (engine as? GatedEngineClient)?.gate ?: return raw
        return GatedEngineExtras(raw, gate)
    }
}
