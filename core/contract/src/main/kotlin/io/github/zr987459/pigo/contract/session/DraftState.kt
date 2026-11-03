package io.github.zr987459.pigo.contract.session

/**
 * 共享草稿（DESIGN-r3 §5.1、§5.3）：两种形态共用同一份，切换时原样带过去。
 * 由 DraftStore 持久化；格式变化时须兼容读取旧格式（§8.2 第 7 条）。
 */
data class DraftState(
    val text: String = "",
    /** 光标 / 选区，按 UTF-16 下标；无选区时 start == end。 */
    val selectionStart: Int = text.length,
    val selectionEnd: Int = selectionStart,
    val images: List<DraftImage> = emptyList(),
    /** `@` 引用的文件（相对工作目录的路径）。 */
    val fileRefs: List<String> = emptyList(),
    val bashMode: BashMode = BashMode.NONE,
    /** 「已撤回」芯片：切换会话或引擎重启时从队列退回、而草稿非空时放这里（§7.3 QueueBackToDraftTest）。 */
    val recalled: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isEmpty() && images.isEmpty() && fileRefs.isEmpty()

    companion object {
        val EMPTY = DraftState()
    }
}

/** 附件图片。[uri] 为 App 内可读的 content/file URI；[mimeType] 如 image/png。 */
data class DraftImage(val uri: String, val mimeType: String? = null, val name: String? = null)

/** `!`（结果进上下文）与 `!!`（不进上下文）命令模式，对应 pi 的 bash `excludeFromContext`。 */
enum class BashMode(val prefix: String) { NONE(""), INCLUDE("!"), EXCLUDE("!!") }
