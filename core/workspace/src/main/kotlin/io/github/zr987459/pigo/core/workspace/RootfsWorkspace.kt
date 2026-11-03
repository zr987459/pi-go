package io.github.zr987459.pigo.core.workspace

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.FileDiff
import io.github.zr987459.pigo.contract.FileNode
import io.github.zr987459.pigo.contract.FilePreview
import io.github.zr987459.pigo.contract.GitFileChange
import io.github.zr987459.pigo.contract.GitStatus
import io.github.zr987459.pigo.contract.GitWorktree
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.TextChunk
import io.github.zr987459.pigo.contract.WorkspaceFs
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
 * [WorkspaceFs] 的真实实现：宿主直接读写 rootfs（不经 pi，引擎没起来也能浏览），git 经容器内 `git -C` 执行。
 *
 * 允许访问的范围：`/root` 与调用方给过的会话 cwd（[roots]、[gitStatus]、[allowRoot]）。路径解析与越界检查见 [GuestPaths]。
 * 所有 IO 在 [io] 上执行，不阻塞主线程。
 *
 * @param rootfs 宿主上的 rootfs 目录（`runtime.hostFile("/")`）
 * @param exec 在容器内执行命令（`RuntimeManager.exec`）
 */
class RootfsWorkspace(
    private val rootfs: File,
    private val exec: suspend (cmd: List<String>, timeoutMs: Long) -> ExecResult,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : WorkspaceFs {
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

    // ------------------------------------------------------------------ git

    override suspend fun gitStatus(cwd: String): Outcome<GitStatus> = guarded {
        // 只检查不扩大：cwd 必须已由 roots(sessionCwd) 或装配层 allowRoot 登记过，否则 resolve 抛 PathDenied。
        val dir = paths().resolve(cwd)
        val top = git(dir, "rev-parse", "--show-toplevel")
        if (top.exitCode != 0) return@guarded GitStatus(isRepo = false)
        val r = git(dir, "status", "--porcelain=v2", "-z", "--branch", "--untracked-files=all")
        if (r.exitCode != 0) throw IOException(gitError(r))
        val s = GitParse.porcelainV2(r.stdout)
        // porcelain 的路径相对仓库根；换成相对 cwd 的显示需要仓库根与 cwd 的关系，这里保留仓库根相对路径并附上仓库根。
        GitStatus(
            isRepo = true,
            branch = s.branch,
            upstream = s.upstream,
            ahead = s.ahead,
            behind = s.behind,
            files = s.entries.filter { it.kind != GitParse.Kind.IGNORED }.map { GitFileChange(it.path, it.index, it.worktree, it.originalPath) },
        )
    }

    /** 仓库根（容器路径）；不是仓库返回 null。 */
    suspend fun gitRoot(cwd: String): String? = withContext(io) {
        runCatching {
            val r = git(paths().resolve(cwd), "rev-parse", "--show-toplevel")
            r.stdout.trim().takeIf { r.exitCode == 0 && it.startsWith("/") }
        }.getOrNull()
    }

    /** [path]：相对仓库根的路径（GitStatus.files 里的形式）或容器绝对路径。 */
    override suspend fun gitDiff(cwd: String, path: String?, staged: Boolean): Outcome<List<FileDiff>> = guarded {
        val dir = paths().resolve(cwd)
        val args = ArrayList(listOf("-c", "core.quotepath=off", "diff", "--no-color", "--no-ext-diff", "--unified=3"))
        if (staged) args += "--cached"
        // 相对路径按仓库根解析（与 GitStatus.files 的路径一致），绝对路径原样交给 git。
        if (path != null) { args += "--"; args += if (path.startsWith("/")) path else ":(top)$path" }
        val r = git(dir, *args.toTypedArray(), timeoutMs = 30_000)
        if (r.exitCode != 0) throw IOException(gitError(r))
        // GuestRunner 的 stdout 上限是「字节」(maxOutputBytes = 1 MB)，drain 到顶即切断，最后一个文件可能不全。
        // 因此按 UTF-8 字节数判断是否贴近上限：r.stdout.length 是 UTF-16 码元数，中文等多字节内容每字约占 3 字节、
        // 却只算 1 个 length，永远到不了阈值 → 大 diff 检测不到（第 2 轮回归）。改用 stdout 的 UTF-8 字节数。
        val nearLimit = r.stdout.toByteArray(Charsets.UTF_8).size >= DIFF_TRUNCATE_THRESHOLD
        if (nearLimit) {
            val parsed = GitParse.unifiedDiff(r.stdout).map(::toContract)
            // 多文件时丢掉可能被切断的最后一个；单文件（界面都是按文件查看）就保留它，展示大部分优于什么都不给。
            throw DiffTooLargeException(if (parsed.size > 1) parsed.dropLast(1) else parsed)
        }
        var diffs = GitParse.unifiedDiff(r.stdout).map(::toContract)
        // 未跟踪文件 git diff 不输出：只有确认「未跟踪且未被忽略」时才按「全部新增」生成（参考 Pi-Web createAddedFilePatch）。
        // 干净的已跟踪文件、只有暂存改动的文件、被 .gitignore 忽略的文件都返回空表。
        if (diffs.isEmpty() && path != null && !staged) {
            val top = gitRoot(cwd) ?: return@guarded diffs
            val rel = repoRelative(top, path) ?: return@guarded diffs
            // --full-name：无论 cwd 在仓库哪个子目录，ls-files 都输出相对仓库根的路径，才能和 rel 比对
            // （否则 cwd 为子目录时输出相对 cwd 的 ../new.kt、n2.kt，永远匹配不上，回归为 diff 全空）。
            val untracked = git(dir, "ls-files", "--others", "--exclude-standard", "--full-name", "-z", "--", ":(top,literal)$rel")
            if (untracked.exitCode != 0 || untracked.stdout.split('\u0000').none { it == rel }) return@guarded diffs
            val f = runCatching { paths().hostFile(GuestPaths.child(top, rel)) }.getOrNull()
            if (f != null && f.isFile && f.length() <= WorkspaceFs.DEFAULT_MAX_TEXT_BYTES && !looksBinary(f)) {
                diffs = GitParse.unifiedDiff(GitParse.addedFilePatch(rel, f.readText())).map(::toContract)
            }
        }
        diffs
    }

    override suspend fun gitWorktrees(cwd: String): Outcome<List<GitWorktree>> = guarded {
        val r = git(paths().resolve(cwd), "worktree", "list", "--porcelain")
        if (r.exitCode != 0) return@guarded emptyList()
        parseWorktrees(r.stdout)
    }

    private suspend fun git(dir: String, vararg args: String, timeoutMs: Long = 15_000): ExecResult =
        exec(listOf("git", "-C", dir) + args, timeoutMs)

    private fun gitError(r: ExecResult) = if (r.timedOut) "git 执行超时" else "git 执行失败：" + r.stderr.trim().lineSequence().firstOrNull().orEmpty()

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

        /** 略低于 GuestRunner 的 1 MB 输出上限，单位为 UTF-8 字节（与 maxOutputBytes 同一单位）；留 4 KB 余量，宁可多报截断。 */
        internal const val DIFF_TRUNCATE_THRESHOLD = (1 shl 20) - 4096

        /**
         * 把 [path]（相对仓库根，或容器绝对路径）换成相对仓库根 [top] 的路径；在仓库外或含 `..` 返回 null。
         * diff 头与 `ls-files` 都用这个相对路径。
         */
        internal fun repoRelative(top: String, path: String): String? {
            val rel = if (path.startsWith("/")) {
                val norm = runCatching { GuestPaths.normalize(path) }.getOrNull() ?: return null
                val t = top.trimEnd('/')
                if (t.isEmpty()) norm.removePrefix("/") else if (norm.startsWith("$t/")) norm.removePrefix("$t/") else return null
            } else path
            if (rel.isEmpty() || rel.split('/').any { it == ".." || it.isEmpty() }) return null
            return rel
        }

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

        internal fun toContract(d: GitParse.FileDiff): FileDiff = FileDiff(
            path = d.path,
            oldPath = d.oldPath?.takeIf { it != d.newPath },
            hunks = d.hunks.map { h ->
                io.github.zr987459.pigo.contract.DiffHunk(h.header, h.lines.map { l ->
                    io.github.zr987459.pigo.contract.DiffLine(
                        kind = when (l.type) {
                            GitParse.LineType.ADD -> io.github.zr987459.pigo.contract.DiffLineKind.ADD
                            GitParse.LineType.DEL -> io.github.zr987459.pigo.contract.DiffLineKind.DELETE
                            GitParse.LineType.CONTEXT -> io.github.zr987459.pigo.contract.DiffLineKind.CONTEXT
                            GitParse.LineType.NO_NEWLINE -> io.github.zr987459.pigo.contract.DiffLineKind.META
                        },
                        text = l.text,
                        oldNo = l.oldNo,
                        newNo = l.newNo,
                    )
                })
            },
            binary = d.binary,
            added = d.additions,
            deleted = d.deletions,
        )

        internal fun parseWorktrees(out: String): List<GitWorktree> {
            val res = ArrayList<GitWorktree>()
            var path: String? = null
            var head: String? = null
            var branch: String? = null
            fun flush() { path?.let { res += GitWorktree(it, branch, head) }; path = null; head = null; branch = null }
            for (line in out.lines()) {
                when {
                    line.isBlank() -> flush()
                    line.startsWith("worktree ") -> { flush(); path = line.removePrefix("worktree ") }
                    line.startsWith("HEAD ") -> head = line.removePrefix("HEAD ")
                    line.startsWith("branch ") -> branch = line.removePrefix("branch ").removePrefix("refs/heads/")
                    line == "detached" -> branch = null
                }
            }
            flush()
            return res
        }
    }
}

/**
 * diff 输出超过 GuestRunner 的 1 MB 字节上限被截断。[partial] 是完整解析出的文件：
 * 多文件时已丢掉可能不全的最后一个，单文件时保留（界面本就按文件查看，展示大部分优于空白）。
 * 契约 [FileDiff] 没有截断标志（契约冻结），故目前只能经此异常以 [message] 提示；
 * 让 partial 随 Ok 返回需给契约加截断字段，另开 PR。
 */
class DiffTooLargeException(val partial: List<FileDiff>) : IOException(
    if (partial.size <= 1) "改动过大，已超过 1 MB 显示上限被截断，下面只是其中一部分" else "改动过大，已截断（只显示前 ${partial.size} 个文件）；请按文件查看",
)

class TooLargeException(val limit: Long) : IOException("文件超过 ${limit / (1024 * 1024)} MB，无法导入")
