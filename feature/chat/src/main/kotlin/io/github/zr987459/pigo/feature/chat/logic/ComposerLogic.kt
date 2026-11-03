package io.github.zr987459.pigo.feature.chat.logic

import io.github.zr987459.pigo.contract.QueueSnapshot

/**
 * 输入框与发送键的纯状态机（DESIGN.md §3.7），不依赖 Android，可在 JVM 上单测。
 *
 * - 空闲：发送键 = 发送（`prompt`）。
 * - 生成中、输入框有字：发送键默认「排队」（followUp），长按可选「插话」（steer）。
 * - 生成中、输入框为空：发送键变成「停止」。
 * - 停止中：按钮不可用，显示「正在停止…」。
 */
data class ComposerState(
    val draft: String = "",
    /** 停止时撤回、但因用户正在写草稿而没有放回输入框的文本；以「已撤回 n 条」芯片显示。 */
    val withdrawn: List<String> = emptyList(),
    val stopping: Boolean = false,
    /** 停止请求超过 [ChatConfig.stopSlowHintMs] 仍未完成。 */
    val stopSlow: Boolean = false,
    /**
     * 草稿被「非打字」方式改写（撤回放回、插入、扩展写入、发送后清空）时递增。
     * 界面只在它变化时用 [draft] 覆盖输入框，用户打字不会被滞后的状态回写打断。
     */
    val revision: Int = 0,
) {
    /** 以程序方式改写草稿。 */
    fun withDraft(text: String): ComposerState = copy(draft = text, revision = revision + 1)
}

enum class PrimaryAction {
    /** 空闲且有字：发送。 */
    SEND,

    /** 生成中且有字：默认排队，长按可选插话。 */
    QUEUE,

    /** 生成中且输入框为空：停止。 */
    STOP,

    /** 空闲且输入框为空，或正在停止：不可点。 */
    DISABLED,
}

object ComposerLogic {

    fun primaryAction(running: Boolean, composer: ComposerState): PrimaryAction = when {
        composer.stopping -> PrimaryAction.DISABLED
        running && composer.draft.isBlank() -> PrimaryAction.STOP
        running -> PrimaryAction.QUEUE
        composer.draft.isBlank() -> PrimaryAction.DISABLED
        else -> PrimaryAction.SEND
    }

    /** 撤回的文本按「先插话、后排队」的顺序排列，与 pi 送达顺序一致。 */
    fun withdrawnTexts(snapshot: QueueSnapshot): List<String> =
        (snapshot.steering + snapshot.followUp).filter { it.isNotBlank() }

    /**
     * 停止后撤回的文本怎么处理（§3.7）：**只在输入框为空时**放回输入框；
     * 用户已经在写新草稿时，不碰草稿，改为「已撤回 n 条」芯片。
     */
    fun onWithdrawn(state: ComposerState, texts: List<String>): ComposerState {
        if (texts.isEmpty()) return state
        return if (state.draft.isBlank()) {
            state.withDraft(join(texts))
        } else {
            state.copy(withdrawn = state.withdrawn + texts)
        }
    }

    /** 点芯片上的「插入输入框」：接在草稿后面，草稿原文保留。 */
    fun insertWithdrawn(state: ComposerState): ComposerState {
        if (state.withdrawn.isEmpty()) return state
        val joined = join(state.withdrawn)
        val draft = if (state.draft.isBlank()) joined else state.draft.trimEnd() + "\n" + joined
        return state.withDraft(draft).copy(withdrawn = emptyList())
    }

    fun discardWithdrawn(state: ComposerState): ComposerState = state.copy(withdrawn = emptyList())

    /** 命令失败时把文本退回（§3.9，规则同 §3.7：不覆盖草稿）。 */
    fun onSendRejected(state: ComposerState, text: String): ComposerState = onWithdrawn(state, listOf(text))

    /** 扩展的 `set_editor_text`：输入框为空时直接写入；否则需要询问用户（返回 null）。 */
    fun applyEditorText(state: ComposerState, text: String): ComposerState? =
        if (state.draft.isBlank()) state.withDraft(text) else null

    private fun join(texts: List<String>) = texts.joinToString("\n")
}
