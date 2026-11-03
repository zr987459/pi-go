package io.github.zr987459.pigo.contract.fake

import io.github.zr987459.pigo.contract.Attachment
import io.github.zr987459.pigo.contract.AttachmentProcessor
import io.github.zr987459.pigo.contract.DiffHunk
import io.github.zr987459.pigo.contract.DiffLine
import io.github.zr987459.pigo.contract.DiffLineKind
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExportFormat
import io.github.zr987459.pigo.contract.FileDiff
import io.github.zr987459.pigo.contract.FileNode
import io.github.zr987459.pigo.contract.FilePreview
import io.github.zr987459.pigo.contract.GitFileChange
import io.github.zr987459.pigo.contract.GitStatus
import io.github.zr987459.pigo.contract.GitWorktree
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SessionExporter
import io.github.zr987459.pigo.contract.TextChunk
import io.github.zr987459.pigo.contract.WorkspaceFs
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.Base64

/** 规范化容器内路径；含 `..` 越出根目录时返回 null。 */
internal fun normalizeGuestPath(path: String): String? {
    if (!path.startsWith("/")) return null
    val parts = ArrayDeque<String>()
    for (p in path.split('/')) {
        when (p) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) return null else parts.removeLast()
            else -> parts.addLast(p)
        }
    }
    return "/" + parts.joinToString("/")
}

/**
 * 内存文件系统：路径 → 内容（目录以 `/` 结尾的 key 或由文件路径隐式推出）。
 * 夹具：`/root/pi-cwd/README.md`、`src/main.kt`、`.hidden`、`logo.png`，以及一个 git 仓库状态。
 */
