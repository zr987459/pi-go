package io.github.zr987459.pigo.feature.chat.ui.calm

import io.github.zr987459.pigo.contract.session.OverlayModel

/**
 * UI 形态的返回键（PR #21 审查第 1 轮 Important 3、第 2 轮 Nit 2）：按**画面层级自上而下**关一层。
 * CalmSurface 的画法（自下而上）：消息 + 会话内搜索条 → 会话列表 → 展开编辑器 → 面板（overlay 栈顶）。
 * 所以返回依次关 面板 → 展开编辑器 → 会话列表 → 搜索；都关着时返回 [Step.NONE]，由装配层（FormHost）把 App 退到后台
 * （不结束 Activity、不打断生成，N4）。
 *
 * 扩展对话框（ExtDialog）必须由用户回答（pi 里也只能 Esc / 选项），返回键不替它作答；它在最上层时也不越过它去关
 * 下面看不见的层，返回 [Step.NONE]（退到后台，对话框和计时原样保留）。
 */
object CalmBack {
    enum class Step { EDITOR, OVERLAY, DRAWER, SEARCH, NONE }

    fun step(editorExpanded: Boolean, top: OverlayModel?, drawerOpen: Boolean, searchOpen: Boolean): Step = when {
        top is OverlayModel.ExtDialog -> Step.NONE
        top != null -> Step.OVERLAY
        editorExpanded -> Step.EDITOR
        drawerOpen -> Step.DRAWER
        searchOpen -> Step.SEARCH
        else -> Step.NONE
    }
}
