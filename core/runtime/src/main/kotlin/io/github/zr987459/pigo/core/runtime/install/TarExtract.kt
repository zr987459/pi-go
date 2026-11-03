package io.github.zr987459.pigo.core.runtime.install

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * 解压 `.tar.gz` 到目录（DESIGN.md §4.2 功能规格，基于 Apache Commons Compress 编写）。
 *
 * 规则：
 * - 条目路径一律相对目标根目录；去掉开头的 `/` 与 `./`；任何 `..` 分量都拒绝。
 * - 中间目录若是已解出的符号链接，按「目标根目录 = 容器的 /」解析：绝对目标从根重新开始，
 *   相对目标从链接所在目录开始；解析结果越出根目录即拒绝（防止经符号链接逃逸）。
 * - 符号链接本身原样创建（目标字符串不改写，容器内由 proot 解释）。
 * - 硬链接指向根目录内已解出的文件；创建失败（部分 ROM 的 SELinux 不允许）时改为复制。
 * - 保留权限位（含可执行位）；不处理属主；跳过设备文件与 FIFO。
 * - 解压总字节数超过 [maxTotalBytes] 或条目数超过 [maxEntries] 时中止。
 * - PAX / GNU 长文件名由 Commons Compress 处理。
 *
 * 调用方负责：只对已通过 SHA256 校验的文件调用；出错时清理目标目录（通常是 staging 目录）。
 */
