package io.github.zr987459.pigo.core.engine

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** r3/core：trust.json 读写与 pi 1.5.0 格式一致（docs/protocol/r3-measurements.md §2）。 */
class ProjectTrustFileTest {
    private val dir: File = Files.createTempDirectory("pigo-trust").toFile()
    private val file = File(dir, "trust.json")

    @After
    fun tearDown() { dir.deleteRecursively() }

    @Test
    fun writesPiFormatAndKeepsOtherKeys() {
        file.writeText("{\n  \"/root/b\": false,\n  \"/root/z\": null\n}\n")
        val t = ProjectTrustFile(file)
        t.set("/root/a/../w/", true)
        assertEquals("{\n  \"/root/b\": false,\n  \"/root/w\": true,\n  \"/root/z\": null\n}\n", file.readText())
        assertEquals(true, t.get("/root/w"))
        assertEquals("向上找最近的祖先", true, t.get("/root/w/sub/dir"))
        assertEquals(false, t.get("/root/b"))
        assertNull(t.get("/root/z"))
        assertNull(t.get("/tmp"))
        t.set("/root/w", null)
        assertFalse(file.readText().contains("/root/w\""))
        assertFalse("锁已释放", File(dir, "trust.json.lock").exists())
    }

    @Test
    fun emptyAndUnicode() {
        val t = ProjectTrustFile(file)
        assertNull(t.get("/root/x"))
        t.set("/root/项目 一", true)
        assertEquals("{\n  \"/root/项目 一\": true\n}\n", file.readText())
        t.set("/root/项目 一", null)
        assertEquals("{}\n", file.readText())
    }

    @Test
    fun staleLockIsBroken() {
        val lock = File(dir, "trust.json.lock").apply { mkdirs() }
        lock.setLastModified(System.currentTimeMillis() - 60_000)
        ProjectTrustFile(file).set("/root/a", true)
        assertTrue(file.readText().contains("/root/a"))
    }

    @Test(expected = IOException::class)
    fun refusesToOverwriteInvalid() {
        file.writeText("[1,2]")
        ProjectTrustFile(file).set("/root/a", true)
    }

    @Test
    fun invalidLeftUntouched() {
        file.writeText("{\"/a\": 3}")
        runCatching { ProjectTrustFile(file).set("/root/a", true) }
        assertEquals("{\"/a\": 3}", file.readText())
    }
}
