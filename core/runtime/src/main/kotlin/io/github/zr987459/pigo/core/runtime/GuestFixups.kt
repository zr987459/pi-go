package io.github.zr987459.pigo.core.runtime

import java.io.File

/**
 * 容器内的基础配置（DESIGN.md §4.3 第 4 步，按 resolv.conf(5)、hosts(5)、group(5) 与 npm 文档重新编写）。
 * 全部是幂等的文件写入，不启动任何进程。
 */
class GuestFixups(private val paths: RuntimePaths) {

    fun applyAll(hostDns: List<String>, fallbackDns: List<String>, npmRegistry: String, hostGids: List<Int>) {
        writeResolvConf(hostDns, fallbackDns)
        writeHosts()
        ensureGroups(hostGids)
        writeNpmrc(npmRegistry)
        ensureDirs()
    }

    /** 宿主 DNS 在前，国内公共 DNS 兜底；去重，最多 3 个（glibc 的 MAXNS）。 */
    fun writeResolvConf(hostDns: List<String>, fallbackDns: List<String>) {
        val servers = (hostDns + fallbackDns).map { it.trim() }.filter { IP.matches(it) }.distinct().take(3)
        val f = paths.guest("/etc/resolv.conf")
        // Ubuntu base 里 resolv.conf 可能是指向 systemd-resolved 的悬空软链：先删掉再写普通文件
        f.parentFile?.mkdirs()
        if (java.nio.file.Files.isSymbolicLink(f.toPath())) f.delete()
        f.writeText(buildString {
            appendLine("# 由 pi-go 生成")
            servers.forEach { appendLine("nameserver $it") }
            appendLine("options timeout:2 attempts:2")
        })
    }

    fun writeHosts() {
        val f = paths.guest("/etc/hosts")
        f.parentFile?.mkdirs()
        val existing = if (f.isFile) f.readLines() else emptyList()
        val needed = listOf("127.0.0.1 localhost", "::1 localhost ip6-localhost ip6-loopback")
        val missing = needed.filter { line -> existing.none { it.trim().split(Regex("\\s+")).take(2) == line.split(' ').take(2) } }
        if (missing.isEmpty() && f.isFile) return
        f.writeText((existing + missing).joinToString("\n", postfix = "\n"))
    }

    /**
     * Android 进程带有若干附加组（inet、sdcard_rw 等），容器内 /etc/group 没有这些 GID 时
     * `id`、apt 会打印 “cannot find name for group ID”。为每个缺失的 GID 补一行 `aid_<gid>`。
     */
    fun ensureGroups(hostGids: List<Int>) {
        val f = paths.guest("/etc/group")
        if (!f.isFile) return
        val lines = f.readLines().filter { it.isNotBlank() }
        val known = lines.mapNotNull { it.split(':').getOrNull(2)?.toIntOrNull() }.toSet()
        val names = lines.map { it.substringBefore(':') }.toSet()
        val add = hostGids.distinct().filter { it !in known }.map { gid ->
            var name = "aid_$gid"
            if (name in names) name = "pigo_$gid"
            "$name:x:$gid:root"
        }
        if (add.isEmpty()) return
        f.writeText((lines + add).joinToString("\n", postfix = "\n"))
    }

    /** 只配置 npmmirror registry（不带任何 scope 映射）。保留用户已有的其他配置行。 */
    fun writeNpmrc(registry: String) {
        val f = paths.guest("/root/.npmrc")
        f.parentFile?.mkdirs()
        val kept = if (f.isFile) f.readLines().filter { !it.trim().startsWith("registry=") && it.isNotBlank() } else emptyList()
        f.writeText((listOf("registry=$registry") + kept).joinToString("\n", postfix = "\n"))
    }

    fun ensureDirs() {
        for (p in listOf("/root/pi-cwd", "/root/.pi/agent", "/root/.pi/agent/sessions", "/root/.pi/agent/sessions-trash", "/tmp", "/root/shared")) {
            paths.guest(p).mkdirs()
        }
        paths.guest("/tmp").setWritable(true, false)
    }

    companion object {
        private val IP = Regex("^(\\d{1,3}(\\.\\d{1,3}){3}|[0-9a-fA-F:]+)$")
    }
}
