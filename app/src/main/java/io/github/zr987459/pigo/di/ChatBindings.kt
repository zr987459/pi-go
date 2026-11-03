package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.EngineClient

/** 聊天界面的依赖（归 mvp/chat-ui）。scaffold 初版只含 fake()。 */
object ChatBindings {
    /** 聊天界面需要的外部依赖；mvp/chat-ui 可按需扩展字段。 */
    class ChatDeps(val engine: EngineClient)

    fun deps(mode: Mode, ctx: BindingContext, engine: EngineClient): ChatDeps = when (mode) {
        Mode.FAKE -> fake(engine)
        Mode.REAL -> notWired("Chat")
    }

    fun fake(engine: EngineClient): ChatDeps = ChatDeps(engine)
}