class FakeWorkspaceFs(
    files: Map<String, ByteArray> = FIXTURE_FILES,
    private val hostRoot: File = File(System.getProperty("java.io.tmpdir"), "pigo-fake-rootfs"),
) : WorkspaceFs {
    private val files = LinkedHashMap(files)
    private val dirs = mutableSetOf("/")
    private val now = Instant.parse("2026-11-03T06:00:00Z")

    init { files.keys.forEach(::addParents) }

    private fun addParents(path: String) {
        var p = path.substringBeforeLast('/', "")
        while (p.isNotEmpty()) { dirs += p; p = p.substringBeforeLast('/', "") }
    }

    private fun node(path: String): FileNode? {
        val name = path.substringAfterLast('/').ifEmpty { "/" }
        return when {
            path in dirs -> FileNode(path, name, isDir = true, modifiedAt = now)
            path in files -> FileNode(path, name, isDir = false, sizeBytes = files.getValue(path).size.toLong(), modifiedAt = now, mimeType = mimeOf(name))
            else -> null
        }
    }

    private fun bad(path: String) = Outcome.Err(ErrCode.UNKNOWN, "路径无效或越界：$path")

    override suspend fun roots(sessionCwd: String?): List<FileNode> =
        listOfNotNull(sessionCwd?.let(::node), node("/root")).distinctBy { it.guestPath }

    override suspend fun list(guestDir: String, showHidden: Boolean): Outcome<List<FileNode>> {
        val dir = normalizeGuestPath(guestDir) ?: return bad(guestDir)
        if (dir !in dirs) return Outcome.Err(ErrCode.UNKNOWN, "目录不存在：$dir")
        val prefix = if (dir == "/") "/" else "$dir/"
        val children = (dirs + files.keys)
            .filter { it != dir && it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
            .mapNotNull(::node)
            .filter { showHidden || !it.hidden }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        return Outcome.Ok(children)
    }

    override suspend fun stat(guestPath: String): Outcome<FileNode> {
        val p = normalizeGuestPath(guestPath) ?: return bad(guestPath)
        return node(p)?.let { Outcome.Ok(it) } ?: Outcome.Err(ErrCode.UNKNOWN, "不存在：$p")
    }

    override suspend fun readText(guestPath: String, maxBytes: Long): Outcome<TextChunk> {
        val p = normalizeGuestPath(guestPath) ?: return bad(guestPath)
        val bytes = files[p] ?: return Outcome.Err(ErrCode.UNKNOWN, "不存在：$p")
        val cut = bytes.size > maxBytes
        val text = String(if (cut) bytes.copyOf(maxBytes.toInt()) else bytes, Charsets.UTF_8)
        return Outcome.Ok(TextChunk(text, cut, bytes.size.toLong(), languageOf(p)))
    }

    override suspend fun preview(guestPath: String): Outcome<FilePreview> {
        val n = when (val r = stat(guestPath)) { is Outcome.Ok -> r.value; is Outcome.Err -> return r }
        if (n.isDir) return Outcome.Ok(FilePreview.Directory(n, (list(n.guestPath, true) as Outcome.Ok).value))
        val mime = n.mimeType.orEmpty()
        return when {
            mime.startsWith("image/") -> hostFile(n.guestPath).let { if (it is Outcome.Ok) Outcome.Ok(FilePreview.Image(n, it.value)) else it as Outcome.Err }
            mime.startsWith("text/") || languageOf(n.guestPath) != null ->
                Outcome.Ok(FilePreview.Text(n, (readText(n.guestPath) as Outcome.Ok).value, markdown = n.name.endsWith(".md")))
            else -> Outcome.Ok(FilePreview.Binary(n))
        }
    }

    override suspend fun hostFile(guestPath: String): Outcome<File> {
        val p = normalizeGuestPath(guestPath) ?: return bad(guestPath)
        val bytes = files[p] ?: return Outcome.Err(ErrCode.UNKNOWN, "不存在：$p")
        val f = File(hostRoot, p.removePrefix("/"))
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return Outcome.Ok(f)
    }

    override suspend fun mkdirs(guestDir: String): Outcome<FileNode> {
        val p = normalizeGuestPath(guestDir) ?: return bad(guestDir)
        dirs += p; addParents(p)
        return Outcome.Ok(node(p)!!)
    }

    override suspend fun importStream(fileName: String, input: InputStream, targetDir: String): Outcome<FileNode> {
        val dir = normalizeGuestPath(targetDir) ?: return bad(targetDir)
        if (fileName.isBlank() || fileName.contains('/')) return bad(fileName)
        dirs += dir; addParents(dir)
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "").let { if (it.isEmpty() || it == fileName) "" else ".$it" }
        var candidate = "$dir/$fileName"
        var i = 1
        while (candidate in files) candidate = "$dir/$base ($i)$ext".also { i++ }
        files[candidate] = input.readBytes()
        return Outcome.Ok(node(candidate)!!)
    }

    override suspend fun exportTo(guestPath: String, output: OutputStream): Outcome<Long> {
        val p = normalizeGuestPath(guestPath) ?: return bad(guestPath)
        val bytes = files[p] ?: return Outcome.Err(ErrCode.UNKNOWN, "不存在：$p")
        output.write(bytes)
        return Outcome.Ok(bytes.size.toLong())
    }

    override suspend fun gitStatus(cwd: String): Outcome<GitStatus> =
        if (cwd == FIXTURE_CWD) Outcome.Ok(
            GitStatus(
                isRepo = true, branch = "main", upstream = "origin/main", ahead = 1,
                files = listOf(GitFileChange("src/main.kt", '.', 'M'), GitFileChange("notes.txt", '?', '?')),
            ),
        ) else Outcome.Ok(GitStatus(isRepo = false))

    override suspend fun gitDiff(cwd: String, path: String?, staged: Boolean): Outcome<List<FileDiff>> {
        if (cwd != FIXTURE_CWD) return Outcome.Ok(emptyList())
        val diff = FileDiff(
            "src/main.kt",
            hunks = listOf(
                DiffHunk(
                    "@@ -1,2 +1,2 @@",
                    listOf(
                        DiffLine(DiffLineKind.CONTEXT, "fun main() {", 1, 1),
                        DiffLine(DiffLineKind.DELETE, "    println(\"hi\")", 2, null),
                        DiffLine(DiffLineKind.ADD, "    println(\"你好\")", null, 2),
                    ),
                ),
            ),
            added = 1, deleted = 1,
        )
        return Outcome.Ok(if (path == null || path == diff.path) listOf(diff) else emptyList())
    }

    override suspend fun gitWorktrees(cwd: String): Outcome<List<GitWorktree>> =
        Outcome.Ok(if (cwd == FIXTURE_CWD) listOf(GitWorktree(FIXTURE_CWD, "main", "abc1234")) else emptyList())

    companion object {
        const val FIXTURE_CWD = "/root/pi-cwd"

        val FIXTURE_FILES: Map<String, ByteArray> = mapOf(
            "$FIXTURE_CWD/README.md" to "# 示例\n\n这是假的工作区。\n".toByteArray(),
            "$FIXTURE_CWD/src/main.kt" to "fun main() {\n    println(\"你好\")\n}\n".toByteArray(),
            "$FIXTURE_CWD/.hidden" to "secret-ish\n".toByteArray(),
            "$FIXTURE_CWD/logo.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            "/root/.bashrc" to "# bashrc\n".toByteArray(),
        )

        fun mimeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
            "md", "txt", "log" -> "text/plain"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            else -> null
        }

        fun languageOf(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "ts", "tsx" -> "typescript"
            "js", "mjs" -> "javascript"
            "py" -> "python"
            "md" -> "markdown"
            "json" -> "json"
            "sh" -> "bash"
            "txt", "log" -> "text"
            else -> null
        }
    }
}

