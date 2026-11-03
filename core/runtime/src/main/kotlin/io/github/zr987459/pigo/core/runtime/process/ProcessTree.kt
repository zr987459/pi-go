package io.github.zr987459.pigo.core.runtime.process

import java.io.File

/**
 * 读 `/proc/<pid>/stat` 与 `/proc/<pid>/status`，找出以某个 PID 为根的进程树（DESIGN.md §4.5）。
 * 只返回属于 [uid] 的进程；不按名字匹配任何进程。
 */
class ProcessTree(private val proc: File = File("/proc")) {

    data class Info(val pid: Int, val ppid: Int, val uid: Int, val startTime: Long)

    fun info(pid: Int): Info? {
        val stat = runCatching { File(proc, "$pid/stat").readText() }.getOrNull() ?: return null
        // 格式：pid (comm) state ppid ... ；comm 可含空格与括号，所以取最后一个 ')'
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val rest = stat.substring(close + 1).trim().split(' ')
        // rest[0]=state, rest[1]=ppid, ..., rest[19]=starttime（stat 第 22 个字段）
        val ppid = rest.getOrNull(1)?.toIntOrNull() ?: return null
        val start = rest.getOrNull(19)?.toLongOrNull() ?: 0L
        val uid = runCatching {
            File(proc, "$pid/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("Uid:") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
            }
        }.getOrNull() ?: return null
        return Info(pid, ppid, uid, start)
    }

    fun all(): List<Info> = proc.listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }
        .orEmpty()
        .mapNotNull { info(it.name.toInt()) }

    /** [root] 本身（若仍存在且属于 [uid]）及其全部后代，按「先子后父」排序。 */
    fun tree(root: Int, uid: Int): List<Info> {
        val snapshot = all()
        val byParent = snapshot.groupBy { it.ppid }
        val rootInfo = snapshot.firstOrNull { it.pid == root }
        val out = ArrayList<Info>()
        fun visit(pid: Int) {
            for (c in byParent[pid].orEmpty()) {
                if (c.uid != uid) continue
                visit(c.pid)
                out += c
            }
        }
        visit(root)
        if (rootInfo != null && rootInfo.uid == uid) out += rootInfo
        return out
    }

    /**
     * 引擎（pi）进程的 PID（DESIGN-r3 §7.2 B 档、`SessionHost.debugEnginePid()`）：[root]（proot）的后代中，
     * 可执行文件不是 proot 自身（[prootExe] 判断）的**最早启动**的那个。pi 运行工具时派生的 bash 等子进程启动更晚，
     * 不会被选中。找不到后代（例如桌面上 [root] 本身就是 pi）时返回 [root]。不按进程名匹配。
     */
    fun enginePid(root: Int, uid: Int, prootExe: (Int) -> Boolean = ::isProotExe): Int? {
        val all = tree(root, uid)
        if (all.none { it.pid == root }) return null
        val candidates = all.filter { it.pid != root && !prootExe(it.pid) }
        return candidates.minWithOrNull(compareBy<Info> { it.startTime }.thenBy { it.pid })?.pid ?: root
    }

    /** `/proc/<pid>/exe` 指向 App 自带的 proot（`libproot*.so`）。读不到时按「不是」处理。 */
    fun isProotExe(pid: Int): Boolean = runCatching {
        File(proc, "$pid/exe").canonicalPath.substringAfterLast('/').startsWith("libproot")
    }.getOrDefault(false)

    /** 进程是否仍是同一个（PID 未被复用）。 */
    fun stillSame(i: Info): Boolean = info(i.pid)?.let { it.startTime == i.startTime && it.uid == i.uid } == true
}

/** 发信号。Android 上用 `android.os.Process.sendSignal`；单测里替换。 */
fun interface Signaller {
    fun send(pid: Int, signal: Int)

    companion object {
        const val SIGKILL = 9
        const val SIGTERM = 15
    }
}
