package io.github.zr987459.pigo.core.runtime

import io.github.zr987459.pigo.contract.AppLog
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.InstallProgress
import io.github.zr987459.pigo.contract.InstallStep
import io.github.zr987459.pigo.contract.LogLevel
import io.github.zr987459.pigo.contract.LogLine
import io.github.zr987459.pigo.core.runtime.install.DomesticInstaller
import io.github.zr987459.pigo.core.runtime.install.GuestExec
import io.github.zr987459.pigo.core.runtime.install.HostInfo
import io.github.zr987459.pigo.core.runtime.install.InstallFailure
import io.github.zr987459.pigo.core.runtime.install.Mirror
import io.github.zr987459.pigo.core.runtime.install.Mirrors
import io.github.zr987459.pigo.core.runtime.install.Pins
import io.github.zr987459.pigo.core.runtime.net.Downloader
import io.github.zr987459.pigo.core.runtime.net.MirrorProbe
import io.github.zr987459.pigo.core.runtime.util.SafeFs
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 安装流程的 JVM 端到端测试：真下载（MockWebServer）、真校验、真解压；容器内命令用假实现。 */
class DomesticInstallerTest {
    private val tmp: File = Files.createTempDirectory("inst").toFile()
    private val files = File(tmp, "files")
    private val nativeDir = File(tmp, "native").apply {
        mkdirs()
        listOf("libproot.so", "libprootloader.so", "libprootloader32.so", "libtalloc.so", "libandroidshmem.so").forEach {
            File(this, it).apply { writeText("elf"); setExecutable(true) }
        }
    }
    private val paths = RuntimePaths(files, nativeDir, null)

    /** 不易压缩的填充文件，让包大到需要分很多次读完（测速/过慢判定用）。 */
    private val filler: String = java.util.Random(1).let { r -> buildString { repeat(96 * 1024) { append(('!' + r.nextInt(90))) } } }

    private val ubuntuTar = tgz(
        Entry.File("./usr/share/filler", filler),
        Entry.Dir("./usr/bin/"), Entry.Link("./bin", "usr/bin"), Entry.File("./usr/bin/sh", "#!", 0b111_101_101),
        Entry.File("./etc/group", "root:x:0:\n"), Entry.Dir("./root/"), Entry.Dir("./tmp/"),
        Entry.Link("./etc/resolv.conf", "../run/systemd/resolve/stub-resolv.conf"),
    )
    private val nodeTar = tgz(
        Entry.File("node-v24.22.2-linux-arm64/bin/node", "node", 0b111_101_101),
        Entry.File("node-v24.22.2-linux-arm64/share/filler", filler),
        Entry.Link("node-v24.22.2-linux-arm64/bin/npm", "../lib/node_modules/npm/bin/npm-cli.js"),
        Entry.Link("node-v24.22.2-linux-arm64/bin/npx", "../lib/node_modules/npm/bin/npx-cli.js"),
    )
    private val ubuntuName = "ubuntu-base-24.04.5-base-arm64.tar.gz"
    private val nodeName = "node-v24.22.2-linux-arm64.tar.gz"
    private val integrity = "sha512-test=="

    private val pins = Pins(
        piPackage = "@earendil-works/pi-coding-agent", piVersion = "1.5.0", piIntegrity = integrity,
        piCompat = ">=1.5.0 <2.0.0", piBlocked = emptyList(),
        nodeVersion = "24.22.2", nodeSha256 = mapOf("arm64" to sha(nodeTar), "x64" to "0".repeat(64)),
        ubuntuVersion = "24.04.5", ubuntuSha256 = mapOf("arm64" to sha(ubuntuTar), "amd64" to "0".repeat(64)),
    )

    private lateinit var good: MockWebServer
    private lateinit var bad: MockWebServer
    private val goodTarballHits = AtomicInteger()

    @Before fun setUp() {
        good = MockWebServer().apply { dispatcher = serve(corrupt = false); start() }
        bad = MockWebServer().apply { dispatcher = serve(corrupt = true); start() }
    }

    @After fun tearDown() {
        good.shutdown(); bad.shutdown()
        SafeFs.deleteTree(tmp)
    }

