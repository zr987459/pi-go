package io.github.zr987459.pigo.core.runtime.util

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * rootfs 里满是指向容器绝对路径的符号链接（如 `/bin -> usr/bin`、`x -> /usr/lib/...`），在宿主侧它们指向
 * 宿主的同名路径。这里的操作一律**不跟随符号链接**，避免删到/改到 rootfs 之外的文件。
 */
object SafeFs {
    /** 递归删除，不跟随符号链接（链接本身被删除）。不存在时什么都不做。 */
    fun deleteTree(f: File) {
        val root = f.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(root)
            return
        }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                // 删除前确保可写，否则只读目录里的项删不掉
                dir.toFile().setWritable(true, true)
                dir.toFile().setExecutable(true, true)
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** 原子替换为符号链接：先建临时链接再 rename 覆盖。 */
    fun replaceSymlink(link: File, target: String) {
        link.parentFile?.mkdirs()
        val tmp = File(link.parentFile, ".${link.name}.tmp-link")
        Files.deleteIfExists(tmp.toPath())
        Files.createSymbolicLink(tmp.toPath(), Paths.get(target))
        try {
            Files.move(tmp.toPath(), link.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            if (Files.isDirectory(link.toPath(), LinkOption.NOFOLLOW_LINKS)) throw e
            Files.deleteIfExists(link.toPath())
            Files.move(tmp.toPath(), link.toPath())
        }
    }

    fun readSymlink(link: File): String? =
        if (Files.isSymbolicLink(link.toPath())) Files.readSymbolicLink(link.toPath()).toString() else null

    /** 移动（同一文件系统内 rename）；目标已存在时报错，绝不覆盖用户数据。 */
    fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (Files.exists(to.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("目标已存在，拒绝覆盖：$to")
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    fun exists(f: File) = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)
}
