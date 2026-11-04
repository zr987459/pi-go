package io.github.zr987459.pigo.form

import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.feature.term.TermPanelRouter

/**
 * 终端形态的系统返回键（r3/app PR 2）：与 UI 形态 CalmBack 一致——先关最上层可关的面板；扩展对话框固定在栈顶、
 * 必须由用户回答，不被返回键关掉（也不挡住下面的面板）。都没有时交给 FormHost 的退到后台。
 */
object TermBack {
    fun topClosable(stack: List<OverlayModel>): OverlayModel? = stack.lastOrNull { it !is OverlayModel.ExtDialog }

    /** 关掉 [m]；模型面板（登录中的 OAuth、自定义服务商表单）按 pi 的约定通知面板宿主 `cancel` / `done`。 */
    fun close(host: SessionHost, m: OverlayModel, notify: (overlayId: String, key: String) -> Unit) {
        host.overlay.remove(m.id)
        TermPanelRouter.dismissEvent(m)?.let { notify(m.id, it) }
    }
}
