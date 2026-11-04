package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.session.OverlayStack
import java.util.concurrent.atomic.AtomicInteger

/**
 * 选择面板选完即关（r3/app PR 2，交接残留；PR #26 审查 Nit 2）。
 *
 * 判据是**结构化**的：这次面板事件期间 `set_model` 成功了（[SetModelSignal] 计数变了），不看提示文案。覆盖两条路径：
 * - `/model` 里直接选中已配置的模型（事件在 `model.select` 上）→ 关选择面板；
 * - 选未配置模型 → 服务商表单 / 账号登录里填好凭据 → 自动选回（事件在 `provider.form:*`、`oauth:*` 等上）→
 *   关掉选择面板与触发它的那个面板，提示走全局（面板已不在栈里，两种形态都按全局提示落地）。
 * 切换失败、去配置服务商（返回 Overlay）、星标 / 设为默认等不调用 set_model 的事件都不关。
 * UI 形态（CalmPanelLogic）与终端形态（TermPanelRouter）都经 [ModelPanelEvents] 走这里，两形态一致。
 */
object PickClose {
    const val MODEL_PICKER = "model.select"

    /** @param switched 本次事件期间 set_model 成功过 */
    fun after(stack: OverlayStack, overlayId: String, r: ActionResult, switched: Boolean): ActionResult {
        if (!switched || r is ActionResult.Overlay) return r
        val pickerOpen = stack.items.value.any { it.id == MODEL_PICKER }
        if (!pickerOpen) return r
        stack.remove(MODEL_PICKER)
        // 自动选回：触发它的表单 / 登录面板一并关掉（流程结束）
        if (overlayId != MODEL_PICKER) stack.remove(overlayId)
        return r
    }
}

/**
 * 交给 RealModelPanels 的引擎包装：只数「set_model 成功」的次数，其余原样委托（同一个引擎对象，不另起）。
 * 这是 [PickClose] 不依赖提示文案的成败信号（PR #25 的结构化标志进 main 后可换成它）。
 */
class SetModelSignal(private val inner: EngineClient) : EngineClient by inner {
    private val ok = AtomicInteger()
    val count: Int get() = ok.get()

    override suspend fun setModel(provider: String, modelId: String): Outcome<ModelInfo> =
        inner.setModel(provider, modelId).also { if (it is Outcome.Ok) ok.incrementAndGet() }
}
