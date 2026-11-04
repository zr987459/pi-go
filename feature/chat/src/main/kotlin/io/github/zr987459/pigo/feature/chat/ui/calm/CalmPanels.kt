package io.github.zr987459.pigo.feature.chat.ui.calm

import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.OverlayStack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 面板事件的接收方（KeyCap 点击协议，见 `contract/session/OverlayModel.kt` 的 [io.github.zr987459.pigo.contract.session.KeyCap]）。
 *
 * 模型配置面板（`ModelPanels`，r3/models）的面板 id 与动作 id 重名（`model.select`、`auth.login`…），
 * 面板内的点击不能再当成 `runAction(面板 id, key)`，否则会被当成「再打开一次面板」。
 * 由装配层（r3/app）提供：[owns] 为 true 的面板，面板内的点击、键帽、表单提交与附加按钮、确认框都改发 [onEvent]。
 *
 * r3/app 第 3 步加入（授权最小改动 ui/calm，见 PR 描述）。
 */
interface PanelEventSink {
    fun owns(overlayId: String): Boolean
    suspend fun onEvent(overlayId: String, key: String, value: String? = null): ActionResult
}

/** 面板事件的纯逻辑（与 Compose 无关，JVM 单测覆盖）。 */
object CalmPanelLogic {

    /**
     * 把面板事件的结果落到 overlay 栈上，返回要显示的提示（没有则 null）：
     * - Overlay：确认框压栈（取消后回到原面板）；同一面板或从该面板派生的下一步替换栈顶；
     * - Done：关闭发出事件的面板；
     * - Toast：面板保持打开（不丢刚输入的内容），只显示提示；
     * - LocalBlock：显示标题作为提示。
     */
    fun apply(stack: OverlayStack, fromId: String, r: ActionResult): String? = when (r) {
        is ActionResult.Overlay -> {
            val m = r.model
            val top = stack.top
            when {
                m is OverlayModel.Confirm -> stack.push(m)
                top != null && (top.id == fromId || top.id == m.id) -> stack.replaceTop(m)
                else -> stack.push(m)
            }
            null
        }
        ActionResult.Done -> { stack.remove(fromId); null }
        is ActionResult.Toast -> r.text
        is ActionResult.LocalBlock -> r.title
    }

    /** 表单值 JSON（与设置页 `formJson` 相同格式）：公开值 + 只在本地内存的密文字段。 */
    fun formJson(values: Map<String, String>, secrets: Map<String, String> = emptyMap()): String =
        JsonObject((values + secrets).mapValues { JsonPrimitive(it.value) }).toString()

    /** Form 的附加按钮（「测试」「账号登录」「清除」…）：面板自带的 [OverlayModel.Form.actions]。 */
    fun formActions(model: OverlayModel.Form): List<Pair<String, String>> = model.actions.map { it.actionId to it.label }

    /** OAuth 面板是否到了终点（成功 / 失败）。 */
    fun isOAuthFinal(model: OverlayModel): Boolean =
        model is OverlayModel.Text && (model.text.startsWith("已登录") || model.text.startsWith("登录失败"))

    /** 没到终点的 OAuth 面板每秒发一次 `refresh` 拉取进度（与设置页一致）。 */
    fun needsOAuthPolling(model: OverlayModel?): Boolean =
        model != null && model.id.startsWith("oauth:") && !isOAuthFinal(model)

    /** OAuth 失败时显示「重试」（发 `retry`，重新开始登录）。 */
    fun oauthRetryable(model: OverlayModel?): Boolean =
        model is OverlayModel.Text && model.id.startsWith("oauth:") && model.text.startsWith("登录失败")

    private val URL = Regex("https?://\\S+")

    /** OAuth 面板里的授权链接：给一个「打开链接」按钮。 */
    fun oauthUrl(model: OverlayModel?): String? =
        (model as? OverlayModel.Text)?.takeIf { it.id.startsWith("oauth:") }?.let { URL.find(it.text)?.value }

    /** 关闭面板时要发的事件：OAuth 未完成 → 取消登录；自定义服务商 → 丢弃草稿与 Key。 */
    fun dismissEvent(model: OverlayModel): String? = when {
        model.id.startsWith("oauth:") && !isOAuthFinal(model) -> "cancel"
        model.id.startsWith("provider.custom") -> "cancel"
        else -> null
    }

    /** 键帽回传的 value：面板当前高亮项（selectedKey）。 */
    fun keyCapValue(model: OverlayModel.SelectList): String? = model.selectedKey
}
