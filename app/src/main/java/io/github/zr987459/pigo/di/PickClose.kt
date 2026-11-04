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
 * 判据全是**结构化**的，不看提示文案。覆盖两条路径：
 * - `/model` 里直接选中已配置的模型（`model:<ref>` 或「选用」键帽）→ 用 PR #25 的成败标志：结果是 `Toast(failed = false)`
 *   （选中事件的成功提示只有「已切换」一种；未配置走 Overlay，出错 / 没选中是 `failed = true`）→ 关选择面板；
 * - 选未配置模型 → 服务商表单 / 账号登录里填好凭据 → 自动选回（事件在 `provider.form:*`、`oauth:*` 等上）：
 *   这里 `failed = false` 既可能是「已保存」也可能是「已切换」，成败标志区分不了，所以看本次事件期间 `set_model` 是否成功
 *   （[SetModelSignal] 计数变了）→ 关掉选择面板与触发它的那个面板，提示走全局（面板已不在栈里，两种形态都按全局提示落地）。
 * - OAuth 登录在**后台**完成（浏览器回调 / 设备码，不经任何面板事件）→ RealModelPanels 自动选回 → [afterBackgroundSwitch]：
 *   由 [SetModelSignal.onSwitched] 触发，选择面板与 `oauth:<该服务商>` 登录面板都在栈里时一并关掉（PR #26 第 2 轮 Nit 2）。
 * 切换失败、去配置服务商（返回 Overlay）、星标 / 设为默认等不调用 set_model 的事件都不关。
 * UI 形态（CalmPanelLogic）与终端形态（TermPanelRouter）都经 [ModelPanelEvents] 走这里，两形态一致。
 */
object PickClose {
    const val MODEL_PICKER = "model.select"

    /** 选中一个模型的事件：点行（`model:<ref>`）或「选用」键帽（`select`，value = 高亮项）。 */
    fun isPick(overlayId: String, key: String): Boolean =
        overlayId == MODEL_PICKER && (key.startsWith("model:") || key == "select")

    /** @param switched 本次事件期间 set_model 成功过（自动选回路径用） */
    fun after(stack: OverlayStack, overlayId: String, key: String, r: ActionResult, switched: Boolean): ActionResult {
        val closes = when {
            r !is ActionResult.Toast -> false
            isPick(overlayId, key) -> !r.failed
            overlayId == MODEL_PICKER -> false // 星标、设为默认、刷新等不关
            else -> switched && !r.failed
        }
        if (!closes) return r
        val pickerOpen = stack.items.value.any { it.id == MODEL_PICKER }
        if (!pickerOpen) return r
        stack.remove(MODEL_PICKER)
        // 自动选回：触发它的表单 / 登录面板一并关掉（流程结束）
        if (overlayId != MODEL_PICKER) stack.remove(overlayId)
        return r
    }

    /**
     * 后台自动选回（不在面板事件里）：`set_model` 成功切到 [provider] 的模型，且选择面板与 `oauth:<provider>` 登录面板都开着
     * （= 从 /model 选了未登录的模型、正在登录；终端形态逐层压栈）→ 选择面板、该服务商的表单与登录面板一并关。
     * 没开选择面板（从 /login 直接登录；UI 形态选未配置模型时表单已替换掉选择面板）或不是这个服务商的登录面板时不动。
     * @return 是否关了
     */
    fun afterBackgroundSwitch(stack: OverlayStack, provider: String): Boolean {
        val ids = stack.items.value.map { it.id }
        val login = "oauth:$provider"
        if (MODEL_PICKER !in ids || login !in ids) return false
        stack.remove(login)
        stack.remove("provider.form:$provider")
        stack.remove(MODEL_PICKER)
        return true
    }
}

/**
 * 交给 RealModelPanels 的引擎包装：只数「set_model 成功」的次数，其余原样委托（同一个引擎对象，不另起）。
 * 这是 [PickClose] 自动选回路径的成败信号（PR #25 的 `Toast.failed` 分不清「已保存」与「已切换」）。
 * [onSwitched] 在每次成功后以服务商 id 回调：后台 OAuth 完成后的自动选回不经面板事件，靠它关面板。
 */
class SetModelSignal(private val inner: EngineClient) : EngineClient by inner {
    private val ok = AtomicInteger()
    val count: Int get() = ok.get()

    @Volatile var onSwitched: ((provider: String) -> Unit)? = null

    override suspend fun setModel(provider: String, modelId: String): Outcome<ModelInfo> =
        inner.setModel(provider, modelId).also { if (it is Outcome.Ok) { ok.incrementAndGet(); onSwitched?.invoke(provider) } }
}
