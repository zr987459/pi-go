package io.github.zr987459.pigo.feature.chat.ui.calm

import io.github.zr987459.pigo.contract.session.OverlayModel

/**
 * 界面的返回键（r5/openminis，照 OpenMinis 的导航栈）：会话列表是首页，聊天页压在它上面。
 * CalmSurface 的画法（自下而上）：聊天页（消息 + 会话内搜索条）→ 会话列表 → 展开编辑器 → 面板（overlay 栈顶）。
 * 返回依次：面板 → 展开编辑器 → （会话列表在前台：不拦截，由装配层退到后台）→ 会话内搜索 → 聊天页返回会话列表（[Step.LIST]）。
 * （r3 时会话列表是抽屉，返回关抽屉、聊天页再按返回退到后台；r5 改成 OpenMinis 的「聊天 ← 会话列表」两级。）
 *
 * 扩展对话框（ExtDialog）必须由用户回答（pi 里也只能 Esc / 选项），返回键不替它作答；它在最上层时也不越过它去关
 * 下面看不见的层，返回 [Step.NONE]（退到后台，对话框和计时原样保留）。
 */
object CalmBack {
    enum class Step { EDITOR, OVERLAY, LIST, SEARCH, NONE }

    fun step(editorExpanded: Boolean, top: OverlayModel?, listOpen: Boolean, searchOpen: Boolean): Step = when {
        top is OverlayModel.ExtDialog -> Step.NONE
        top != null -> Step.OVERLAY
        editorExpanded -> Step.EDITOR
        listOpen -> Step.NONE
        searchOpen -> Step.SEARCH
        else -> Step.LIST
    }
}
