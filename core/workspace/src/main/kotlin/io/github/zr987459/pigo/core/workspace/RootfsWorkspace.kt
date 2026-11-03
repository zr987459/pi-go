package io.github.zr987459.pigo.core.workspace

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.FileDiff
import io.github.zr987459.pigo.contract.FileNode
import io.github.zr987459.pigo.contract.FilePreview
import io.github.zr987459.pigo.contract.GitStatus
import io.github.zr987459.pigo.contract.GitWorktree
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.TextChunk
import io.github.zr987459.pigo.contract.WorkspaceFs
import io.github.zr987459.pigo.contract.workspace.FileMatch
import io.github.zr987459.pigo.contract.workspace.WorkspaceSearch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.concurrent.CopyOnWriteArraySet

/**
 * [WorkspaceSearch]（DESIGN-r3 §1.2：`@` 模糊搜索、读文件、branchName()、只列目录）与旧 [WorkspaceFs] 的真实实现：
 * 宿主直接读写 rootfs（不经 pi，引擎没起来也能用）。附件导入（[importInto]）与导出仍在用；浏览器、预览由 r3/app 删除后
 * 契约里的旧方法随之移除。Git 页与 GitParse 已删除（§1.1 X5），git* 方法只剩兼容存根。
 *
 * 允许访问的范围：`/root` 与调用方给过的会话 cwd（[roots]、[allowRoot]）。路径解析与越界检查见 [GuestPaths]。
 * 所有 IO 在 [io] 上执行，不阻塞主线程。
 *
 * @param rootfs 宿主上的 rootfs 目录（`runtime.hostFile("/")`）
 * @param exec 在容器内执行命令（`RuntimeManager.exec`）
 */
