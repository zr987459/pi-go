package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.session.DraftState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 聊天状态的唯一来源（审查第 1 轮 I1）。真实实现是 `feature/chat` 的 `ChatControllerPort`（包住现有的
 * `ChatController`：草稿按会话经 `DraftStore` 持久化、发送走 `Outbox`、扩展对话框与队列退回只订阅一次）。
 *
 * [SessionHostImpl] **不再**自己订阅 `uiRequests`、不再自己做队列退回、不再直接 `engine.send`：
 * 这些全部经这个端口，避免与 ChatController 出现两套状态。
 */
interface SessionChatPort {
    /** 共享草稿（两种形态读写同一份）。由端口按会话持久化，进程被杀后可恢复。 */
    val draft: MutableStateFlow<DraftState>

    /** 发送当前草稿（走 Outbox；`!`/`!!` 走 bash）。成功后端口清空草稿；失败时文字回到草稿（不覆盖新输入）。 */
    suspend fun send(mode: SendMode): Outcome<Disposition>

    /** 等待应答的扩展阻塞对话框（select / confirm / input / editor），按到达顺序。 */
    val dialogs: StateFlow<List<ExtUiRequest>>

    fun answerDialog(requestId: String, value: String? = null, confirmed: Boolean? = null, cancelled: Boolean = false)

    /** 扩展 `set_editor_text` 想替换**非空**草稿时的待确认文字；草稿为空时端口直接写入，不出现在这里。 */
    val editorTextOffer: StateFlow<String?>

    fun resolveEditorTextOffer(accept: Boolean)

    /** 一次性提示（扩展 notify、发送失败等）。 */
    val notices: Flow<String>
}
