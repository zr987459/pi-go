package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.fake.FakeSessionHost
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.contract.session.UiForm

/**
 * 两种形态共用的 [SessionHost]（DESIGN-r3 §5.1）。AppGraph 持有单例，与进程同寿。
 *
 * - FAKE：`FakeSessionHost`（core/contract），开发期与 A 档切换测试用。
 * - REAL：`SessionHostImpl`（core/session，r3/session PR #20）合入 main 后在这里接上，
 *   并把同一个 engine / extras、`ModelPanels` 实现（r3/models）注入进去。
 */
object SessionBindings {
    fun host(mode: Mode, ctx: BindingContext, initialForm: UiForm): SessionHost =
        when (mode) {
            Mode.FAKE -> fake(ctx, initialForm)
            Mode.REAL -> notWired("Session")
        }

    fun fake(ctx: BindingContext, initialForm: UiForm): SessionHost =
        FakeSessionHost(ctx.appScope, initialForm = initialForm)
}
