package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.ExtUiRequest

/**
 * N1：一轮结束 / 需要用户处理时给「任务通知」用的结果。由引擎在 `agent_settled` 与阻塞式扩展对话框到达时产出；
 * `aborted`（用户点了停止）不产出。与 `:core:contract` 的 RunOutcome（W0 契约）一一对应，由 `:core:engine` 的适配层转换。
 */
sealed interface RunResult {
    val sessionFile: String?
    val sessionName: String?

    /** 正常完成；[preview] 为最后一条回复的正文开头（已截断），可能为 null（只调了工具）。 */
    data class Completed(override val sessionFile: String?, override val sessionName: String?, val preview: String?) : RunResult

    /** 本轮以错误结束（模型 / 服务商错误、重试耗尽）。[message] 为面向用户的中文说明。 */
    data class Failed(override val sessionFile: String?, override val sessionName: String?, val message: String) : RunResult

    /** 扩展弹出了需要回答的对话框（select/confirm/input/editor），pi 在等用户。 */
    data class NeedsInput(
        override val sessionFile: String?,
        override val sessionName: String?,
        val requestId: String,
        val title: String,
    ) : RunResult
}

object RunResults {
    private const val PREVIEW_MAX = 120

    /**
     * `agent_settled` 后的结果：[aborted] → null（不通知）；本轮最后一条助手消息 `stopReason == "error"` → [RunResult.Failed]；
     * 本轮末尾是「重试 N 次仍失败」的提示 → Failed；否则 [RunResult.Completed]。
     * [state] 为归约了 `agent_settled` 之后的状态。
     */
    fun onSettled(state: ChatState, aborted: Boolean): RunResult? {
        if (aborted) return null
        val lastUser = state.items.indexOfLast { it is ChatItem.User }
        val turn = if (lastUser < 0) state.items else state.items.subList(lastUser + 1, state.items.size)
        val last = turn.lastOrNull { it is ChatItem.Assistant } as ChatItem.Assistant?
        if (last?.stopReason == "error") {
            return RunResult.Failed(state.sessionFile, state.sessionName, ProviderErrors.hint(last.errorMessage).text)
        }
        val retryFailed = turn.lastOrNull { it is ChatItem.Notice && it.text.startsWith("重试 ") && it.text.contains("仍失败") } as ChatItem.Notice?
        if (retryFailed != null && (last == null || turn.indexOf(retryFailed) > turn.indexOf(last))) {
            return RunResult.Failed(state.sessionFile, state.sessionName, retryFailed.text)
        }
        val text = last?.blocks?.filterIsInstance<ContentBlock.Text>()?.joinToString("") { it.text }?.trim().orEmpty()
        val preview = text.takeIf { it.isNotEmpty() }?.replace(Regex("\\s+"), " ")?.let {
            if (it.length > PREVIEW_MAX) it.take(PREVIEW_MAX) + "…" else it
        }
        return RunResult.Completed(state.sessionFile, state.sessionName, preview)
    }

    /** 阻塞式扩展对话框 → [RunResult.NeedsInput]；非阻塞（notify/setStatus 等）→ null。 */
    fun onUiRequest(state: ChatState, req: ExtUiRequest): RunResult? {
        if (!req.needsResponse) return null
        val title = req.title?.takeIf { it.isNotBlank() } ?: req.message?.takeIf { it.isNotBlank() } ?: "pi 需要你确认"
        return RunResult.NeedsInput(state.sessionFile, state.sessionName, req.id, title)
    }
}
