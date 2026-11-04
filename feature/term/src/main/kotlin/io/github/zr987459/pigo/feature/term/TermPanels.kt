package io.github.zr987459.pigo.feature.term

import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.models.ModelPanels
import io.github.zr987459.pigo.contract.models.ModelPickerTab
import io.github.zr987459.pigo.contract.session.FlowState
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SessionHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 记下哪些面板是 [ModelPanels] 产出的（PR #18 审查第 3 轮 Important 1）：面板是否走面板事件，按**来源**判定，
 * 而不是按 id 是否在动作表里——`model.select`、`auth.login`、`auth.logout`、`provider.custom` 这几个面板 id 恰好也是动作 id。
 *
 * 装配层（r3/app）用它包一层真实的 ModelPanels，再交给 `PiActionsImpl`；终端形态的 [TermEnv] 传
 * `panelSource = tracker`（`ownsPanel` 与 `onPanelEvent` 都取自它）。
 * 每个工厂方法和 [onPanelEvent] 返回的面板 id 都会被记下；记下的 id 只增不减（面板 id 由面板宿主定，同一 id 始终同一来源）。
 */
class PanelSourceTracker(private val inner: ModelPanels) : ModelPanels {
    private val produced = ConcurrentHashMap.newKeySet<String>()

    /** [model] 是否由面板宿主产出（按记下的来源判断）。 */
    fun owns(model: OverlayModel): Boolean = model.id in produced

    private fun <T : OverlayModel> rec(m: T): T = m.also { produced += it.id }

    override suspend fun modelPicker(tab: ModelPickerTab, query: String) = rec(inner.modelPicker(tab, query))
    override suspend fun providerList(query: String) = rec(inner.providerList(query))
    override suspend fun providerForm(providerId: String) = rec(inner.providerForm(providerId))
    override suspend fun customProviderForm(providerId: String?) = rec(inner.customProviderForm(providerId))
    override suspend fun oauthFlow(providerId: String) = rec(inner.oauthFlow(providerId))
    override suspend fun logoutPicker() = rec(inner.logoutPicker())

    override suspend fun onPanelEvent(overlayId: String, key: String, value: String?): ActionResult =
        inner.onPanelEvent(overlayId, key, value).also { if (it is ActionResult.Overlay) produced += it.model.id }
}

/**
 * 终端形态的面板路由与结果落地（非 Composable，便于用真实 SessionHostImpl 做 JVM 测试）。
 *
 * 路由（审查第 3 轮 Important 1）：面板宿主产出的面板（[TermEnv.panelSource] 认得的）的选中、提交、确认、Enter
 * 一律发面板事件 `onPanelEvent(面板 id, …)`；其余面板（动作表 / SessionHost 自己的 `overlay:*`）照旧 `runAction(面板 id, 值)`。
 * 没有注入 panelSource 时退回旧规则 [TermInput.routesToPanel]（只用于 FakeSessionHost 开发与测试）。
 *
 * 落地（审查第 3 轮 Important 2，与设置页 `SettingsPanelLogic.closesPanel` 一致）：
 * - [ActionResult.Done] 关闭该面板；
 * - [ActionResult.Overlay] 同 id 替换栈顶；栈中更下面有同 id 的层则回到它（约定见 `ModelPanels.onPanelEvent`）；否则压栈；
 * - [ActionResult.Toast] **不关面板**，提示显示在该面板里（[notices]），已填值保留；面板已不在栈顶时走全局提示。
 */
