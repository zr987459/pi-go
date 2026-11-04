package io.github.zr987459.pigo.contract.session

/**
 * 一条外发消息的送达状态（r3/session 审查第 2 轮 I1）。两种形态都经 [SessionHost.outgoing] 读到同一份：
 * 「发送中」「已排队」「失败，可重试」都看得见，失败的消息不会悄无声息地消失。
 *
 * 新增文件，不改已有契约类型；[SessionHost] 上对应的成员都有默认实现（旧实现与 FakeSessionHost 不用改）。
 */
data class OutgoingMessage(
    /** 本地 id（重试、改一改、删除都按它）；引擎队列里的条目为 `queue:<steer|follow>:<序号>`。 */
    val id: String,
    val text: String,
    val status: OutgoingStatus,
    /** 附带的图片张数。 */
    val imageCount: Int = 0,
    /** 失败原因（[OutgoingStatus.FAILED] 时非空）。 */
    val error: String? = null,
    /** 失败原因提示去设置模型（没配模型、Key 失效等）。 */
    val needsSettings: Boolean = false,
) {
    /** 失败的消息可以重试（[SessionHost.retryOutgoing]）或退回草稿（[SessionHost.editOutgoing]）。 */
    val retryable: Boolean get() = status == OutgoingStatus.FAILED
}

enum class OutgoingStatus {
    /** 已交给引擎，还没拿到响应。 */
    SENDING,

    /** pi 已收下并开始处理，等它出现在对话里。 */
    SENT,

    /** 生成中发出的插话 / 排队消息，在 pi 的队列里等待。 */
    QUEUED,

    /** 发送失败：内容保留，可重试或退回草稿。 */
    FAILED,
}
