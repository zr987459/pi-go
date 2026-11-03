package io.github.zr987459.pigo.core.runtime

import io.github.zr987459.pigo.core.runtime.net.Downloader
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloaderTest {
    private val tmp: File = Files.createTempDirectory("dl").toFile()
    private lateinit var a: MockWebServer
    private lateinit var b: MockWebServer
    private val client = OkHttpClient()
    private val content = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val sha = sha256(content)

    @Before fun setUp() {
        a = MockWebServer().apply { start() }
        b = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        a.shutdown(); b.shutdown(); tmp.deleteRecursively()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun body(bytes: ByteArray) = Buffer().write(bytes)

    @Test fun downloadsAndVerifies() = runBlocking {
        a.enqueue(MockResponse().setBody(body(content)))
        val dest = File(tmp, "x.tar.gz")
        Downloader(client).download(a.url("/x.tar.gz").toString(), dest, sha)
        assertEquals(sha, sha256(dest.readBytes()))
        assertTrue(Downloader.allParts(dest).isEmpty())
    }

    @Test fun resumesWithRangeOnlyForSameUrl() = runBlocking {
        val dest = File(tmp, "x.tar.gz")
        val url = a.url("/x.tar.gz").toString()
        // 上次从同一 URL 下了前 80 000 字节
        Downloader.partFile(dest, url).writeBytes(content.copyOfRange(0, 80_000))
        Downloader.srcFile(dest, url).writeText(url)
        a.enqueue(MockResponse().setResponseCode(206).setBody(body(content.copyOfRange(80_000, content.size))))

        Downloader(client).download(url, dest, sha)

        val req = a.takeRequest()
        assertEquals("bytes=80000-", req.getHeader("Range"))
        assertEquals(sha, sha256(dest.readBytes()))
    }

    @Test fun switchingMirrorNeverResumesFromOtherSource() = runBlocking {
        val dest = File(tmp, "x.tar.gz")
        val urlA = a.url("/x.tar.gz").toString()
        val urlB = b.url("/x.tar.gz").toString()
        // 残留来自镜像 A（内容甚至是错的），现在改用镜像 B
        Downloader.partFile(dest, urlA).writeBytes(ByteArray(50_000) { 7 })
        Downloader.srcFile(dest, urlA).writeText(urlA)
        b.enqueue(MockResponse().setBody(body(content)))

        Downloader(client).download(urlB, dest, sha)

        val req = b.takeRequest()
        assertNull("换镜像不应续传", req.getHeader("Range"))
        assertEquals(0, a.requestCount)
        assertEquals(sha, sha256(dest.readBytes()))
        assertTrue("成功后清掉所有来源的 .part", Downloader.allParts(dest).isEmpty())
    }

    @Test fun partWhoseSrcRecordDisagreesIsDiscarded() = runBlocking {
        // .part 文件名对应 URL B，但来源记录写的是 A（被改动/碰撞）：不得续传
        val dest = File(tmp, "x.tar.gz")
        val urlB = b.url("/x.tar.gz").toString()
        Downloader.partFile(dest, urlB).writeBytes(ByteArray(50_000) { 7 })
        Downloader.srcFile(dest, urlB).writeText(a.url("/x.tar.gz").toString())
        b.enqueue(MockResponse().setBody(body(content)))
        Downloader(client).download(urlB, dest, sha)
        assertNull(b.takeRequest().getHeader("Range"))
        assertEquals(sha, sha256(dest.readBytes()))
    }

    @Test fun legacyUnkeyedPartIsNeverResumed() = runBlocking {
        val dest = File(tmp, "x.tar.gz")
        val url = a.url("/x.tar.gz").toString()
        File(tmp, "x.tar.gz.part").writeBytes(ByteArray(50_000) { 7 })
        File(tmp, "x.tar.gz.part.src").writeText(url)
        a.enqueue(MockResponse().setBody(body(content)))
        Downloader(client).download(url, dest, sha)
        assertNull(a.takeRequest().getHeader("Range"))
        assertFalse(File(tmp, "x.tar.gz.part").exists())
        assertEquals(sha, sha256(dest.readBytes()))
    }

    @Test fun serverIgnoringRangeRestartsFromZero() = runBlocking {
        val dest = File(tmp, "x.tar.gz")
        val url = a.url("/x.tar.gz").toString()
        Downloader.partFile(dest, url).writeBytes(content.copyOfRange(0, 1000))
        Downloader.srcFile(dest, url).writeText(url)
        a.enqueue(MockResponse().setResponseCode(200).setBody(body(content)))
        Downloader(client).download(url, dest, sha)
        assertEquals(sha, sha256(dest.readBytes()))
    }

    @Test fun hashMismatchIsRejectedAndFileDeleted() {
        val dest = File(tmp, "x.tar.gz")
        val bad = content.copyOf().also { it[100] = (it[100] + 1).toByte() }
        a.enqueue(MockResponse().setBody(body(bad)))
        val e = assertThrows(Downloader.ChecksumMismatch::class.java) {
            runBlocking { Downloader(client).download(a.url("/x.tar.gz").toString(), dest, sha) }
        }
        assertEquals(sha, e.expected)
        assertFalse(dest.exists())
        assertTrue("校验失败的 .part 必须删除", Downloader.allParts(dest).isEmpty())
    }

    @Test fun corruptedPartFromSameUrlFailsChecksumAndIsDeleted() {
        // F5：.part 被人为改坏后续传，拼出来的文件哈希不符 → 删除
        val dest = File(tmp, "x.tar.gz")
        val url = a.url("/x.tar.gz").toString()
        Downloader.partFile(dest, url).writeBytes(ByteArray(80_000) { 1 })
        Downloader.srcFile(dest, url).writeText(url)
        a.enqueue(MockResponse().setResponseCode(206).setBody(body(content.copyOfRange(80_000, content.size))))
        assertThrows(Downloader.ChecksumMismatch::class.java) { runBlocking { Downloader(client).download(url, dest, sha) } }
        assertFalse(Downloader.partFile(dest, url).exists())
    }

    @Test fun alreadyVerifiedFileIsNotDownloadedAgain() = runBlocking {
        val dest = File(tmp, "x.tar.gz").apply { writeBytes(content) }
        Downloader(client).download(a.url("/x.tar.gz").toString(), dest, sha)
        assertEquals(0, a.requestCount)
    }

    @Test fun httpErrorKeepsNothingFromOtherSource() {
        val dest = File(tmp, "x.tar.gz")
        a.enqueue(MockResponse().setResponseCode(404))
        assertThrows(Downloader.HttpError::class.java) { runBlocking { Downloader(client).download(a.url("/x").toString(), dest, sha) } }
        assertFalse(dest.exists())
    }

    /** 每读一次时钟前进 1 s：6 s 窗口内只收到很少数据 → 按 10 MB/s 的门槛判为过慢。 */
    private fun slowJudge(): Downloader {
        var now = 0L
        return Downloader(client, clock = { now.also { now += 1_000 } }, slowWindowMs = 6_000, minBytesPerSec = 10_000_000)
    }

    private fun throttled(bytes: ByteArray, code: Int = 200) =
        MockResponse().setResponseCode(code).setBody(body(bytes)).throttleBody(16 * 1024, 1, TimeUnit.MILLISECONDS)

    @Test fun slowMirrorIsAbandonedButPartKeptForSameUrlResume() {
        val dest = File(tmp, "x.tar.gz")
        val url = a.url("/x.tar.gz").toString()
        a.enqueue(throttled(content))
        assertThrows(Downloader.TooSlow::class.java) { runBlocking { slowJudge().download(url, dest, sha) } }
        assertTrue(Downloader.partFile(dest, url).exists())
        assertEquals(url, Downloader.srcFile(dest, url).readText())
    }

    @Test fun tooSlowOnOneMirrorKeepsItsPartWhileAnotherMirrorIsTried() = runBlocking {
        val dest = File(tmp, "x.tar.gz")
        val urlA = a.url("/x.tar.gz").toString()
        val urlB = b.url("/x.tar.gz").toString()
        a.enqueue(throttled(content))
        b.enqueue(throttled(content))
        assertThrows(Downloader.TooSlow::class.java) { runBlocking { slowJudge().download(urlA, dest, sha) } }
        val keptA = Downloader.partFile(dest, urlA).length()
        assertTrue(keptA > 0)
        // 换到 B 也慢：A 的 .part 不应被删
        assertThrows(Downloader.TooSlow::class.java) { runBlocking { slowJudge().download(urlB, dest, sha) } }
        assertEquals("换线不应删除其他镜像的 .part", keptA, Downloader.partFile(dest, urlA).length())
        assertTrue(Downloader.partFile(dest, urlB).exists())

        // 回到 A、关闭过慢判定：从 A 自己的 .part 续传，下完并校验通过
        a.takeRequest()
        a.enqueue(throttled(content.copyOfRange(keptA.toInt(), content.size), code = 206))
        slowJudge().download(urlA, dest, sha, detectSlow = false)
        assertEquals("bytes=$keptA-", a.takeRequest().getHeader("Range"))
        assertEquals(sha, sha256(dest.readBytes()))
        assertTrue(Downloader.allParts(dest).isEmpty())
    }

    @Test fun slowDetectionCanBeDisabled() = runBlocking {
        val dest = File(tmp, "x.tar.gz")
        a.enqueue(throttled(content))
        slowJudge().download(a.url("/x.tar.gz").toString(), dest, sha, detectSlow = false)
        assertEquals(sha, sha256(dest.readBytes()))
    }
}