class TermPanelRouter(
    private val host: SessionHost,
    private val env: TermEnv,
    private val catalogIds: Set<String>,
    private val scope: CoroutineScope,
    private val runAction: (String, String?) -> Unit,
    private val toast: (String) -> Unit,
) {
    private val _notices = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 面板 id → 面板内显示的提示（失败、校验不通过、「已保存」等）。面板关闭或换成新面板时清掉。 */
    val notices: StateFlow<Map<String, String>> = _notices.asStateFlow()

    /** 面板事件的出口：显式注入的 [TermEnv.onPanelEvent] 优先，否则用 [TermEnv.panelSource]。 */
    private val dispatcher: (suspend (String, String, String?) -> ActionResult)?
        get() = env.onPanelEvent ?: env.panelSource?.let { src -> { id: String, k: String, v: String? -> src.onPanelEvent(id, k, v) } }

    fun toPanel(model: OverlayModel): Boolean {
        if (dispatcher == null) return false
        val src = env.panelSource
        return if (src != null) src.owns(model) else TermInput.routesToPanel(model.id, catalogIds)
    }

    /** SelectList 选中一项 / Tree 选中节点。 */
    fun pick(model: OverlayModel, value: String) {
        if (toPanel(model)) panelEvent(model.id, value, null) else runAction(model.id, value)
    }

    /** 表单提交：面板宿主的表单发 `submit` + 表单值 JSON，按结果落地（失败不关）；动作表里的表单关闭后 runAction(面板 id, k=v 行)。 */
    fun submitForm(model: OverlayModel.Form): Job? {
        if (toPanel(model)) return panelEvent(model.id, "submit", TermInput.formJson(model.values))
        val vals = model.values
        if (host.overlay.top?.id == model.id) host.overlay.pop()
        runAction(model.id, vals.entries.joinToString("\n") { "${it.key}=${it.value}" })
        return null
    }

    /** 确认框：先关掉确认框本身，再回传。 */
    fun confirm(model: OverlayModel.Confirm, yes: Boolean) {
        if (host.overlay.top?.id == model.id) host.overlay.pop()
        clearNotice(model.id)
        if (toPanel(model)) panelEvent(model.id, if (yes) "confirm" else "cancel", null)
        else if (yes) runAction(model.id, "confirm")
    }

    /**
     * 用户关闭面板（Esc / 「escape 关闭」键帽）：先弹出，再按面板状态通知面板宿主（审查第 3 轮 Nit 2，与设置页 dismissEvent 一致）：
     * 账号登录面板成功（[FlowState.DONE]）发 `done`（拿到「已自动选回模型」等提示），其余状态发 `cancel`（取消后台登录任务）；
     * 自定义服务商表单发 `cancel`（丢弃草稿与 Key）。
     */
    fun close(model: OverlayModel) {
        if (host.overlay.top?.id == model.id) host.overlay.pop() else host.overlay.remove(model.id)
        clearNotice(model.id)
        val key = dismissEvent(model) ?: return
        if (!toPanel(model)) return
        val dispatch = dispatcher ?: return
        scope.launch {
            when (val r = dispatch(model.id, key, null)) {
                is ActionResult.Toast -> toast(r.text)
                is ActionResult.Overlay -> host.overlay.push(r.model)
                else -> Unit
            }
        }
    }

    /** 面板事件（键帽、选中、提交）交给面板宿主，结果按上面的规则落地一次。 */
    fun panelEvent(overlayId: String, key: String, value: String?): Job? {
        val dispatch = dispatcher
        if (dispatch == null) { toast("面板操作未接线：$overlayId/$key"); return null }
        return scope.launch { land(overlayId, dispatch(overlayId, key, value)) }
    }

    internal fun land(overlayId: String, r: ActionResult) {
        when (r) {
            is ActionResult.Overlay -> {
                clearNotice(overlayId)
                clearNotice(r.model.id)
                val items = host.overlay.items.value
                when {
                    host.overlay.top?.id == r.model.id -> host.overlay.replaceTop(r.model)
                    // 同一流程里回到栈中更下面的某层（约定写在 ModelPanels.onPanelEvent 的注释里）
                    items.any { it.id == r.model.id } -> {
                        while (host.overlay.top != null && host.overlay.top?.id != r.model.id) host.overlay.pop()
                        host.overlay.replaceTop(r.model)
                    }
                    else -> host.overlay.push(r.model)
                }
            }
            is ActionResult.Toast ->
                if (host.overlay.items.value.any { it.id == overlayId }) _notices.update { it + (overlayId to r.text) } else toast(r.text)
            ActionResult.Done -> {
                clearNotice(overlayId)
                if (host.overlay.top?.id == overlayId) host.overlay.pop() else host.overlay.remove(overlayId)
            }
            is ActionResult.LocalBlock -> toast(r.text)
        }
    }

    private fun clearNotice(id: String) = _notices.update { it - id }

    companion object {
        /** 关闭时要发给面板宿主的事件；null = 不发。 */
        fun dismissEvent(model: OverlayModel): String? = when {
            model.id.startsWith("oauth:") -> if ((model as? OverlayModel.Text)?.flow == FlowState.DONE) "done" else "cancel"
            model.id.startsWith("provider.custom") -> "cancel"
            else -> null
        }
    }
}