/** 假附件处理：图片不真压缩，只做大小检查与 base64；文件写入 [fs] 的上传目录。 */
class FakeAttachmentProcessor(private val fs: FakeWorkspaceFs = FakeWorkspaceFs(), private val today: String = "2026-11-03") : AttachmentProcessor {
    override suspend fun prepareImage(input: InputStream, mimeType: String?, displayName: String?, maxEdgePx: Int, maxBytes: Long): Outcome<ImageInput> {
        val bytes = input.readBytes()
        if (bytes.size > maxBytes) return Outcome.Err(ErrCode.UNKNOWN, "图片超过 ${maxBytes / 1024 / 1024} MB")
        return Outcome.Ok(ImageInput(mimeType ?: "image/jpeg", Base64.getEncoder().encodeToString(bytes), displayName = displayName))
    }

    override suspend fun importFile(fileName: String, input: InputStream, cwd: String, maxBytes: Long): Outcome<Attachment.FileRef> {
        val bytes = input.readBytes()
        if (bytes.size > maxBytes) return Outcome.Err(ErrCode.UNKNOWN, "文件超过 ${maxBytes / 1024 / 1024} MB")
        return when (val r = fs.importStream(fileName, bytes.inputStream(), "$cwd/${AttachmentProcessor.UPLOAD_DIR}/$today")) {
            is Outcome.Ok -> Outcome.Ok(Attachment.FileRef(r.value))
            is Outcome.Err -> r
        }
    }
}

/** 假导出：在临时目录写一个固定内容的文件。 */
class FakeSessionExporter(private val outDir: File = File(System.getProperty("java.io.tmpdir"), "pigo-fake-export")) : SessionExporter {
    override suspend fun export(sessionGuestPath: String, format: ExportFormat): Outcome<File> {
        outDir.mkdirs()
        val name = sessionGuestPath.substringAfterLast('/').substringBeforeLast('.') + "." + format.extension
        val f = File(outDir, name)
        f.writeText(
            when (format) {
                ExportFormat.HTML -> "<html><body><h1>假会话</h1></body></html>"
                ExportFormat.MARKDOWN -> "# 假会话\n\n**你**：你好\n\n**pi**：你好！\n"
                ExportFormat.JSONL -> "{\"type\":\"session\"}\n"
            },
        )
        return Outcome.Ok(f)
    }
}
