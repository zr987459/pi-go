package io.github.zr987459.pigo.core.runtime.install

import io.github.zr987459.pigo.contract.AppLog
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.InstallProgress
import io.github.zr987459.pigo.contract.InstallStep
import io.github.zr987459.pigo.core.runtime.GuestFixups
import io.github.zr987459.pigo.core.runtime.RuntimeFiles
import io.github.zr987459.pigo.core.runtime.RuntimePaths
import io.github.zr987459.pigo.core.runtime.net.Downloader
import io.github.zr987459.pigo.core.runtime.net.MirrorProbe
import io.github.zr987459.pigo.core.runtime.util.Hashes
import io.github.zr987459.pigo.core.runtime.util.SafeFs
import java.io.File
import java.io.IOException
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 宿主信息（Android 实现见 AndroidHost；单测替换）。 */
interface HostInfo {
    val abis: List<String>
    fun freeBytes(dir: File): Long
    fun networkAvailable(): Boolean
    fun dnsServers(): List<String>
    fun gids(): List<Int>
}

/** 在容器内执行命令（真实实现走 proot）。 */
interface GuestExec {
    suspend fun exec(cmd: List<String>, env: Map<String, String> = emptyMap(), timeoutMs: Long = 60_000): ExecResult

    /** 启动 `pi --mode rpc --no-session`，发一条 get_state，[timeoutMs] 内收到 success:true 返回 true。 */
    suspend fun rpcHandshake(timeoutMs: Long = 20_000): Boolean
}

