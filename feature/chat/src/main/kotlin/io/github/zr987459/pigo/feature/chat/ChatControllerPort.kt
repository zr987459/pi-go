package io.github.zr987459.pigo.feature.chat

import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.session.BashMode
import io.github.zr987459.pigo.contract.session.DraftImage
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.OutgoingMessage
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.core.session.AtFileExpansion
import io.github.zr987459.pigo.core.session.SessionChatPort
import io.github.zr987459.pigo.feature.chat.logic.ExtUiAnswer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * `SessionHostImpl` 的聊天端口（审查第 1 轮 I1）：**包住现有的 [ChatController]**，不另起一套状态。
 *
 * - 草稿 = [ChatController.sharedDraft]（按会话经 DraftStore 持久化，进程被杀可恢复）；
 * - 发送 = [ChatController.submit]（走 Outbox：乐观气泡、「已排队」对账、失败可重试或改一改，未送达消息在引擎重启时接回）；
 * - 扩展对话框、`set_editor_text`、队列退回只由 ChatController 订阅和处理一次。
 *
 * @param imageLoader 把草稿里的图片 URI 读成 base64（Android ContentResolver，由 AppGraph 注入）。
 */
class ChatControllerPort(
    private val controller: ChatController,
    private val imageLoader: suspend (List<DraftImage>) -> List<ImageInput> = { emptyList() },
) : SessionChatPort {

    override val draft: MutableStateFlow<DraftState> get() = controller.sharedDraft

    /** 经端口发出、带图片的本地 id → 草稿里的图片 URI（失败后「改一改」时放回共享草稿）。 */
    private val sentImages = java.util.concurrent.ConcurrentHashMap<String, List<DraftImage>>()

    /**
     * 发送当前草稿（审查第 2 轮 I3）：图片直接随这一次提交传给 [ChatController.submitContent]，**不**放进控制器的附件，
     * 所以被拒时控制器里不留任何东西、草稿原样不动，重发也不会重复带图；是否被接受只看 submitContent 的明确返回值。
     */
    override suspend fun send(mode: SendMode): Outcome<Disposition> {
        if (controller.isStopping) return Outcome.Err(ErrCode.RPC_REJECTED, "正在停止，稍后再发")
        val d = draft.value
        if (!d.hasSendableContent) return Outcome.Err(ErrCode.RPC_REJECTED, "草稿为空")
        val body = AtFileExpansion.compose(d.text, d.fileRefs)
        val text = (if (d.bashMode != BashMode.NONE && !body.startsWith(d.bashMode.prefix)) d.bashMode.prefix + body else body).trim()
        val images = if (d.images.isEmpty()) emptyList() else imageLoader(d.images)
        if (d.images.isNotEmpty() && images.size != d.images.size) {
            val msg = "有图片读取失败，没有发送"
            controller.postNotice(msg)
            return Outcome.Err(ErrCode.RPC_REJECTED, msg)
        }
        val r = controller.submitContent(text, images, mode) {
            // 清掉已发出的内容；这期间用户又改过的文字保留，「已撤回」芯片保留。
            draft.update { cur ->
                val rest = cur.copy(
                    images = cur.images.filterNot { it in d.images },
                    fileRefs = cur.fileRefs.filterNot { it in d.fileRefs },
                    bashMode = if (cur.text == d.text) BashMode.NONE else cur.bashMode,
                )
                if (cur.text == d.text) rest.withText("") else rest
            }
        }
        return when (r) {
            is ChatController.SubmitResult.Rejected -> Outcome.Err(ErrCode.RPC_REJECTED, r.reason)
            is ChatController.SubmitResult.Accepted -> {
                if (r.localId != null && d.images.isNotEmpty()) sentImages[r.localId] = d.images
                Outcome.Ok(Disposition.STARTED)
            }
        }
    }

    override val outgoing: StateFlow<List<OutgoingMessage>> get() = controller.outgoing

    override fun retryOutgoing(id: String) = controller.retry(id)

    /**
     * 失败的消息退回共享草稿：文字按撤回规则（不覆盖草稿），经本端口发出的图片 URI 放回草稿。
     * 经旧入口 [ChatController.submit] 发出的带图消息（端口不知道它的图片 URI）：图片放回控制器的附件，也就是它原来所在的地方，
     * 不再丢失（PR #20 R3-N2）。
     */
    override fun editOutgoing(id: String) {
        val viaPort = sentImages.remove(id)
        if (controller.editFailed(id, restoreAttachments = viaPort == null) == null) {
            viaPort?.let { sentImages[id] = it }
            return
        }
        viaPort?.let { imgs -> draft.update { it.copy(images = it.images + imgs.filterNot { i -> i in it.images }) } }
    }

    override fun discardOutgoing(id: String) {
        controller.deleteFailed(id)
        sentImages.remove(id)
    }

    override suspend fun stop(): Outcome<QueueSnapshot> = controller.stopAndWait()

    override val dialogs: StateFlow<List<ExtUiRequest>> get() = controller.pendingDialogs

    override fun answerDialog(requestId: String, value: String?, confirmed: Boolean?, cancelled: Boolean) =
        controller.answerDialog(requestId, ExtUiAnswer(value = value, confirmed = confirmed, cancelled = cancelled))

    override val editorTextOffer: StateFlow<String?> get() = controller.editorTextOffer

    override fun resolveEditorTextOffer(accept: Boolean) = if (accept) controller.acceptReplaceDraft() else controller.rejectReplaceDraft()

    override val notices: Flow<String> get() = controller.messages
}
