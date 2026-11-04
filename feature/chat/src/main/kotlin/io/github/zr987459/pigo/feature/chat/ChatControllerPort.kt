package io.github.zr987459.pigo.feature.chat

import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.session.BashMode
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.core.session.AtFileExpansion
import io.github.zr987459.pigo.core.session.SessionChatPort
import io.github.zr987459.pigo.feature.chat.logic.ExtUiAnswer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    private val imageLoader: suspend (List<io.github.zr987459.pigo.contract.session.DraftImage>) -> List<ImageInput> = { emptyList() },
) : SessionChatPort {

    override val draft: MutableStateFlow<DraftState> get() = controller.sharedDraft

    override suspend fun send(mode: SendMode): Outcome<Disposition> {
        if (controller.isStopping) return Outcome.Err(ErrCode.RPC_REJECTED, "正在停止，稍后再发")
        val d = draft.value
        if (!d.hasSendableContent) return Outcome.Err(ErrCode.RPC_REJECTED, "草稿为空")
        val body = AtFileExpansion.compose(d.text, d.fileRefs)
        val text = if (d.bashMode != BashMode.NONE && !body.startsWith(d.bashMode.prefix)) d.bashMode.prefix + body else body
        if (d.images.isNotEmpty()) controller.addImages(imageLoader(d.images))
        controller.setDraft(text)
        controller.submit(mode)
        // submit 接受后会清空输入框（composer.draft）；没清空 = 被拒（停止中、模型不支持图片等，原因已经发到 notices）。
        if (controller.composerDraft.isNotEmpty() && controller.composerDraft == text) {
            controller.setDraft(d.text) // 草稿原样放回（@ 引用、! 模式仍在 sharedDraft 里）
            return Outcome.Err(ErrCode.RPC_REJECTED, "未发送")
        }
        // 已交给 Outbox：清掉 @ 引用、! 模式、图片；「已撤回」芯片保留。
        // 文字以输入框为准（submit 已清空；同步协程可能还没跑，不能把旧文字写回去）。
        draft.value = draft.value.copy(fileRefs = emptyList(), images = emptyList(), bashMode = BashMode.NONE).withText(controller.composerDraft)
        return Outcome.Ok(Disposition.STARTED)
    }

    override val dialogs: StateFlow<List<ExtUiRequest>> get() = controller.pendingDialogs

    override fun answerDialog(requestId: String, value: String?, confirmed: Boolean?, cancelled: Boolean) =
        controller.answerDialog(requestId, ExtUiAnswer(value = value, confirmed = confirmed, cancelled = cancelled))

    override val editorTextOffer: StateFlow<String?> get() = controller.editorTextOffer

    override fun resolveEditorTextOffer(accept: Boolean) = if (accept) controller.acceptReplaceDraft() else controller.rejectReplaceDraft()

    override val notices: Flow<String> get() = controller.messages
}