class RootfsWorkspace(
    private val rootfs: File,
    private val exec: suspend (cmd: List<String>, timeoutMs: Long) -> ExecResult,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : WorkspaceFs, WorkspaceSearch {
    private val extraRoots = CopyOnWriteArraySet<String>()

    /** 允许访问 [guestDir] 及其子路径（新会话 cwd、Share Target 的落地目录等）。 */
    fun allowRoot(guestDir: String) {
        runCatching { GuestPaths.normalize(guestDir) }.getOrNull()?.let { extraRoots += it }
    }

    internal fun paths(): GuestPaths = GuestPaths(rootfs, listOf(HOME) + extraRoots)

    override suspend fun roots(sessionCwd: String?): List<FileNode> = withContext(io) {
        val list = ArrayList<String>()
        if (sessionCwd != null && runCatching { GuestPaths.normalize(sessionCwd) }.isSuccess) {
            allowRoot(sessionCwd)
            list += GuestPaths.normalize(sessionCwd)
        }
        if (HOME !in list) list += HOME
        list.mapNotNull { p -> runCatching { nodeOf(paths(), p, p) }.getOrNull() }
    }

    override suspend fun list(guestDir: String, showHidden: Boolean): Outcome<List<FileNode>> = guarded {
        val ps = paths()
        val real = ps.resolve(guestDir)
        val dir = ps.hostFile(guestDir)
        if (!dir.isDirectory) throw IOException("不是目录：$guestDir")
        val names = dir.list() ?: throw IOException("无法读取目录：$guestDir")
        val base = GuestPaths.normalize(guestDir)
        names.asSequence()
            .filter { showHidden || !it.startsWith(".") }
            .mapNotNull { n -> runCatching { childNode(ps, base, real, n) }.getOrNull() }
            .sortedWith(compareByDescending<FileNode> { it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }

    override suspend fun stat(guestPath: String): Outcome<FileNode> = guarded {
        val ps = paths()
        val norm = GuestPaths.normalize(guestPath)
        nodeOf(ps, norm, ps.resolve(norm))
    }

    override suspend fun readText(guestPath: String, maxBytes: Long): Outcome<TextChunk> = guarded {
        val ps = paths()
        val f = ps.hostFile(guestPath)
        if (f.isDirectory) throw IOException("是目录：$guestPath")
        readChunk(f, maxBytes, FileTypes.language(guestPath))
    }

    override suspend fun preview(guestPath: String): Outcome<FilePreview> = guarded {
        val ps = paths()
        val norm = GuestPaths.normalize(guestPath)
        val real = ps.resolve(norm)
        val node = nodeOf(ps, norm, real)
        val f = ps.hostFile(norm)
        when {
            node.isDir -> {
                val children = (f.list() ?: emptyArray()).mapNotNull { n -> runCatching { childNode(ps, norm, real, n) }.getOrNull() }
                    .sortedWith(compareByDescending<FileNode> { it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                FilePreview.Directory(node, children)
            }
            FileTypes.isImage(norm) && node.sizeBytes <= FileTypes.IMAGE_PREVIEW_MAX_BYTES -> FilePreview.Image(node, f)
            FileTypes.isExternal(norm) -> FilePreview.External(node, f)
            else -> {
                val chunk = readChunk(f, WorkspaceFs.DEFAULT_MAX_TEXT_BYTES, FileTypes.language(norm))
                if (chunk.text.isEmpty() && node.sizeBytes > 0 && looksBinary(f)) FilePreview.Binary(node)
                else FilePreview.Text(node, chunk, markdown = FileTypes.isMarkdown(norm))
            }
        }
    }

    override suspend fun hostFile(guestPath: String): Outcome<File> = guarded { paths().hostFile(guestPath) }

    override suspend fun mkdirs(guestDir: String): Outcome<FileNode> = guarded {
        val ps = paths()
        val norm = GuestPaths.normalize(guestDir)
        val f = ps.hostFile(norm)
        if (!f.isDirectory && !f.mkdirs()) throw IOException("无法创建目录：$norm")
        nodeOf(ps, norm, ps.resolve(norm))
    }

    override suspend fun importStream(fileName: String, input: InputStream, targetDir: String): Outcome<FileNode> = guarded {
        importInto(fileName, input, targetDir, Long.MAX_VALUE)
    }

    /** 写入 [targetDir]；超过 [maxBytes] 删掉半成品并抛 [TooLargeException]。先写 `.part` 再改名，中途失败不留残文件。 */
    internal fun importInto(fileName: String, input: InputStream, targetDir: String, maxBytes: Long): FileNode {
        val ps = paths()
        val dirGuest = GuestPaths.normalize(targetDir)
        val dir = ps.hostFile(dirGuest)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建目录：$dirGuest")
        val name = FileNames.sanitize(fileName)
        val tmp = File(dir, ".$name.${System.nanoTime()}.part")
        try {
            var total = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw TooLargeException(maxBytes)
                    out.write(buf, 0, n)
                }
            }
            val target = synchronized(this) {
                val t = FileNames.unique(dir, name)
                if (!tmp.renameTo(t)) throw IOException("保存文件失败：${t.name}")
                t
            }
            val guest = GuestPaths.child(dirGuest, target.name)
            return nodeOf(ps, guest, ps.resolve(guest))
        } finally {
            tmp.delete()
        }
    }

    override suspend fun exportTo(guestPath: String, output: OutputStream): Outcome<Long> = guarded {
        val f = paths().hostFile(guestPath)
        if (!f.isFile) throw IOException("不是文件：$guestPath")
        f.inputStream().use { it.copyTo(output) }
    }

    // ------------------------------------------------------------------ git（已删除，DESIGN-r3 §1.1 X5）

    /**
     * Git 页已按 DESIGN-r3 §1.1 X5 删除（pi 没有）；GitParse 与 diff/worktree 实现一并删除。
     * 契约 [WorkspaceFs] 冻结（r3/core 只新增文件），旧调用方（feature/files 的 Git 页，r3/app 删除）在那之前仍能编译：
     * 只回答「是不是仓库 + 当前分支名」，不再列文件改动。
     */
    @Deprecated("r3: r3/app 删除调用方（GitScreens / FilesController）；页脚分支名用 branchName()")
    override suspend fun gitStatus(cwd: String): Outcome<GitStatus> = guarded {
        paths().resolve(cwd) // 只检查不扩大：未登记的 cwd 抛 PathDenied
        val b = branchNameBlocking(cwd)
        if (b == null) GitStatus(isRepo = false) else GitStatus(isRepo = true, branch = b)
    }

    @Deprecated("r3: Git diff 已删除（DESIGN-r3 §1.1 X5），r3/app 删除调用方")
    override suspend fun gitDiff(cwd: String, path: String?, staged: Boolean): Outcome<List<FileDiff>> =
        Outcome.Err(ErrCode.UNKNOWN, GIT_REMOVED)

    @Deprecated("r3: worktree 列表已删除（DESIGN-r3 §1.1 X5），r3/app 删除调用方")
    override suspend fun gitWorktrees(cwd: String): Outcome<List<GitWorktree>> =
        Outcome.Err(ErrCode.UNKNOWN, GIT_REMOVED)

    // ------------------------------------------------------------------ WorkspaceSearch（DESIGN-r3 §1.2）

    override suspend fun searchFiles(cwd: String, query: String, limit: Int): Outcome<List<FileMatch>> = guarded {
        val ps = paths()
        val base = GuestPaths.normalize(cwd)
        val dir = ps.hostFile(base)
        if (!dir.isDirectory) throw IOException("不是目录：$cwd")
        val rootCanon = runCatching { rootfs.canonicalPath }.getOrDefault(rootfs.path)
        val allowedHost = ps.allowedRoots.map { r -> runCatching { ps.hostFile(r).canonicalPath }.getOrNull() }.filterNotNull()
        val inAllowed: (File) -> Boolean = { f ->
            val c = f.path
            (c == rootCanon || c.startsWith("$rootCanon/")) && allowedHost.any { a -> c == a || c.startsWith("$a/") }
        }
        FileSearch.search(dir, query, limit, inAllowed).map { m ->
            FileMatch(
                guestPath = GuestPaths.child(base, m.path),
                path = m.path,
                name = m.path.substringAfterLast('/'),
                isDir = m.isDir,
                score = m.score,
                completion = FileSearch.completion(m.path, m.isDir),
            )
        }
    }

    override suspend fun readFile(guestPath: String, maxBytes: Long): Outcome<TextChunk> = readText(guestPath, maxBytes)

    override suspend fun branchName(cwd: String): String? = withContext(io) { runCatching { branchNameBlocking(cwd) }.getOrNull() }

    override suspend fun listDirectories(guestDir: String, showHidden: Boolean): Outcome<List<FileNode>> =
        when (val r = list(guestDir, showHidden)) {
            is Outcome.Ok -> Outcome.Ok(r.value.filter { it.isDir })
            is Outcome.Err -> r
        }

    /**
     * 与 pi 页脚（`footer-data-provider.js` 的 `findGitPaths` + `resolveGitBranchSync`）相同：从 cwd 往上找 `.git`
     * （目录，或 worktree 的 `gitdir:` 文件），读 HEAD：`ref: refs/heads/<名>` → 名；其他 → `"detached"`；
     * reftable 仓库（`.invalid`）用 `git symbolic-ref` 兜底。不是仓库返回 null。只读 rootfs 里的文件。
     */
    private suspend fun branchNameBlocking(cwd: String): String? {
        val ps = paths()
        var dir: String? = GuestPaths.normalize(cwd)
        while (dir != null) {
            val git = ps.rawHost(GuestPaths.child(dir, ".git").let { runCatching { ps.resolve(it) }.getOrDefault(it) })
            if (git.exists()) {
                val head: File = if (git.isFile) {
                    val content = runCatching { git.readText().trim() }.getOrNull() ?: return null
                    if (!content.startsWith("gitdir: ")) return null
                    val gd = content.removePrefix("gitdir: ").trim()
                    val gitDirGuest = if (gd.startsWith("/")) GuestPaths.normalize(gd) else GuestPaths.normalize("$dir/$gd")
                    ps.rawHost(gitDirGuest).resolve("HEAD")
                } else git.resolve("HEAD")
                if (!head.isFile) return null
                val content = runCatching { head.readText().trim() }.getOrNull() ?: return null
                if (!content.startsWith("ref: refs/heads/")) return "detached"
                val b = content.removePrefix("ref: refs/heads/")
                if (b != ".invalid") return b
                val r = runCatching { exec(listOf("git", "-C", dir, "--no-optional-locks", "symbolic-ref", "--quiet", "--short", "HEAD"), 5_000) }.getOrNull()
                val out = if (r != null && r.exitCode == 0) r.stdout.trim() else ""
                return out.ifEmpty { "detached" }
            }
            dir = GuestPaths.parent(dir)
        }
        return null
    }

    // ------------------------------------------------------------------ 内部

    private fun childNode(ps: GuestPaths, dirGuest: String, dirReal: String, name: String): FileNode {
        val guest = GuestPaths.child(dirGuest, name)
        val raw = ps.rawHost(GuestPaths.child(dirReal, name))
        val link = GuestPaths.isLink(raw)
        if (!link) return nodeFromHost(guest, name, raw, false)
        // 符号链接：能解析到允许范围内就按目标显示，否则显示为链接本身（不可打开）。
        val target = runCatching { ps.hostFile(guest) }.getOrNull()
        return if (target != null) nodeFromHost(guest, name, target, true)
        else FileNode(guest, name, isDir = false, sizeBytes = 0, modifiedAt = null, isSymlink = true, mimeType = null)
    }

    private fun nodeOf(ps: GuestPaths, guest: String, real: String): FileNode {
        val f = ps.hostFile(guest)
        if (!GuestPaths.existsNoFollow(f) && !f.exists()) throw java.io.FileNotFoundException("文件不存在：$guest")
        return nodeFromHost(guest, GuestPaths.name(guest), f, guest != real)
    }

    private fun nodeFromHost(guest: String, name: String, f: File, symlink: Boolean): FileNode {
        val attrs = runCatching { Files.readAttributes(f.toPath(), BasicFileAttributes::class.java) }.getOrNull()
        val isDir = attrs?.isDirectory ?: f.isDirectory
        return FileNode(
            guestPath = guest,
            name = name,
            isDir = isDir,
            sizeBytes = if (isDir) 0 else attrs?.size() ?: f.length(),
            modifiedAt = attrs?.lastModifiedTime()?.toMillis()?.let(Instant::ofEpochMilli),
            isSymlink = symlink,
            mimeType = if (isDir) null else FileTypes.mime(name),
        )
    }

    private suspend inline fun <T> guarded(crossinline block: suspend () -> T): Outcome<T> = withContext(io) {
        try {
            Outcome.Ok(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: PathDeniedException) {
            Outcome.Err(ErrCode.UNKNOWN, e.message ?: "无权访问", e)
        } catch (e: TooLargeException) {
            Outcome.Err(ErrCode.UNKNOWN, e.message ?: "文件过大", e)
        } catch (e: java.io.FileNotFoundException) {
            Outcome.Err(ErrCode.UNKNOWN, e.message ?: "文件不存在", e)
        } catch (e: IOException) {
            val disk = e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true
            Outcome.Err(if (disk) ErrCode.DISK_FULL else ErrCode.UNKNOWN, if (disk) "存储空间不足" else e.message ?: "读写失败", e)
        } catch (e: SecurityException) {
            Outcome.Err(ErrCode.UNKNOWN, "无权访问", e)
        }
    }

    companion object {
        const val HOME = "/root"

        internal const val GIT_REMOVED = "Git 页已删除（pi 没有此功能，DESIGN-r3 §1.1 X5）"

        /** 读前 [maxBytes]；按 UTF-8 解码，截断处不切开多字节字符。含 NUL 视为二进制，返回空文本。 */
        internal fun readChunk(f: File, maxBytes: Long, language: String?): TextChunk {
            val total = f.length()
            val n = minOf(total, maxBytes).toInt()
            val buf = ByteArray(n)
            var read = 0
            f.inputStream().use { inp ->
                while (read < n) {
                    val k = inp.read(buf, read, n - read)
                    if (k < 0) break
                    read += k
                }
            }
            var end = read
            if (total > read) end = utf8Boundary(buf, read)
            for (i in 0 until minOf(end, 8192)) if (buf[i] == 0.toByte()) return TextChunk("", truncated = total > read, totalBytes = total, language = language)
            return TextChunk(String(buf, 0, end, Charsets.UTF_8), truncated = total > read, totalBytes = total, language = language)
        }

        /** 回退到完整 UTF-8 字符的边界。 */
        internal fun utf8Boundary(b: ByteArray, len: Int): Int {
            var i = len
            var back = 0
            while (i > 0 && back < 4 && (b[i - 1].toInt() and 0xC0) == 0x80) { i--; back++ }
            if (i == 0) return len
            val lead = b[i - 1].toInt() and 0xFF
            val need = when {
                lead and 0x80 == 0 -> 1
                lead and 0xE0 == 0xC0 -> 2
                lead and 0xF0 == 0xE0 -> 3
                lead and 0xF8 == 0xF0 -> 4
                else -> 1
            }
            return if (back + 1 >= need) len else i - 1
        }

        internal fun looksBinary(f: File): Boolean = runCatching {
            f.inputStream().use { inp ->
                val b = ByteArray(8192)
                val n = inp.read(b)
                (0 until maxOf(n, 0)).any { b[it] == 0.toByte() }
            }
        }.getOrDefault(false)

    }
}

class TooLargeException(val limit: Long) : IOException("文件超过 ${limit / (1024 * 1024)} MB，无法导入")
