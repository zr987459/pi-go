package io.github.zr987459.pigo.feature.files

import io.github.zr987459.pigo.feature.files.platform.FileShare
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 分享副本清理（原 ShareSecurityTest 的清理用例；系统分享入口已按 DESIGN-r3 X10 删除）。 */
class FileShareCleanTest {
    @Test
    fun deleteOlderKeepsFreshFilesAndDropsLegacyInbox() {
        val dir = Files.createTempDirectory("pigo-share").toFile()
        try {
            val now = System.currentTimeMillis()
            val old = File(dir, "old.txt").apply { writeText("x"); setLastModified(now - 7_200_000) }
            val fresh = File(dir, "fresh.txt").apply { writeText("x") }
            val inbox = File(dir, "inbox").apply { mkdirs(); File(this, "a.png").writeText("x") }
            FileShare.deleteOlder(dir, now - FileShare.OUTGOING_TTL_MS)
            assertFalse(old.exists())
            assertTrue(fresh.exists())
            assertFalse("旧版分享收件目录整个清掉", inbox.exists())
        } finally { dir.deleteRecursively() }
    }
}