class InstallFailure(val code: ErrCode, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 首启安装（DESIGN.md §4.3，国内优先）。每步完成写标记文件；失败只重试当前步；已校验的文件不重下。
 * 返回冷流：收集即开始，取消即中止（下一次收集从未完成的步骤继续）。
 */
class DomesticInstaller(
    private val paths: RuntimePaths,
    private val pins: Pins,
    private val mirrors: Mirrors,
    private val downloader: Downloader,
    private val probe: MirrorProbe,
    private val host: HostInfo,
    private val guest: GuestExec,
    private val log: AppLog,
    private val extract: TarExtract = TarExtract(),
    /** 「我有代理，走官方源」：官方源同样校验 pins.json 哈希。 */
    private val useOfficialSources: Boolean = false,
    private val minFreeBytes: Long = MIN_FREE_BYTES,
    /** 本进程内已判定不可用的下载地址（校验失败/不一致），重试时排到最后。 */
    private val badUrls: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf()),
) {
    private val markers = Markers(paths.markers)

    fun run(): Flow<InstallProgress> = flow {
        val arch = GuestArch.fromAbis(host.abis)
            ?: throw InstallFailure(ErrCode.INSTALL_FAILED, "不支持的处理器架构（${host.abis.joinToString()}），只支持 arm64-v8a 与 x86_64")

        step(InstallStep.PREFLIGHT, 0, "正在检查设备…")
        preflight()
        step(InstallStep.PREFLIGHT, 2, "设备检查通过（${arch.abi}）")

        if (!markers.done(InstallStep.ROOTFS) || !markers.done(InstallStep.NODE)) {
            step(InstallStep.PROBE, 2, "正在测试镜像速度…")
        }
        if (!markers.done(InstallStep.ROOTFS)) rootfs(arch) else log.i(TAG, "ROOTFS 已完成，跳过")
        if (!markers.done(InstallStep.NODE)) node(arch) else log.i(TAG, "NODE 已完成，跳过")
        // 修复步骤每次都跑：都是幂等的文件写入，开销很小，还能修正被改坏的配置
        fixups()
        if (!markers.done(InstallStep.APT)) apt(arch) else log.i(TAG, "APT 已完成，跳过")
        if (!markers.done(InstallStep.PI)) pi() else log.i(TAG, "PI 已完成，跳过")
        verify(arch)
        step(InstallStep.VERIFY, 100, "安装完成")
    }.flowOn(Dispatchers.IO)

    // ---------------------------------------------------------------- 0 PREFLIGHT

    private fun preflight() {
        paths.filesDir.mkdirs()
        checkRuntimeFiles()
        val free = host.freeBytes(paths.filesDir)
        if (free < minFreeBytes) {
            throw InstallFailure(ErrCode.DISK_FULL, "可用空间不足：需要至少 ${minFreeBytes / GB} GB，当前只有 ${"%.1f".format(free.toDouble() / GB)} GB。请清理手机存储后重试。")
        }
        if (!host.networkAvailable()) throw InstallFailure(ErrCode.NETWORK, "没有可用的网络连接，请联网后重试。")
    }

    /** proot 本体与依赖库是否都随 APK 就位；缺失即硬失败（安装包问题，换网络/重试都无济于事）。 */
    private fun checkRuntimeFiles() {
        if (!paths.proot.isFile || !paths.proot.canExecute() || !paths.loader.isFile) {
            throw InstallFailure(ErrCode.INSTALL_FAILED, "找不到可执行的 proot（${paths.proot.name}），安装包可能不完整，请重新安装 pi-go。")
        }
        // proot 的 DT_NEEDED 依赖（libtalloc.so.2、libandroid-shmem.so）缺任何一个，proot 都无法启动：
        // 必须在下载任何东西之前硬失败，而不是下载几百 MB 后在验证步骤才失败。
        val report = RuntimeFiles.ensure(paths)
        if (report.missing.isNotEmpty() || !RuntimeFiles.prootAvailable(paths)) {
            val missing = (report.missing + RuntimeFiles.missingLibs(paths)).distinct()
            log.w(TAG, "nativeLibDir 缺少：${missing.joinToString()}")
            throw InstallFailure(
                ErrCode.INSTALL_FAILED,
                "安装包缺少运行环境组件（${missing.joinToString("、")}），proot 无法启动。请重新安装或更新 pi-go。",
            )
        }
    }

    // ---------------------------------------------------------------- 2 ROOTFS

    private suspend fun FlowCollector<InstallProgress>.rootfs(arch: GuestArch) {
        val file = pins.ubuntuFile(arch)
        val sources = if (useOfficialSources) mirrors.ubuntuBaseOfficial else mirrors.ubuntuBase
        val tarball = File(paths.downloads, file)
        fetchVerified(InstallStep.ROOTFS, 5, 25, sources.map { it.url(file) }, tarball, pins.ubuntuHash(arch), "SHA256SUMS")

        step(InstallStep.ROOTFS, 25, "正在解压 Ubuntu ${pins.ubuntuVersion}…")
        val stagingRoot = File(paths.staging, "rootfs")
        SafeFs.deleteTree(paths.staging)
        stagingRoot.mkdirs()
        try {
            val r = extract.extractTarGz(tarball, stagingRoot)
            log.i(TAG, "rootfs 解压完成：${r.files} 个文件，${r.symlinks} 个链接，${r.bytes} 字节")
        } catch (e: IOException) {
            SafeFs.deleteTree(paths.staging)
            throw InstallFailure(ErrCode.INSTALL_FAILED, "解压 Ubuntu 失败：${e.message}", e)
        }
        replaceRootfs(stagingRoot)
        markers.mark(InstallStep.ROOTFS)
        tarball.delete()
        step(InstallStep.ROOTFS, 35, "Ubuntu ${pins.ubuntuVersion} 已就绪")
    }

    /**
     * 用 staging 中的新 rootfs 替换旧的；旧 rootfs 中的 `/root/.pi/agent` 与 `/root/pi-cwd` 先移到 `files/preserve`，
     * 换好后再移回（会话、models.json、settings.json、回收站、工作目录都不丢）。
     */
    private fun replaceRootfs(newRoot: File) {
        val keep = listOf("root/.pi/agent", "root/pi-cwd")
        if (SafeFs.exists(paths.rootfs)) {
            for (rel in keep) {
                val src = File(paths.rootfs, rel)
                val dst = File(paths.preserve, rel)
                if (SafeFs.exists(src) && !SafeFs.exists(dst)) {
                    SafeFs.move(src, dst)
                    log.i(TAG, "已暂存 /$rel")
                }
            }
            val old = File(paths.staging, "old-rootfs")
            SafeFs.move(paths.rootfs, old)
            SafeFs.deleteTree(old)
        }
        SafeFs.move(newRoot, paths.rootfs)
        restorePreserved()
        SafeFs.deleteTree(paths.staging)
    }

    private fun restorePreserved() {
        for (rel in listOf("root/.pi/agent", "root/pi-cwd")) {
            val src = File(paths.preserve, rel)
            if (!SafeFs.exists(src)) continue
            val dst = File(paths.rootfs, rel)
            if (SafeFs.exists(dst)) {
                // 新 rootfs 里不会有这些目录；万一有（空目录），只在为空时替换
                if (dst.isDirectory && (dst.list()?.isEmpty() == true)) dst.delete() else continue
            }
            SafeFs.move(src, dst)
            log.i(TAG, "已恢复 /$rel")
        }
        if (paths.preserve.exists() && paths.preserve.walkBottomUp().none { it.isFile }) SafeFs.deleteTree(paths.preserve)
    }

    // ---------------------------------------------------------------- 3 NODE

    private suspend fun FlowCollector<InstallProgress>.node(arch: GuestArch) {
        val v = pins.nodeVersion
        val file = pins.nodeFile(arch)
        val sources = if (useOfficialSources) mirrors.nodeOfficial else mirrors.node
        val tarball = File(paths.downloads, file)
        fetchVerified(InstallStep.NODE, 35, 52, sources.map { it.url(file, v) }, tarball, pins.nodeHash(arch), "SHASUMS256.txt")

        step(InstallStep.NODE, 52, "正在解压 Node.js v$v…")
        val nodeDir = paths.guest("/opt/node")
        val staging = File(nodeDir, ".staging-$v")
        val target = File(nodeDir, v)
        SafeFs.deleteTree(staging)
        try {
            extract.extractTarGz(tarball, staging, stripComponents = 1)
        } catch (e: IOException) {
            SafeFs.deleteTree(staging)
            throw InstallFailure(ErrCode.INSTALL_FAILED, "解压 Node.js 失败：${e.message}", e)
        }
        if (!File(staging, "bin/node").isFile) {
            SafeFs.deleteTree(staging)
            throw InstallFailure(ErrCode.INSTALL_FAILED, "Node.js 包内容不完整（缺少 bin/node）")
        }
        SafeFs.deleteTree(target)
        SafeFs.move(staging, target)
        SafeFs.replaceSymlink(File(nodeDir, "current"), v)
        for (b in listOf("node", "npm", "npx")) {
            SafeFs.replaceSymlink(paths.guest("/usr/local/bin/$b"), "/opt/node/current/bin/$b")
        }
        markers.mark(InstallStep.NODE)
        tarball.delete()
        step(InstallStep.NODE, 60, "Node.js v$v 已就绪")
    }

    // ---------------------------------------------------------------- 4 FIXUPS

    private suspend fun FlowCollector<InstallProgress>.fixups() {
        step(InstallStep.FIXUPS, 60, "正在配置容器（DNS、npm 源、目录）…")
        RuntimeFiles.ensure(paths)
        GuestFixups(paths).applyAll(host.dnsServers(), mirrors.dns, mirrors.npmRegistry, host.gids())
        markers.mark(InstallStep.FIXUPS)
    }

    // ---------------------------------------------------------------- 5 APT（失败不致命）

    private suspend fun FlowCollector<InstallProgress>.apt(arch: GuestArch) {
        val mirror = mirrors.apt[arch.ubuntu] ?: return
        step(InstallStep.APT, 61, "正在安装基础工具（git、curl 等）…")
        writeAptSources(mirror)
        val r = guest.exec(
            listOf(
                "/bin/sh", "-c",
                "apt-get -o APT::Sandbox::User=root update && " +
                    "apt-get -o APT::Sandbox::User=root install -y --no-install-recommends ca-certificates git curl procps",
            ),
            env = mapOf("DEBIAN_FRONTEND" to "noninteractive"),
            // APT 失败不致命，也不写标记（下次安装/修复会再试）；前台只给几分钟，避免每次重试都卡在这一步 20 分钟
            timeoutMs = APT_FOREGROUND_TIMEOUT_MS,
        )
        if (r.exitCode == 0) {
            markers.mark(InstallStep.APT)
            step(InstallStep.APT, 75, "基础工具已安装")
        } else {
            // 不影响 pi 运行；不写标记，下次「修复」会重试
            val why = if (r.timedOut) "超过 ${APT_FOREGROUND_TIMEOUT_MS / 60_000} 分钟未完成" else "exit=${r.exitCode} ${r.stderr.takeLast(400)}"
            log.w(TAG, "apt 安装失败（不影响使用，稍后可在设置里修复）：$why")
            step(InstallStep.APT, 75, "基础工具安装失败，已跳过（不影响使用）")
        }
    }

    /** Ubuntu 24.04 使用 deb822 格式的 `/etc/apt/sources.list.d/ubuntu.sources`。 */
    internal fun writeAptSources(mirror: String) {
        val f = paths.guest("/etc/apt/sources.list.d/ubuntu.sources")
        f.parentFile?.mkdirs()
        f.writeText(
            """
            |# 由 pi-go 写入（国内镜像）
            |Types: deb
            |URIs: $mirror
            |Suites: noble noble-updates noble-backports noble-security
            |Components: main restricted universe multiverse
            |Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
            |""".trimMargin(),
        )
    }

    // ---------------------------------------------------------------- 6 PI

    private suspend fun FlowCollector<InstallProgress>.pi() {
        val v = pins.piVersion
        step(InstallStep.PI, 76, "正在核对 pi $v 的完整性…")
        val metaUrl = mirrors.npmRegistry.trimEnd('/') + "/" + pins.piPackage + "/" + v
        val integrity = try {
            val meta = Json.parseToJsonElement(downloader.fetchText(metaUrl)).jsonObject
            meta["dist"]?.jsonObject?.get("integrity")?.jsonPrimitive?.content
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw InstallFailure(ErrCode.NETWORK, "无法从 npm 镜像读取 pi $v 的信息，请换网络或稍后再试。", e)
        }
        if (integrity != pins.piIntegrity) {
            throw InstallFailure(ErrCode.CHECKSUM, "npm 镜像上 pi $v 的 integrity 与内置记录不一致，已中止安装。")
        }
        step(InstallStep.PI, 78, "正在安装 pi $v（npm）…")
        val prefix = "/opt/pi-go/pi/$v"
        val r = guest.exec(npmInstallCmd(prefix, v), timeoutMs = 20 * 60_000)
        if (r.exitCode != 0 || !paths.guest("$prefix/bin/pi").let { SafeFs.exists(it) }) {
            throw InstallFailure(ErrCode.INSTALL_FAILED, "安装 pi 失败（npm 退出码 ${r.exitCode}）：${r.stderr.takeLast(300)}")
        }
        SafeFs.replaceSymlink(paths.guest("/opt/pi-go/pi/current"), v)
        SafeFs.replaceSymlink(paths.guest("/usr/local/bin/pi"), "/opt/pi-go/pi/current/bin/pi")
        markers.mark(InstallStep.PI)
        step(InstallStep.PI, 95, "pi $v 已安装")
    }

    internal fun npmInstallCmd(prefix: String, version: String) = listOf(
        "npm", "install", "-g", "--prefix", prefix,
        "--ignore-scripts", "--no-audit", "--no-fund",
        "--registry", mirrors.npmRegistry,
        "${pins.piPackage}@$version",
    )

    // ---------------------------------------------------------------- 7 VERIFY

    private suspend fun FlowCollector<InstallProgress>.verify(arch: GuestArch) {
        step(InstallStep.VERIFY, 95, "正在验证运行环境…")
        val node = guest.exec(listOf("node", "-v"), timeoutMs = 30_000)
        if (node.exitCode != 0 || node.stdout.trim() != "v${pins.nodeVersion}") {
            markers.clear(InstallStep.NODE)
            throw InstallFailure(ErrCode.INSTALL_FAILED, "Node 验证失败：期望 v${pins.nodeVersion}，实际「${node.stdout.trim()}」${node.stderr.takeLast(200)}")
        }
        val pi = guest.exec(listOf("pi", "--version"), timeoutMs = 60_000)
        if (pi.exitCode != 0 || pi.stdout.trim() != pins.piVersion) {
            markers.clear(InstallStep.PI)
            throw InstallFailure(ErrCode.INSTALL_FAILED, "pi 验证失败：期望 ${pins.piVersion}，实际「${pi.stdout.trim()}」${pi.stderr.takeLast(200)}")
        }
        step(InstallStep.VERIFY, 98, "正在进行 RPC 握手…")
        if (!guest.rpcHandshake(20_000)) {
            throw InstallFailure(ErrCode.ENGINE_START, "pi RPC 握手失败（20 秒内未收到 get_state 响应）")
        }
        writeRecord()
        markers.mark(InstallStep.VERIFY)
    }

    private fun writeRecord() {
        val f = paths.runtimeRecord
        f.parentFile?.mkdirs()
        f.writeText(
            """{"ubuntu":"${pins.ubuntuVersion}","node":"v${pins.nodeVersion}","pi":"${pins.piVersion}","installedAt":"${Instant.now()}"}""" + "\n",
        )
    }

    // ---------------------------------------------------------------- 下载：测速 + 换镜像 + 交叉核对

    private suspend fun FlowCollector<InstallProgress>.fetchVerified(
        step: InstallStep,
        from: Int,
        to: Int,
        urls: List<String>,
        dest: File,
        pinnedSha: String,
        sumsName: String,
    ) {
        if (dest.isFile && Hashes.sha256(dest) == pinnedSha) {
            log.i(TAG, "${dest.name} 已存在且校验通过，跳过下载")
            return
        }
        val ranked = probe.rank(urls).let { scores ->
            log.i(TAG, "镜像测速：" + scores.joinToString { "${hostOf(it.url)}=${if (it.ok) "${it.bytesPerSec / 1024}KB/s" else "不可用"}" })
            val ok = scores.map { it.url }
            ok.filter { it !in badUrls } + ok.filter { it in badUrls }
        }
        var lastError: Throwable? = null
        var sawChecksum = false
        /** 本轮因「过慢」放弃的镜像，保持测速排名顺序。它们各自的 `.part` 仍在，回退时可续传。 */
        val tooSlow = mutableListOf<String>()
        for (url in ranked) {
            val host = hostOf(url)
            emit(InstallProgress(step, from, null, "正在从 $host 下载 ${dest.name}…"))
            // 交叉核对镜像自带的校验和文件（取不到不阻塞，以 pins.json 为准）
            val sumsUrl = url.substringBeforeLast('/') + "/" + sumsName
            val mirrorSha = runCatching { Hashes.lookupSum(downloader.fetchText(sumsUrl), dest.name) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (mirrorSha != null && mirrorSha != pinnedSha) {
                log.w(TAG, "$host 的 $sumsName 与内置哈希不一致，换下一个镜像")
                badUrls += url
                sawChecksum = true
                continue
            }
            try {
                downloadWithProgress(step, from, to, url, dest, pinnedSha, detectSlow = true)
                log.i(TAG, "${dest.name} 下载完成并通过校验（$host）")
                return
            } catch (e: Downloader.ChecksumMismatch) {
                log.w(TAG, "校验失败，已换镜像：$host（已删除损坏的文件）")
                emit(InstallProgress(step, from, null, "校验失败，已换镜像"))
                badUrls += url
                sawChecksum = true
                lastError = e
            } catch (e: Downloader.TooSlow) {
                log.w(TAG, "$host 速度过慢（${e.bytesPerSec / 1024} KB/s），换线（保留该镜像已下载的部分）")
                tooSlow += url
                lastError = e
            } catch (e: IOException) {
                log.w(TAG, "$host 下载失败：${e.message}")
                lastError = e
            }
        }
        // 所有可用镜像都只是「慢」：网络整体偏慢时不能因此判失败。回退到测速排名最好的那个慢镜像，
        // 关闭过慢判定一直下完（接着它自己的 .part 续传，不拼接其他来源）。
        val fallback = tooSlow.firstOrNull()
        if (fallback != null) {
            val host = hostOf(fallback)
            log.w(TAG, "所有镜像都较慢，回退到 $host 继续下载（不再按速度换线）")
            emit(InstallProgress(step, from, null, "网络较慢，继续从 $host 下载 ${dest.name}…"))
            try {
                downloadWithProgress(step, from, to, fallback, dest, pinnedSha, detectSlow = false)
                log.i(TAG, "${dest.name} 下载完成并通过校验（$host，慢速回退）")
                return
            } catch (e: Downloader.ChecksumMismatch) {
                log.w(TAG, "校验失败，已换镜像：$host（已删除损坏的文件）")
                emit(InstallProgress(step, from, null, "校验失败，已换镜像"))
                badUrls += fallback
                sawChecksum = true
                lastError = e
            } catch (e: IOException) {
                log.w(TAG, "$host 下载失败：${e.message}")
                lastError = e
            }
        }
        throw InstallFailure(
            if (sawChecksum) ErrCode.CHECKSUM else ErrCode.NETWORK,
            "所有镜像都下载失败（${dest.name}），请换网络或稍后再试。",
            lastError,
        )
    }

    /** 下载一个镜像，并按 [PROGRESS_INTERVAL_MS] 节流上报进度。 */
    private suspend fun FlowCollector<InstallProgress>.downloadWithProgress(
        step: InstallStep,
        from: Int,
        to: Int,
        url: String,
        dest: File,
        pinnedSha: String,
        detectSlow: Boolean,
    ) {
        val host = hostOf(url)
        coroutineScope {
            val job = async {
                downloader.download(url, dest, pinnedSha, detectSlow) { p ->
                    val frac = p.total?.takeIf { it > 0 }?.let { p.downloaded.toDouble() / it } ?: 0.0
                    val pct = from + ((to - from) * frac).toInt().coerceIn(0, to - from)
                    lastProgress = InstallProgress(step, pct, p.bytesPerSec, "正在从 $host 下载 ${dest.name}（${p.downloaded / MB} MB${p.total?.let { " / ${it / MB} MB" } ?: ""}）")
                }
            }
            while (job.isActive) {
                delay(PROGRESS_INTERVAL_MS)
                lastProgress?.let { emit(it); lastProgress = null }
            }
            job.await()
        }
    }

    @Volatile private var lastProgress: InstallProgress? = null

    private suspend fun FlowCollector<InstallProgress>.step(step: InstallStep, percent: Int, msg: String) {
        log.i(TAG, "[$step $percent%] $msg")
        emit(InstallProgress(step, percent, null, msg))
    }

    /** 每步完成标记。 */
    class Markers(private val dir: File) {
        fun done(step: InstallStep) = File(dir, "${step.name}.done").isFile
        fun mark(step: InstallStep) {
            dir.mkdirs()
            File(dir, "${step.name}.done").writeText(Instant.now().toString())
        }
        fun clear(step: InstallStep) {
            File(dir, "${step.name}.done").delete()
        }
        fun clearAll() {
            InstallStep.entries.forEach { clear(it) }
        }
    }

    companion object {
        const val TAG = "Installer"
        private const val MB = 1024L * 1024
        private const val GB = 1024L * 1024 * 1024
        const val MIN_FREE_BYTES = 3L * GB
        private const val PROGRESS_INTERVAL_MS = 300L
        /** APT 步骤的前台超时（DESIGN §4.3 第 5 步：失败不致命）。 */
        const val APT_FOREGROUND_TIMEOUT_MS = 5L * 60_000

        fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url
    }
}
