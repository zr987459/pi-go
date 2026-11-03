package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.RunOutcome
import io.github.zr987459.pigo.contract.RunOutcomeKind

/**
 * N1：一轮结束 / 需要用户处理时产出契约的 [RunOutcome]（`EngineExtras.runOutcomes`）。
 * 由引擎在 `agent_settled` 与阻塞式扩展对话框到达时调用。`aborted`（用户点了停止）产出 [RunOutcomeKind.ABORTED]，
 * 由通知层决定不发通知（轮末汇总等仍可用）。所有文字经 [redact] 脱敏。
 */
object RunResults {
    const val PREVIEW_MAX = 200

    /**
     * [state] 为归约了 `agent_settled` 之后的状态。
     * - [aborted] → ABORTED；
     * - 本轮最后一条助手消息 `stopReason == "error"`，或本轮末尾是「重试 N 次仍失败」提示 → ERROR（中文原因）；
     * - 否则 COMPLETED，[RunOutcome.lastTextPreview] 为最后一条回复正文开头（≤200 字，可能为 null：只调了工具）。
     */
    fun onSettled(
        state: ChatState,
        aborted: Boolean,
        atMillis: Long,
        cwd: String?,
        redact: (String) -> String = { it },
    ): RunOutcome {
        fun outcome(kind: RunOutcomeKind, message: String? = null, preview: String? = null) = RunOutcome(
            kind = kind,
            sessionFile = state.sessionFile,
            sessionName = state.sessionName,
            message = message?.let(redact),
            lastTextPreview = preview?.let(redact)?.let(::clip),
            atMillis = atMillis,
            cwd = cwd,
        )
        if (aborted) return outcome(RunOutcomeKind.ABORTED)
        val lastUser = state.items.indexOfLast { it is ChatItem.User }
        val turn = if (lastUser < 0) state.items else state.items.subList(lastUser + 1, state.items.size)
        val last = turn.lastOrNull { it is ChatItem.Assistant } as ChatItem.Assistant?
        if (last?.stopReason == "error") {
            return outcome(RunOutcomeKind.ERROR, ProviderErrors.hint(last.errorMessage).text)
        }
        val retryFailed = turn.lastOrNull { it is ChatItem.Notice && it.text.startsWith("重试 ") && it.text.contains("仍失败") } as ChatItem.Notice?
        if (retryFailed != null && (last == null || turn.indexOf(retryFailed) > turn.indexOf(last))) {
            return outcome(RunOutcomeKind.ERROR, retryFailed.text)
        }
        val text = last?.blocks?.filterIsInstance<ContentBlock.Text>()?.joinToString("") { it.text }?.trim().orEmpty()
        return outcome(RunOutcomeKind.COMPLETED, preview = text.takeIf { it.isNotEmpty() }?.replace(Regex("\\s+"), " "))
    }

    /** 阻塞式扩展对话框 → NEEDS_INPUT（message 为对话框标题）；非阻塞（notify/setStatus 等）→ null。 */
    fun onUiRequest(
        state: ChatState,
        req: ExtUiRequest,
        atMillis: Long,
        cwd: String?,
        redact: (String) -> String = { it },
    ): RunOutcome? {
        if (!req.needsResponse) return null
        val title = req.title?.takeIf { it.isNotBlank() } ?: req.message?.takeIf { it.isNotBlank() } ?: "pi 需要你确认"
        return RunOutcome(RunOutcomeKind.NEEDS_INPUT, state.sessionFile, state.sessionName, redact(title), null, atMillis, cwd)
    }

    private fun clip(s: String) = if (s.length > PREVIEW_MAX) s.take(PREVIEW_MAX - 1) + "…" else s
}
