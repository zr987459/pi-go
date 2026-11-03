package io.github.zr987459.pigo.feature.files

import io.github.zr987459.pigo.feature.files.share.ShareCache
import io.github.zr987459.pigo.feature.files.share.ShareUriPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** 审查第 1 轮：分享入口的借权读取防护与缓存清理（纯 JVM 部分；Activity 级见 androidTest）。 */
class ShareSecurityTest {
    private val pkg = "io.github.zr987459.pigo"
    private val own = setOf("$pkg.pigo.files", "$pkg.androidx-startup")

    private fun ok(scheme: String?, auth: String?) = ShareUriPolicy.isAllowed(scheme, auth, pkg, own)

    @Test
    fun rejectsFileSchemeAndOwnProviders() {
        assertFalse("file:// 指向私有目录", ok("file", null))
        assertFalse(ok("FILE", ""))
        assertFalse("本 App FileProvider", ok("content", "$pkg.pigo.files"))
        assertFalse("本包前缀的其他 provider", ok("content", "$pkg.internal"))
        assertFalse("带 userInfo 的伪装", ok("content", "evil@$pkg.pigo.files"))
        assertFalse("大小写", ok("content", "IO.GITHUB.ZR987459.PIGO.PIGO.FILES"))
        assertFalse("没有 authority", ok("content", null))
        assertFalse(ok("http", "example.com"))
        assertFalse(ok("android.resource", "com.other"))
    }

    @Test
    fun acceptsOtherAppsContentUris() {
        assertTrue(ok("content", "com.android.providers.media.documents"))
        assertTrue(ok("content", "com.google.android.apps.photos.contentprovider"))
        assertTrue("包名只是前缀相似但不同包", ok("content", "${pkg}x.files"))
    }

    @Test
    fun copyLimitedDropsOversizeFiles() {
        val dir = Files.createTempDirectory("pigo-share").toFile()
        try {
            val small = File(dir, "a")
            assertTrue(ShareCache.copyLimited(ByteArrayInputStream(ByteArray(10)), small, 10))
            assertEquals(10L, small.length())
            val big = File(dir, "b")
            assertFalse(ShareCache.copyLimited(ByteArrayInputStream(ByteArray(11)), big, 10))
            assertFalse("超限不留半成品", big.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun deleteOlderKeepsFreshFilesAndSkipsInbox() {
        val dir = Files.createTempDirectory("pigo-share").toFile()
        try {
            val now = System.currentTimeMillis()
            val old = File(dir, "old.txt").apply { writeText("x"); setLastModified(now - 7_200_000) }
            val fresh = File(dir, "fresh.txt").apply { writeText("x") }
            val inbox = File(dir, "inbox").apply { mkdirs(); setLastModified(now - 7_200_000) }
            ShareCache.deleteOlder(dir, now - ShareCache.OUTGOING_TTL_MS, skipDirNamed = "inbox")
            assertFalse(old.exists())
            assertTrue(fresh.exists())
            assertTrue("inbox 另按 24 小时清理", inbox.exists())
        } finally { dir.deleteRecursively() }
    }
}