class TarExtract(
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    class UnsafeEntryException(message: String) : IOException(message)
    class LimitExceededException(message: String) : IOException(message)

    data class Result(val files: Int, val dirs: Int, val symlinks: Int, val hardlinks: Int, val copiedHardlinks: Int, val bytes: Long)

    /**
     * @param stripComponents 去掉的前导路径分量数（Node 包的 `node-v24.x-linux-arm64/`）。
     * @param onBytes 已写出的累计字节数，用于进度。
     */
    fun extractTarGz(
        archive: File,
        dest: File,
        stripComponents: Int = 0,
        onBytes: (Long) -> Unit = {},
    ): Result = archive.inputStream().buffered().use { raw ->
        GzipCompressorInputStream(raw, true).use { gz -> extractTar(gz, dest, stripComponents, onBytes) }
    }

    fun extractTar(
        input: InputStream,
        dest: File,
        stripComponents: Int = 0,
        onBytes: (Long) -> Unit = {},
    ): Result {
        dest.mkdirs()
        val root: Path = dest.toPath().toAbsolutePath().normalize()
        var files = 0; var dirs = 0; var symlinks = 0; var hardlinks = 0; var copied = 0
        var total = 0L
        var entries = 0
        val dirModes = mutableListOf<Pair<Path, Int>>()

        TarArchiveInputStream(input, "UTF-8").use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextEntry ?: break
                if (++entries > maxEntries) throw LimitExceededException("条目数超过上限 $maxEntries")
                val parts = splitEntryName(entry.name, stripComponents) ?: continue
                if (parts.isEmpty()) continue

                when {
                    entry.isDirectory -> {
                        val dir = resolveParent(root, parts).resolve(parts.last())
                        ensureNotEscaping(root, dir)
                        if (Files.isSymbolicLink(dir)) Files.delete(dir)
                        Files.createDirectories(dir)
                        dirModes += dir to entry.mode
                        dirs++
                    }
                    entry.isSymbolicLink -> {
                        val link = resolveParent(root, parts).resolve(parts.last())
                        ensureNotEscaping(root, link)
                        deleteIfExists(link)
                        Files.createSymbolicLink(link, Paths.get(entry.linkName))
                        symlinks++
                    }
                    entry.isLink -> {
                        val link = resolveParent(root, parts).resolve(parts.last())
                        ensureNotEscaping(root, link)
                        val targetParts = splitEntryName(entry.linkName, stripComponents)
                            ?: throw UnsafeEntryException("硬链接目标不合法：${entry.name} -> ${entry.linkName}")
                        if (targetParts.isEmpty()) throw UnsafeEntryException("硬链接目标为空：${entry.name}")
                        val target = resolveFully(root, targetParts)
                        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                            throw UnsafeEntryException("硬链接目标不存在或不是普通文件：${entry.name} -> ${entry.linkName}")
                        }
                        deleteIfExists(link)
                        try {
                            Files.createLink(link, target)
                        } catch (e: Exception) {
                            val size = Files.size(target)
                            total += size
                            if (total > maxTotalBytes) throw LimitExceededException("解压总量超过上限 $maxTotalBytes 字节")
                            Files.copy(target, link, StandardCopyOption.COPY_ATTRIBUTES)
                            copied++
                        }
                        hardlinks++
                    }
                    entry.isFile -> {
                        val file = resolveParent(root, parts).resolve(parts.last())
                        ensureNotEscaping(root, file)
                        deleteIfExists(file)
                        Files.newOutputStream(file).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = tar.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > maxTotalBytes) throw LimitExceededException("解压总量超过上限 $maxTotalBytes 字节")
                                out.write(buf, 0, n)
                            }
                        }
                        applyMode(file, entry.mode)
                        onBytes(total)
                        files++
                    }
                    else -> Unit // 字符/块设备、FIFO：容器里由 proot 绑定 /dev，不需要
                }
            }
        }
        // 目录权限最后设置，避免先设成只读后写不进子项。
        for ((dir, mode) in dirModes.asReversed()) applyMode(dir, mode or 0b111_000_000)
        return Result(files, dirs, symlinks, hardlinks, copied, total)
    }

    companion object {
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 2L * 1024 * 1024 * 1024
        const val DEFAULT_MAX_ENTRIES: Int = 500_000
        private const val MAX_SYMLINK_HOPS = 40

        /** 规范化条目名为路径分量；`..` 或空字节直接拒绝；分量不足 [strip] 时返回 null（跳过）。 */
        internal fun splitEntryName(name: String, strip: Int): List<String>? {
            if (name.indexOf('\u0000') >= 0) throw UnsafeEntryException("条目名含空字节")
            val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.any { it == ".." }) throw UnsafeEntryException("条目路径含 ..：$name")
            if (parts.size < strip) return null
            return parts.drop(strip)
        }

        private fun ensureNotEscaping(root: Path, p: Path) {
            val n = p.toAbsolutePath().normalize()
            if (!n.startsWith(root)) throw UnsafeEntryException("路径越出目标目录：$p")
        }

        private fun deleteIfExists(p: Path) {
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                // 同名目录被文件/链接替换：只允许空目录
                try {
                    Files.delete(p)
                } catch (e: java.nio.file.DirectoryNotEmptyException) {
                    throw UnsafeEntryException("不能用文件覆盖非空目录：$p")
                }
            } else {
                Files.deleteIfExists(p)
            }
        }

        /**
         * 解析除最后一个分量以外的路径，跟随已存在的符号链接（按 rootfs 根解析），
         * 并创建缺失的中间目录。返回最后一个分量所在的真实目录（一定在 root 内）。
         */
        internal fun resolveParent(root: Path, parts: List<String>): Path {
            var cur = root
            var hops = 0
            val queue = ArrayDeque(parts.dropLast(1))
            while (queue.isNotEmpty()) {
                val name = queue.removeFirst()
                if (name == "..") {
                    if (cur == root) throw UnsafeEntryException("经符号链接越出目标目录")
                    cur = cur.parent
                    continue
                }
                val next = cur.resolve(name)
                if (Files.isSymbolicLink(next)) {
                    if (++hops > MAX_SYMLINK_HOPS) throw UnsafeEntryException("符号链接层数过多：$next")
                    val target = Files.readSymbolicLink(next).toString()
                    val targetParts = target.split('/').filter { it.isNotEmpty() && it != "." }
                    if (target.startsWith("/")) cur = root
                    for (i in targetParts.indices.reversed()) queue.addFirst(targetParts[i])
                    continue
                }
                if (Files.exists(next, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)) {
                    throw UnsafeEntryException("中间路径不是目录：$next")
                }
                if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Files.createDirectory(next)
                    } catch (_: FileAlreadyExistsException) {
                    }
                }
                cur = next
            }
            ensureNotEscaping(root, cur)
            return cur
        }

        /** 完整解析（包括最后一个分量若是符号链接也跟随），用于硬链接目标。 */
        internal fun resolveFully(root: Path, parts: List<String>): Path {
            var p = resolveParent(root, parts).resolve(parts.last())
            var hops = 0
            while (Files.isSymbolicLink(p)) {
                if (++hops > MAX_SYMLINK_HOPS) throw UnsafeEntryException("符号链接层数过多：$p")
                val target = Files.readSymbolicLink(p).toString()
                val base = if (target.startsWith("/")) emptyList() else root.relativize(p.parent).toString()
                    .split('/').filter { it.isNotEmpty() }
                val combined = base + target.split('/').filter { it.isNotEmpty() && it != "." }
                val normalized = ArrayList<String>()
                for (c in combined) {
                    if (c == "..") {
                        if (normalized.isEmpty()) throw UnsafeEntryException("经符号链接越出目标目录")
                        normalized.removeAt(normalized.size - 1)
                    } else {
                        normalized += c
                    }
                }
                if (normalized.isEmpty()) return root
                p = resolveParent(root, normalized).resolve(normalized.last())
            }
            ensureNotEscaping(root, p)
            return p
        }

        internal fun applyMode(p: Path, mode: Int) {
            val perms = HashSet<PosixFilePermission>()
            val bits = listOf(
                0b100_000_000 to PosixFilePermission.OWNER_READ,
                0b010_000_000 to PosixFilePermission.OWNER_WRITE,
                0b001_000_000 to PosixFilePermission.OWNER_EXECUTE,
                0b000_100_000 to PosixFilePermission.GROUP_READ,
                0b000_010_000 to PosixFilePermission.GROUP_WRITE,
                0b000_001_000 to PosixFilePermission.GROUP_EXECUTE,
                0b000_000_100 to PosixFilePermission.OTHERS_READ,
                0b000_000_010 to PosixFilePermission.OTHERS_WRITE,
                0b000_000_001 to PosixFilePermission.OTHERS_EXECUTE,
            )
            // App 自己必须能读写（属主位总是加上 rw），其余照搬。
            val m = mode or 0b110_000_000
            for ((bit, perm) in bits) if (m and bit != 0) perms += perm
            try {
                Files.setPosixFilePermissions(p, perms)
            } catch (e: UnsupportedOperationException) {
                val f = p.toFile()
                f.setReadable(true, false)
                f.setExecutable(m and 0b001_001_001 != 0, m and 0b000_001_001 == 0)
            }
        }
    }
}
