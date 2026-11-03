package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.feature.chat.ChatConfig
import io.github.zr987459.pigo.feature.chat.ChatController
import io.github.zr987459.pigo.feature.chat.ChatControllers

/**
 * 聊天界面的依赖（归 mvp/chat-ui）。
 *
 * 聊天界面没有「假的」和「真的」之分：它只依赖 [EngineClient]，Fake 还是真实引擎由 EngineBindings 决定。
 * 因此 fake() 与 real() 装配相同，mvp/integration 把 BindingMode.chat 切到 REAL 不会报错。
 */
object ChatBindings {
    /**
     * @param controller 与引擎同寿的聊天控制器：扩展对话框、草稿、撤回芯片都在这里，切页/旋转不丢。
     *                   导航层可用 `ChatScreen(deps.controller, onOpenModelSettings = …)` 显示聊天页。
     */
    class ChatDeps(val engine: EngineClient, val controller: ChatController)

    fun deps(mode: Mode, ctx: BindingContext, engine: EngineClient): ChatDeps = when (mode) {
        Mode.FAKE -> fake(engine, ctx)
        Mode.REAL -> real(engine, ctx)
    }

    fun fake(engine: EngineClient, ctx: BindingContext): ChatDeps = build(engine, ctx)

    fun real(engine: EngineClient, ctx: BindingContext): ChatDeps = build(engine, ctx)

    private fun build(engine: EngineClient, ctx: BindingContext): ChatDeps =
        ChatDeps(engine, ChatControllers.obtain(engine, ctx.appScope, ChatConfig()))
}
