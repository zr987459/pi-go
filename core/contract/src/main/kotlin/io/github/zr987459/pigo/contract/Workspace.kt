package io.github.zr987459.pigo.contract

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

/*
 * W0（feat/modules）新增的契约：工作区文件、Git、附件、导出。W3（feat/files）实现，
 * docs/feature-gap.md §2.4 F1–F7、§2.2 C1–C2、§2.6 E1–E2。定稿后冻结。
 *
 * 路径一律为容器内（guest）绝对路径；实现负责映射到宿主 rootfs，并拒绝越界（`..`、指向 rootfs 外的符号链接）。
 * 纯 JVM 模块，不出现 Android 类型：SAF 的 Uri 由界面层打开成流后再传入。
 */

data class FileNode(
    val guestPath: String,
    val name: String,
    val isDir: Boolean,
    val sizeBytes: Long = 0,
    val modifiedAt: Instant? = null,
    val isSymlink: Boolean = false,
    val mimeType: String? = null,
    val hidden: Boolean = name.startsWith("."),
)

data class TextChunk(
    val text: String,
    val truncated: Boolean,
    val totalBytes: Long,
    /** 代码高亮用的语言名（按扩展名推断），未知为 null。 */
    val language: String? = null,
)

sealed interface FilePreview {
    val node: FileNode

    data class Text(override val node: FileNode, val chunk: TextChunk, val markdown: Boolean) : FilePreview
    data class Image(override val node: FileNode, val hostFile: File) : FilePreview

    /** PDF、音频、DOCX 等交给系统应用打开。 */
    data class External(override val node: FileNode, val hostFile: File) : FilePreview
    data class Binary(override val node: FileNode) : FilePreview
    data class Directory(override val node: FileNode, val children: List<FileNode>) : FilePreview
}

// ---------------- Git（F4、F6） ----------------

data class GitFileChange(
    val path: String,
    /** porcelain v2 的 XY 两位：index、worktree；`?` 为未跟踪。 */
    val indexStatus: Char,
    val worktreeStatus: Char,
    val origPath: String? = null,
) {
    val untracked: Boolean get() = indexStatus == '?'
}

data class GitStatus(
    val isRepo: Boolean,
    val branch: String? = null,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val files: List<GitFileChange> = emptyList(),
)

enum class DiffLineKind { CONTEXT, ADD, DELETE, META }

data class DiffLine(val kind: DiffLineKind, val text: String, val oldNo: Int? = null, val newNo: Int? = null)

data class DiffHunk(val header: String, val lines: List<DiffLine>)

data class FileDiff(
    val path: String,
    val oldPath: String? = null,
    val hunks: List<DiffHunk> = emptyList(),
    val binary: Boolean = false,
    val added: Int = 0,
    val deleted: Int = 0,
)

data class GitWorktree(val path: String, val branch: String?, val head: String?)

// ---------------- 工作区文件系统 ----------------

interface WorkspaceFs {
    /** 文件页的根：会话 cwd、`/root`。 */
    suspend fun roots(sessionCwd: String?): List<FileNode>

    suspend fun list(guestDir: String, showHidden: Boolean = false): Outcome<List<FileNode>>
    suspend fun stat(guestPath: String): Outcome<FileNode>

    /** 读文本；超过 [maxBytes] 截断（默认 1 MB）。 */
    suspend fun readText(guestPath: String, maxBytes: Long = DEFAULT_MAX_TEXT_BYTES): Outcome<TextChunk>
    suspend fun preview(guestPath: String): Outcome<FilePreview>

    /** 宿主侧真实文件（只读用途，分享、交给系统打开）。越界返回 Err。 */
    suspend fun hostFile(guestPath: String): Outcome<File>

    suspend fun mkdirs(guestDir: String): Outcome<FileNode>

    /** 把外部流写入 [targetDir]；重名自动加序号（`a (1).txt`）。 */
    suspend fun importStream(fileName: String, input: InputStream, targetDir: String): Outcome<FileNode>

    /** 把容器内文件写到外部流（SAF 导出）。 */
    suspend fun exportTo(guestPath: String, output: OutputStream): Outcome<Long>

    suspend fun gitStatus(cwd: String): Outcome<GitStatus>

    /** [path] 为 null 时整个仓库；[staged] 为 true 时 `--cached`。 */
    suspend fun gitDiff(cwd: String, path: String? = null, staged: Boolean = false): Outcome<List<FileDiff>>
    suspend fun gitWorktrees(cwd: String): Outcome<List<GitWorktree>>

    companion object {
        const val DEFAULT_MAX_TEXT_BYTES = 1L shl 20
    }
}

// ---------------- 附件（C1、C2，及系统分享入口） ----------------

sealed interface Attachment {
    val displayName: String

    data class Image(val image: ImageInput) : Attachment {
        override val displayName: String get() = image.displayName ?: "图片"
    }

    /** 已导入工作区的文件；发送时在消息里插入 [mention]（`@路径`）。 */
    data class FileRef(val node: FileNode) : Attachment {
        override val displayName: String get() = node.name
        val mention: String get() = "@" + node.guestPath
    }
}

/** 附件预处理（W3 实现）。 */
interface AttachmentProcessor {
    /** 压缩到长边 ≤ [maxEdgePx]、体积 ≤ [maxBytes]，校正 EXIF 方向，转 base64。 */
    suspend fun prepareImage(
        input: InputStream,
        mimeType: String?,
        displayName: String?,
        maxEdgePx: Int = MAX_IMAGE_EDGE_PX,
        maxBytes: Long = MAX_IMAGE_BYTES,
    ): Outcome<ImageInput>

    /** 导入到 `<cwd>/.pigo/uploads/<yyyy-MM-dd>/`；超过 [maxBytes] 拒绝。 */
    suspend fun importFile(fileName: String, input: InputStream, cwd: String, maxBytes: Long = MAX_FILE_BYTES): Outcome<Attachment.FileRef>

    /** 把附件拼进发送文本：文件引用追加为 `@路径`，图片单独返回。 */
    fun compose(text: String, attachments: List<Attachment>): Pair<String, List<ImageInput>> {
        val mentions = attachments.filterIsInstance<Attachment.FileRef>().joinToString(" ") { it.mention }
        val images = attachments.filterIsInstance<Attachment.Image>().map { it.image }
        val body = when {
            mentions.isEmpty() -> text
            text.isBlank() -> mentions
            else -> "$text\n\n$mentions"
        }
        return body to images
    }

    companion object {
        const val MAX_IMAGE_EDGE_PX = 1568
        const val MAX_IMAGE_BYTES = 5L * 1024 * 1024
        const val MAX_FILE_BYTES = 20L * 1024 * 1024
        const val UPLOAD_DIR = ".pigo/uploads"
    }
}

// ---------------- 导出（E1、E2） ----------------

enum class ExportFormat(val extension: String, val mimeType: String) {
    HTML("html", "text/html"),
    MARKDOWN("md", "text/markdown"),
    JSONL("jsonl", "application/x-ndjson"),
}

interface SessionExporter {
    /** 导出到宿主缓存目录（供分享或另存），返回宿主文件。HTML 走引擎 `export_html`。 */
    suspend fun export(sessionGuestPath: String, format: ExportFormat): Outcome<File>
}
