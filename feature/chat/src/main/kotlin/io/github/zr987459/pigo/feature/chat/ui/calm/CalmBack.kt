package io.github.zr987459.pigo.feature.chat.ui.calm

import io.github.zr987459.pigo.contract.session.OverlayModel

/**
 * UI 形态的返回键（PR #21 审查第 1 轮 Important 3）：依次关闭 展开编辑器 → ⌘ / 面板（overlay 栈顶）→ 会话列表 → 会话内搜索；
 * 都关着时返回 [Step.NONE]，由装配层（FormHost）把 App 退到后台（不结束 Activity、不打断生成，N4）。
 *
 * 扩展对话框（ExtDialog）必须由用户回答（pi 里也只能 Esc / 选项），返回键不替它作答：它在栈顶时跳过 overlay 这一步。
 */
object CalmBack {
    enum class Step { EDITOR, OVERLAY, DRAWER, SEARCH, NONE }

    fun step(editorExpanded: Boolean, top: OverlayModel?, drawerOpen: Boolean, searchOpen: Boolean): Step = when {
        editorExpanded -> Step.EDITOR
        top != null && top !is OverlayModel.ExtDialog -> Step.OVERLAY
        drawerOpen -> Step.DRAWER
        searchOpen -> Step.SEARCH
        else -> Step.NONE
    }
}
