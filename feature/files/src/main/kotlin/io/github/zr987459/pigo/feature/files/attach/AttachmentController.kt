package io.github.zr987459.pigo.feature.files.attach

import io.github.zr987459.pigo.contract.Attachment
import io.github.zr987459.pigo.contract.AttachmentProcessor
import io.github.zr987459.pigo.contract.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream

/** 一个待处理 / 处理中 / 完成 / 失败的附件。 */
data class PendingAttachment(
    val id: Long,
    val displayName: String,
    val isImage: Boolean,
    val result: Attachment? = null,
    val error: String? = null,
) {
    val busy: Boolean get() = result == null && error == null
}

/** 一个外部来源（相册、相机、SAF）：显示名、MIME、大小（可能未知）、打开流。 */
class SourceItem(val displayName: String, val mimeType: String?, val sizeBytes: Long?, val open: () -> InputStream?)

/**
 * 附件选择的逻辑（C1、C2）：图片走 [AttachmentProcessor.prepareImage]，其他文件导入工作区。
 * 与界面分开便于单测；W4 的 ChatController 拿 [ready] 的结果去 `sendWithImages` / 拼 `@路径`。
 *
 * @param supportsImages 当前模型能否看图（`model.input` 含 image）；不能时图片按文件导入并提示换模型
 */
class AttachmentController(
    private val processor: AttachmentProcessor,
    private val scope: CoroutineScope,
    private val cwd: () -> String,
    private val supportsImages: () -> Boolean = { true },
    private val maxFileBytes: Long = AttachmentProcessor.MAX_FILE_BYTES,
) {
    private val _items = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val items: StateFlow<List<PendingAttachment>> = _items.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private var seq = 0L

    val ready: List<Attachment> get() = _items.value.mapNotNull { it.result }

    fun add(sources: List<SourceItem>, asImages: Boolean) {
        for (src in sources) {
            val image = asImages && (src.mimeType == null || src.mimeType.startsWith("image/")) && src.mimeType != "image/svg+xml"
            val id = ++seq
            if (!image && src.sizeBytes != null && src.sizeBytes > maxFileBytes) {
                _items.update { it + PendingAttachment(id, src.displayName, false, error = "超过 ${maxFileBytes / (1024 * 1024)} MB，不能作为附件") }
                continue
            }
            val asImage = image && supportsImages()
            if (image && !asImage) _notice.value = "当前模型不支持看图，图片会作为文件附上；要让模型看图，请换一个支持图片的模型"
            _items.update { it + PendingAttachment(id, src.displayName, asImage) }
            scope.launch {
                val input = runCatching { src.open() }.getOrNull()
                val r: Outcome<Attachment> = if (input == null) {
                    Outcome.Err(io.github.zr987459.pigo.contract.ErrCode.UNKNOWN, "无法读取")
                } else input.use {
                    if (asImage) when (val o = processor.prepareImage(it, src.mimeType, src.displayName)) {
                        is Outcome.Ok -> Outcome.Ok(Attachment.Image(o.value))
                        is Outcome.Err -> o
                    } else processor.importFile(src.displayName, it, cwd(), maxFileBytes)
                }
                _items.update { list ->
                    list.map { p ->
                        if (p.id != id) p else when (r) {
                            is Outcome.Ok -> p.copy(result = r.value)
                            is Outcome.Err -> p.copy(error = r.message)
                        }
                    }
                }
            }
        }
    }

    fun remove(id: Long) = _items.update { l -> l.filterNot { it.id == id } }

    fun clear() = _items.update { emptyList() }

    fun consumeNotice() { _notice.value = null }

    /** 发送用：文字 + 文件引用、图片列表；处理中的、失败的不算。 */
    fun compose(text: String): Pair<String, List<io.github.zr987459.pigo.contract.ImageInput>> = processor.compose(text, ready)
}
