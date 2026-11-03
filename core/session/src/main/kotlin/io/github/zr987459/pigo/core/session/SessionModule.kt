package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.actions.PiActionCatalog
import io.github.zr987459.pigo.contract.actions.PiActionSpec

/**
 * `:core:session` 骨架占位（r3/core 首推）。SessionHostImpl、PiActions.run、PiSettings、SessionCatalog
 * 由 r3/session 在本模块实现（DESIGN-r3 §5、§8.1）。
 */
object SessionModule {
    /** 动作表（第一版来自 core/contract 的 PiActionCatalog）。 */
    val actionSpecs: List<PiActionSpec> get() = PiActionCatalog.all
}
