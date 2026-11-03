package io.github.zr987459.pigo.core.store

import java.io.File

/**
 * 容器路径 ↔ 宿主路径（DESIGN.md §2.2 `RuntimeManager.hostFile`、§3.8）。
 *
 * 容器内 `/root/x` 对应宿主 `files/rootfs/root/x`。本类不依赖 `:core:runtime`，
 * 只按同一规则换算，便于在 JVM 单测里用临时目录代替 rootfs。
 */
class GuestPaths(val rootfs: File) {

    val agentDir: File get() = File(rootfs, "root/.pi/agent")
    val sessionsDir: File get() = File(agentDir, "sessions")
    val trashDir: File get() = File(agentDir, "sessions-trash")
    val modelsJson: File get() = File(agentDir, "models.json")
    val settingsJson: File get() = File(agentDir, "settings.json")
    val authJson: File get() = File(agentDir, "auth.json")

    /** `/root/x` → 宿主文件。拒绝相对路径与 `..`。 */
    fun hostFile(guestPath: String): File {
        require(guestPath.startsWith("/")) { "不是容器内的绝对路径：$guestPath" }
        require(guestPath.split('/').none { it == ".." }) { "路径不能包含 ..：$guestPath" }
        return File(rootfs, guestPath.removePrefix("/"))
    }

    /** 宿主文件 → `/root/x`；不在 rootfs 下时返回 null。 */
    fun guestPath(host: File): String? {
        val root = rootfs.canonicalFile.path
        val p = host.canonicalFile.path
        if (p != root && !p.startsWith(root + File.separator)) return null
        return "/" + p.removePrefix(root).trimStart(File.separatorChar).replace(File.separatorChar, '/')
    }

    /** 宿主文件是否严格位于 [dir] 之下（按规范路径比较，防止 `..` 与符号链接越界）。 */
    fun isInside(host: File, dir: File): Boolean {
        val d = dir.canonicalFile.path + File.separator
        return host.canonicalFile.path.startsWith(d)
    }
}
