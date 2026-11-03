package io.github.zr987459.pigo.contract.session

/**
 * 共享草稿（DESIGN-r3 §5.1、§5.3）：两种形态共用同一份，切换时原样带过去。
 * 由 DraftStore 持久化；格式变化时须兼容读取旧格式（§8.2 第 7 条）。
 */
data class DraftState(
    val text: String = "",
    /**
     * 光标 / 选区，按 UTF-16 下标；无选区时 start == end。
     * 注意：`copy(text = 更短的文字)` 不会自动收缩选区，原始值可能越界或 start > end。
     * **读取一律用 [safeSelectionStart] / [safeSelectionEnd]**（已夹到 `0..text.length` 且有序），
     * 改文字用 [withText]；持久化前可调 [normalized]。
     */
    val selectionStart: Int = text.length,
    val selectionEnd: Int = selectionStart,
    val images: List<DraftImage> = emptyList(),
    /** `@` 引用的文件（相对工作目录的路径）。 */
    val fileRefs: List<String> = emptyList(),
    val bashMode: BashMode = BashMode.NONE,
    /** 「已撤回」芯片：切换会话或引擎重启时从队列退回、而草稿非空时放这里（§7.3 QueueBackToDraftTest）。 */
    val recalled: List<String> = emptyList(),
) {
    /** 夹到 `0..text.length`、且不大于 [safeSelectionEnd] 的选区起点。 */
    val safeSelectionStart: Int get() = minOf(selectionStart.coerceIn(0, text.length), selectionEnd.coerceIn(0, text.length))

    /** 夹到 `0..text.length`、且不小于 [safeSelectionStart] 的选区终点。 */
    val safeSelectionEnd: Int get() = maxOf(selectionStart.coerceIn(0, text.length), selectionEnd.coerceIn(0, text.length))

    /**
     * 草稿里**什么都没有**（含「已撤回」芯片与 `!` 模式）。持久化与「是否清掉草稿」用它判断，
     * 只有它为真时才能丢弃草稿，否则会把撤回芯片或 `!` 模式丢掉。
     */
    val isEmpty: Boolean get() = text.isEmpty() && images.isEmpty() && fileRefs.isEmpty() && recalled.isEmpty() && bashMode == BashMode.NONE

    /** 有可发送的内容（非空白文字、图片或 `@` 引用）。「发送」按钮是否可用用它判断。 */
    val hasSendableContent: Boolean get() = text.isNotBlank() || images.isNotEmpty() || fileRefs.isNotEmpty()

    /** 换文字并把光标放到末尾（其他字段不变）。 */
    fun withText(newText: String): DraftState = copy(text = newText, selectionStart = newText.length, selectionEnd = newText.length)

    /** 选区写回合法值。 */
    fun normalized(): DraftState =
        if (selectionStart == safeSelectionStart && selectionEnd == safeSelectionEnd) this
        else copy(selectionStart = safeSelectionStart, selectionEnd = safeSelectionEnd)

    companion object {
        val EMPTY = DraftState()
    }
}

/** 附件图片。[uri] 为 App 内可读的 content/file URI；[mimeType] 如 image/png。 */
data class DraftImage(val uri: String, val mimeType: String? = null, val name: String? = null)

/** `!`（结果进上下文）与 `!!`（不进上下文）命令模式，对应 pi 的 bash `excludeFromContext`。 */
enum class BashMode(val prefix: String) { NONE(""), INCLUDE("!"), EXCLUDE("!!") }