    private fun serve(corrupt: Boolean) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!.substringBefore('?')
            val bytes = when {
                path.endsWith(ubuntuName) -> ubuntuTar
                path.endsWith(nodeName) -> nodeTar
                path.endsWith("/pi-coding-agent/1.5.0") -> """{"dist":{"integrity":"$integrity"}}""".toByteArray()
                else -> return MockResponse().setResponseCode(404)
            }
            if (request.method == "HEAD") return MockResponse().setHeader("Content-Length", bytes.size)
            val isTarball = path.endsWith(".tar.gz")
            if (isTarball && request.getHeader("Range")?.startsWith("bytes=0-") == true && request.getHeader("Range") != "bytes=0-") {
                // 测速采样
                return MockResponse().setBody(Buffer().write(bytes))
            }
            if (isTarball && !corrupt) goodTarballHits.incrementAndGet()
            val body = if (corrupt && isTarball) bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() } else bytes
            return MockResponse().setBody(Buffer().write(body))
        }
    }

    private fun mirrors(vararg servers: MockWebServer) = Mirrors(
        ubuntuBase = servers.map { Mirror(it.hostName, it.url("/ubuntu-base/").toString()) },
        ubuntuBaseOfficial = emptyList(),
        node = servers.map { Mirror(it.hostName, it.url("/node/v{version}/").toString()) },
        nodeOfficial = emptyList(),
        apt = mapOf("arm64" to "http://mirrors.aliyun.com/ubuntu-ports/"),
        npmRegistry = good.url("/").toString(),
        dns = listOf("223.5.5.5", "119.29.29.29"),
    )

    private class Host(var free: Long = 10L shl 30) : HostInfo {
        override val abis = listOf("arm64-v8a")
        override fun freeBytes(dir: File) = free
        override fun networkAvailable() = true
        override fun dnsServers() = listOf("10.0.0.1")
        override fun gids() = listOf(3003, 0)
    }

    private inner class Guest(var npmFails: Boolean = false) : GuestExec {
        val commands = mutableListOf<List<String>>()
        val timeouts = mutableListOf<Long>()
        override suspend fun exec(cmd: List<String>, env: Map<String, String>, timeoutMs: Long): ExecResult {
            commands += cmd
            timeouts += timeoutMs
            return when {
                cmd.firstOrNull() == "npm" -> {
                    if (npmFails) ExecResult(1, "", "ETIMEDOUT")
                    else {
                        val prefix = cmd[cmd.indexOf("--prefix") + 1]
                        paths.guest("$prefix/bin").mkdirs()
                        File(paths.guest("$prefix/bin"), "pi").writeText("#!/usr/bin/env node")
                        ExecResult(0, "added 1 package", "")
                    }
                }
                cmd == listOf("node", "-v") -> ExecResult(0, "v24.22.2\n", "")
                cmd == listOf("pi", "--version") -> ExecResult(0, "1.5.0\n", "")
                else -> ExecResult(0, "", "")
            }
        }
        override suspend fun rpcHandshake(timeoutMs: Long) = true
    }

    private class Log : AppLog {
        val lines = mutableListOf<String>()
        override fun i(tag: String, msg: String) { synchronized(lines) { lines += msg } }
        override fun w(tag: String, msg: String, t: Throwable?) { synchronized(lines) { lines += msg } }
        override fun tail(n: Int) = lines.takeLast(n).map { LogLine(0, LogLevel.INFO, "t", it) }
    }

    private val client = OkHttpClient()

    /** 保持镜像表顺序的「测速」，让换镜像的测试结果确定。 */
    private val inOrder = object : MirrorProbe(client) {
        override suspend fun rank(urls: List<String>) = urls.map { Score(it, true, 0) }
    }

    private fun installer(
        m: Mirrors,
        guest: GuestExec = Guest(),
        host: HostInfo = Host(),
        log: AppLog = Log(),
        probe: MirrorProbe = inOrder,
        downloader: Downloader = Downloader(client),
    ) = DomesticInstaller(paths, pins, m, downloader, probe, host, guest, log)

    @Test fun fullInstallProducesRuntime() = runBlocking {
        val guest = Guest()
        val log = Log()
        val progress = installer(mirrors(good), guest, log = log, probe = MirrorProbe(client, sampleMs = 50)).run().toList()

        assertEquals(100, progress.last().percent)
        assertEquals(InstallStep.VERIFY, progress.last().step)
        assertTrue("百分比单调不减", progress.zipWithNext().all { (a, b) -> b.percent >= a.percent })
        assertTrue(File(paths.rootfs, "usr/bin/sh").isFile)
        assertEquals("24.22.2", SafeFs.readSymlink(paths.guest("/opt/node/current")))
        assertEquals("/opt/node/current/bin/node", SafeFs.readSymlink(paths.guest("/usr/local/bin/node")))
        assertEquals("/opt/pi-go/pi/current/bin/pi", SafeFs.readSymlink(paths.guest("/usr/local/bin/pi")))
        assertEquals("1.5.0", SafeFs.readSymlink(paths.guest("/opt/pi-go/pi/current")))
        // 修复项
        val resolv = paths.guest("/etc/resolv.conf")
        assertFalse(Files.isSymbolicLink(resolv.toPath()))
        assertEquals(listOf("nameserver 10.0.0.1", "nameserver 223.5.5.5", "nameserver 119.29.29.29"), resolv.readLines().filter { it.startsWith("nameserver") })
        assertEquals("registry=${good.url("/")}", paths.guest("/root/.npmrc").readLines().first())
        assertTrue(paths.guest("/etc/group").readText().contains("aid_3003:x:3003:"))
        assertTrue(paths.guest("/root/.pi/agent/sessions-trash").isDirectory)
        assertTrue(paths.guest("/root/pi-cwd").isDirectory)
        assertTrue(paths.guest("/etc/apt/sources.list.d/ubuntu.sources").readText().contains("URIs: http://mirrors.aliyun.com/ubuntu-ports/"))
        // pi 用 --ignore-scripts 装到版本化 prefix
        val npm = guest.commands.first { it.first() == "npm" }
        assertTrue(npm.containsAll(listOf("--ignore-scripts", "--prefix", "/opt/pi-go/pi/1.5.0", "@earendil-works/pi-coding-agent@1.5.0")))
        // 记录与标记
        assertTrue(paths.runtimeRecord.readText().contains("\"node\":\"v24.22.2\""))
        InstallStep.entries.filter { it != InstallStep.PROBE && it != InstallStep.PREFLIGHT }.forEach {
            assertTrue("$it 未标记", File(paths.markers, "${it.name}.done").isFile)
        }
        // 已用完的安装包被删除，staging 被清理
        assertFalse(File(paths.downloads, ubuntuName).exists())
        assertFalse(paths.staging.exists())
    }

    @Test fun corruptedMirrorIsSwitchedAndBrokenFileDeleted() = runBlocking {
        val log = Log()
        // 镜像表顺序：先 bad（包被改坏）再 good
        val m = mirrors(bad, good)
        val progress = installer(m, log = log).run().toList()
        assertTrue(progress.any { it.message == "校验失败，已换镜像" })
        assertTrue(log.lines.any { it.startsWith("校验失败，已换镜像") })
        assertTrue("最终从完好的镜像取得", goodTarballHits.get() >= 1)
        assertTrue(File(paths.rootfs, "usr/bin/sh").isFile)
        assertTrue(Downloader.allParts(File(paths.downloads, ubuntuName)).isEmpty())
    }

    @Test fun retryResumesFromFailedStepWithoutRedownloading() = runBlocking {
        val log = Log()
        val failing = Guest(npmFails = true)
        val e = assertThrows(InstallFailure::class.java) { runBlocking { installer(mirrors(good), failing, log = log).run().toList() } }
        assertEquals(ErrCode.INSTALL_FAILED, e.code)
        val hitsAfterFirst = goodTarballHits.get()
        assertEquals(2, hitsAfterFirst)

        val progress = installer(mirrors(good), Guest(), log = log).run().toList()
        assertEquals(100, progress.last().percent)
        assertEquals("已校验的包不应重下", hitsAfterFirst, goodTarballHits.get())
        assertTrue(log.lines.contains("ROOTFS 已完成，跳过"))
        assertTrue(log.lines.contains("NODE 已完成，跳过"))
    }

    @Test fun reinstallingRootfsPreservesSessionsConfigAndWorkdir() = runBlocking {
        installer(mirrors(good)).run().toList()
        val session = paths.guest("/root/.pi/agent/sessions/2026/a.jsonl").apply { parentFile.mkdirs(); writeText("{\"type\":\"session\"}\n") }
        val models = paths.guest("/root/.pi/agent/models.json").apply { writeText("{}") }
        val work = paths.guest("/root/pi-cwd/project/main.py").apply { parentFile.mkdirs(); writeText("print(1)") }
        // 模拟 rootfs 损坏：清掉标记后重装
        DomesticInstaller.Markers(paths.markers).clearAll()
        installer(mirrors(good)).run().toList()
        assertEquals("{\"type\":\"session\"}\n", session.readText())
        assertEquals("{}", models.readText())
        assertEquals("print(1)", work.readText())
        assertFalse(paths.preserve.exists())
    }

    @Test fun notEnoughSpaceIsRefusedWithChineseReason() {
        val e = assertThrows(InstallFailure::class.java) {
            runBlocking { installer(mirrors(good), host = Host(free = 2L shl 30)).run().toList() }
        }
        assertEquals(ErrCode.DISK_FULL, e.code)
        assertTrue(e.message!!.contains("可用空间不足"))
        assertFalse(paths.rootfs.exists())
    }

    @Test fun mirrorWhoseSumsDisagreeIsSkipped() = runBlocking {
        val liar = MockWebServer()
        liar.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("SHA256SUMS") -> MockResponse().setBody("${"a".repeat(64)} *$ubuntuName\n")
                request.method == "HEAD" -> MockResponse()
                else -> MockResponse().setBody(Buffer().write(ubuntuTar))
            }
        }
        liar.start()
        try {
            val log = Log()
            val m = mirrors(good).let { it.copy(ubuntuBase = listOf(Mirror("liar", liar.url("/ubuntu-base/").toString())) + it.ubuntuBase) }
            installer(m, log = log).run().toList()
            assertTrue(log.lines.any { it.contains("与内置哈希不一致") })
            assertTrue(liar.requestCount >= 1)
        } finally {
            liar.shutdown()
        }
    }

    @Test fun missingShmemIsHardPreflightFailureBeforeAnyDownload() {
        File(nativeDir, "libandroidshmem.so").delete()
        val log = Log()
        val e = assertThrows(InstallFailure::class.java) {
            runBlocking { installer(mirrors(good), log = log).run().toList() }
        }
        assertEquals(ErrCode.INSTALL_FAILED, e.code)
        assertTrue(e.message, e.message!!.contains("libandroidshmem.so"))
        assertTrue(e.message, e.message!!.contains("proot 无法启动"))
        assertEquals("预检失败前不应发出任何下载/测速请求", 0, good.requestCount)
        assertFalse(paths.rootfs.exists())
        assertFalse(File(paths.markers, "${InstallStep.ROOTFS.name}.done").exists())
    }

    @Test fun missingTallocIsAlsoHardFailure() {
        File(nativeDir, "libtalloc.so").delete()
        val e = assertThrows(InstallFailure::class.java) {
            runBlocking { installer(mirrors(good)).run().toList() }
        }
        assertTrue(e.message, e.message!!.contains("libtalloc.so"))
        assertEquals(0, good.requestCount)
    }

    @Test fun aptRunsWithShortForegroundTimeout() = runBlocking {
        val guest = Guest()
        installer(mirrors(good), guest).run().toList()
        val i = guest.commands.indexOfFirst { it.joinToString(" ").contains("apt-get") }
        assertTrue(i >= 0)
        assertTrue("APT 前台超时应只有几分钟：${guest.timeouts[i]}", guest.timeouts[i] <= DomesticInstaller.APT_FOREGROUND_TIMEOUT_MS)
        assertTrue(DomesticInstaller.APT_FOREGROUND_TIMEOUT_MS <= 5 * 60_000L)
    }

    /** 支持 `Range: bytes=N-` 的限速镜像；记录每个大包请求的 Range 头。 */
    private inner class SlowMirror : Dispatcher() {
        val ranges = java.util.Collections.synchronizedList(mutableListOf<String?>())
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!.substringBefore('?')
            val bytes = when {
                path.endsWith(ubuntuName) -> ubuntuTar
                path.endsWith(nodeName) -> nodeTar
                else -> return MockResponse().setResponseCode(404)
            }
            if (request.method == "HEAD") return MockResponse().setHeader("Content-Length", bytes.size)
            val range = request.getHeader("Range")
            ranges += range
            val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            val resp = if (from > 0) MockResponse().setResponseCode(206).setBody(Buffer().write(bytes.copyOfRange(from, bytes.size)))
            else MockResponse().setBody(Buffer().write(bytes))
            return resp.throttleBody(1024, 1, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    @Test fun allMirrorsTooSlowFallsBackToBestProbedMirrorAndFinishes() = runBlocking {
        val slowA = SlowMirror()
        val slowB = SlowMirror()
        val a = MockWebServer().apply { dispatcher = slowA; start() }
        val b = MockWebServer().apply { dispatcher = slowB; start() }
        try {
            val log = Log()
            var now = 0L
            // 每读一次时钟前进 1 s，门槛 10 MB/s：第一轮每个镜像都会被判「过慢」（包要分很多次读完）
            val judge = Downloader(client, clock = { now.also { now += 1_000 } }, slowWindowMs = 6_000, minBytesPerSec = 10_000_000)
            val progress = installer(mirrors(a, b), log = log, downloader = judge).run().toList()

            assertEquals(100, progress.last().percent)
            assertTrue(log.lines.any { it.contains("速度过慢") })
            assertTrue(log.lines.any { it.startsWith("所有镜像都较慢，回退到") })
            assertTrue(File(paths.rootfs, "usr/bin/sh").isFile)
            // 回退选测速排名第一的 A，并接着 A 自己的 .part 续传（B 的半截文件不拼进来）
            val aRanges = slowA.ranges.toList()
            assertTrue("A 应被请求两次以上（首轮 + 回退）：$aRanges", aRanges.size >= 2)
            assertTrue("回退时应从 A 自己的 .part 续传：$aRanges", aRanges.drop(1).any { it != null && !it.startsWith("bytes=0-") })
            assertTrue("B 不应在回退中被使用：${slowB.ranges}", slowB.ranges.size <= 2)
            // 成功后所有来源的 .part 都清掉
            assertTrue(Downloader.allParts(File(paths.downloads, ubuntuName)).isEmpty())
            assertTrue(Downloader.allParts(File(paths.downloads, nodeName)).isEmpty())
        } finally {
            a.shutdown(); b.shutdown()
        }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private sealed interface Entry {
        data class File(val name: String, val data: String, val mode: Int = 0b110_100_100) : Entry
        data class Dir(val name: String) : Entry
        data class Link(val name: String, val target: String) : Entry
    }

    private fun tgz(vararg entries: Entry): ByteArray {
        val bos = ByteArrayOutputStream()
        GzipCompressorOutputStream(bos).use { gz ->
            TarArchiveOutputStream(gz).use { out ->
                for (e in entries) when (e) {
                    is Entry.File -> {
                        val b = e.data.toByteArray()
                        out.putArchiveEntry(TarArchiveEntry(e.name).apply { size = b.size.toLong(); mode = e.mode })
                        out.write(b); out.closeArchiveEntry()
                    }
                    is Entry.Dir -> { out.putArchiveEntry(TarArchiveEntry(e.name)); out.closeArchiveEntry() }
                    is Entry.Link -> {
                        out.putArchiveEntry(TarArchiveEntry(e.name, TarConstants.LF_SYMLINK).apply { linkName = e.target })
                        out.closeArchiveEntry()
                    }
                }
            }
        }
        return bos.toByteArray()
    }
}
