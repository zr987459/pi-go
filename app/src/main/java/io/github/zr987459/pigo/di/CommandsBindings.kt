package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.LaunchEnvProvider
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.feature.commands.CommandsGraph

/** W4：会话索引（搜索/分组/置顶）、按会话草稿、自动命名、技能与模板（feature/commands）。 */
object CommandsBindings {
    fun graph(
        mode: Mode,
        ctx: BindingContext,
        runtime: RuntimeManager,
        engine: EngineClient,
        extras: EngineExtras,
        launchEnv: LaunchEnvProvider,
    ): CommandsGraph = when (mode) {
        Mode.FAKE -> CommandsGraph.fake()
        // 自动命名的 pi --print 要和引擎拿同一份 Key 环境（DESIGN §3.10）。
        Mode.REAL -> CommandsGraph.real(ctx.appContext.filesDir, ctx.appScope, runtime, engine, extras, launchEnv)
    }
}
