package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.OutgoingMessage
import io.github.zr987459.pigo.contract.QueueSnapshot
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

    /**
     * 发送当前草稿（走 Outbox；`!`/`!!` 走 bash）。
     * - 被拒（停止中、模型不支持图片、上一条命令还在跑等）：返回 Err，草稿（文字、图片、`@` 引用、`!` 模式）**原样不动**；
     * - 接受：返回 Ok，端口清掉已发出的内容（「已撤回」芯片保留）。之后真正送达或失败经 [outgoing] 报告：
     *   失败的消息留在 [outgoing] 里可重试 / 退回草稿，并发一条 [notices]（审查第 2 轮 I1、I3）。
     */
    suspend fun send(mode: SendMode): Outcome<Disposition>

    /** 外发消息的送达状态（发送中 / 已排队 / 失败可重试），两种形态共用（审查第 2 轮 I1）。 */
    val outgoing: StateFlow<List<OutgoingMessage>>

    fun retryOutgoing(id: String)

    fun editOutgoing(id: String)

    fun discardOutgoing(id: String)

    /**
     * 停止生成（Esc / 停止键）：先收起阻塞对话框、等在途发送，再 clear_queue → abort。撤回的排队消息进**共享草稿**
     * （草稿为空时放进输入框，否则进 [DraftState.recalled]，随草稿持久化），两种形态都看得到（审查第 2 轮 I2）。
     * 返回停止结果（撤回的快照）。
     */
    suspend fun stop(): Outcome<QueueSnapshot>

    /** 等待应答的扩展阻塞对话框（select / confirm / input / editor），按到达顺序。 */
    val dialogs: StateFlow<List<ExtUiRequest>>

    fun answerDialog(requestId: String, value: String? = null, confirmed: Boolean? = null, cancelled: Boolean = false)

    /** 扩展 `set_editor_text` 想替换**非空**草稿时的待确认文字；草稿为空时端口直接写入，不出现在这里。 */
    val editorTextOffer: StateFlow<String?>

    fun resolveEditorTextOffer(accept: Boolean)

    /** 一次性提示（扩展 notify、发送失败等）。 */
    val notices: Flow<String>
}
