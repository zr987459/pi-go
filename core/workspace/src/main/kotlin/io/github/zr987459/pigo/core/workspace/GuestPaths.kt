package io.github.zr987459.pigo.core.workspace

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** 路径不在允许范围内、或解析时越界。界面显示 [message]（中文）。 */
class PathDeniedException(message: String) : IOException(message)

/**
 * 容器内路径（guest path，如 `/root/proj/a.txt`）↔ 宿主 rootfs 文件的映射与越界检查。
 *
 * 宿主不能直接 `File(rootfs, guest).canonicalFile`：rootfs 里的绝对符号链接（`/etc/alternatives/x -> /usr/bin/y`）
 * 在宿主上会指到手机的根目录。这里按容器语义逐段解析符号链接：
 * - 绝对目标按 rootfs 根解析；相对目标按所在目录解析；`..` 到 `/` 为止（容器语义里 `/..` 就是 `/`）；
 * - PRoot `link2symlink` 生成的链接目标是宿主绝对路径（`<rootfs>/…/.l2s.xxx`），剥掉 rootfs 前缀后再按容器路径解析；
 * - 最多跟 [MAX_LINKS] 次，防环。
 *
 * 解析后的容器路径必须落在 [allowedRoots] 之一（会话 cwd、`/root`）之内，宿主文件的规范路径也必须在 rootfs 之内，否则抛 [PathDeniedException]。
 * 请求路径本身带 `..` 段一律拒绝（不做「先拼再规范化」）。
 */
class GuestPaths(
    rootfs: File,
    allowedRoots: Collection<String>,
) {
    val rootfs: File = rootfs.absoluteFile
    private val rootfsCanonical: String = runCatching { this.rootfs.canonicalPath }.getOrDefault(this.rootfs.path)
    val allowedRoots: List<String> = allowedRoots.map { normalize(it) }.distinct()

    /** 换一组允许根（会话 cwd 变了）。 */
    fun withRoots(roots: Collection<String>) = GuestPaths(rootfs, roots)

    /** 请求路径是否在允许范围内（只看字面，不解析链接；用于列表里显示的子项）。 */
    fun isAllowedLexically(guest: String): Boolean = runCatching { normalize(guest) }.getOrNull()?.let { p -> allowedRoots.any { within(it, p) } } == true

    /**
     * 解析为真实的容器路径（跟随符号链接）。最后一段不存在时也能解析（用于新建 / 删除后的 diff）。
     * @throws PathDeniedException 带 `..`、不是绝对路径、越出允许范围或链接过多
     */
    fun resolve(guest: String): String {
        val norm = normalize(guest)
        if (allowedRoots.none { within(it, norm) }) throw PathDeniedException("不能访问工作目录以外的位置：$norm")
        val real = followLinks(norm)
        if (allowedRoots.none { within(it, real) }) throw PathDeniedException("符号链接指向工作目录以外的位置：$norm → $real")
        return real
    }

    /** 解析并返回宿主文件；宿主规范路径必须仍在 rootfs 内。 */
    fun hostFile(guest: String): File {
        val real = resolve(guest)
        val f = rawHost(real)
        val canon = runCatching { f.canonicalPath }.getOrElse { throw PathDeniedException("无法访问：$guest") }
        if (canon != rootfsCanonical && !canon.startsWith(rootfsCanonical + File.separator)) {
            throw PathDeniedException("路径越出运行环境：$guest")
        }
        return f
    }

    /** 不解析链接、不检查范围的直接映射（只在已经 resolve 过的路径上用）。 */
    fun rawHost(realGuest: String): File = if (realGuest == "/") rootfs else File(rootfs, realGuest.removePrefix("/"))

    private fun followLinks(path: String): String {
        var hops = 0
        // 待处理的段（从前往后），已确认的前缀
        val pending = ArrayDeque(segments(path))
        val done = ArrayList<String>()
        while (pending.isNotEmpty()) {
            val seg = pending.removeFirst()
            when (seg) {
                "", "." -> continue
                ".." -> { if (done.isNotEmpty()) done.removeAt(done.lastIndex); continue }
            }
            done += seg
            val host = rawHost(join(done)).toPath()
            if (!Files.isSymbolicLink(host)) continue
            if (++hops > MAX_LINKS) throw PathDeniedException("符号链接层数过多：$path")
            val target = runCatching { Files.readSymbolicLink(host).toString() }.getOrElse { throw PathDeniedException("无法读取符号链接：${join(done)}") }
            done.removeAt(done.lastIndex)
            val guestTarget = hostLinkTargetToGuest(target)
            val targetSegs = segments(guestTarget)
            if (guestTarget.startsWith("/")) done.clear()
            for (i in targetSegs.indices.reversed()) pending.addFirst(targetSegs[i])
        }
        return join(done)
    }

    /** link2symlink 的宿主绝对路径 → 容器路径；其他原样返回。 */
    private fun hostLinkTargetToGuest(target: String): String {
        for (prefix in listOf(rootfsCanonical, rootfs.path)) {
            if (target == prefix) return "/"
            if (target.startsWith("$prefix/")) return target.substring(prefix.length)
        }
        return target
    }

    companion object {
        const val MAX_LINKS = 40

        /** 规范化容器绝对路径：去重复斜杠与 `.`；带 `..` 段或不是绝对路径则拒绝。 */
        fun normalize(guest: String): String {
            if (!guest.startsWith("/")) throw PathDeniedException("需要绝对路径：$guest")
            if (guest.indexOf('\u0000') >= 0) throw PathDeniedException("路径含非法字符")
            val segs = segments(guest).filter { it.isNotEmpty() && it != "." }
            if (segs.any { it == ".." }) throw PathDeniedException("路径不能包含 ..：$guest")
            return join(segs)
        }

        fun within(root: String, path: String): Boolean = root == "/" || path == root || path.startsWith("$root/")

        fun parent(path: String): String? = if (path == "/") null else path.substringBeforeLast('/').ifEmpty { "/" }

        fun name(path: String): String = if (path == "/") "/" else path.substringAfterLast('/')

        fun child(dir: String, name: String): String = if (dir == "/") "/$name" else "$dir/$name"

        private fun segments(p: String): List<String> = p.split('/')
        private fun join(segs: List<String>): String = "/" + segs.joinToString("/")

        /** 不跟随链接判断是否是符号链接（列目录时用）。 */
        internal fun isLink(f: File): Boolean = Files.isSymbolicLink(f.toPath())

        internal fun existsNoFollow(f: File): Boolean = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)
    }
}
