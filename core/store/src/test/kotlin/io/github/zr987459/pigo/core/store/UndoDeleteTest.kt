package io.github.zr987459.pigo.core.store

import io.github.zr987459.pigo.contract.Outcome
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** DESIGN-r3 §7.3：删除进回收目录，5 秒内撤销后文件回到原路径，内容一致。 */
class UndoDeleteTest {
    private lateinit var rootfs: File
    private lateinit var paths: GuestPaths
    private var now = 1_000_000L

    @Before fun setUp() {
        rootfs = Files.createTempDirectory("pigo-undo").toFile()
        paths = GuestPaths(rootfs)
    }

    @After fun tearDown() { rootfs.deleteRecursively() }

    private fun repo() = FileSessionRepository(paths, Dispatchers.Unconfined) { now }

    private fun session(name: String = "2026-11-04T00-00-00_abc.jsonl"): Pair<File, String> {
        val f = File(paths.sessionsDir, "--root-pi-cwd--/$name")
        f.parentFile.mkdirs()
        f.writeText("""{"type":"session","version":3,"id":"abc","timestamp":"t","cwd":"/root/pi-cwd"}""" + "\n")
        return f to paths.guestPath(f)!!
    }

    @Test fun deleteGoesToTrashAndUndoWithin5sRestoresSamePathAndContent() = runTest {
        val (f, guest) = session()
        val content = f.readBytes()
        val r = repo()
        val pending = (r.deleteWithUndo(guest) as Outcome.Ok).value
        assertFalse(f.exists())
        assertTrue(paths.isInside(paths.hostFile(pending.trashPath), paths.trashDir))
        assertTrue(paths.hostFile(pending.trashPath).isFile)
        assertEquals(now + 5_000, pending.undoUntilMillis)

        now += 4_999
        val back = r.undoLastDelete()
        assertEquals(Outcome.Ok(guest), back)
        assertTrue(f.isFile)
        assertTrue(content.contentEquals(f.readBytes()))
        assertFalse(paths.hostFile(pending.trashPath).exists())
    }

    @Test fun undoAfter5sFailsAndFileStaysInTrash() = runTest {
        val (f, guest) = session()
        val r = repo()
        val pending = (r.deleteWithUndo(guest) as Outcome.Ok).value
        now += 5_001
        assertTrue(r.undoLastDelete() is Outcome.Err)
        assertFalse(f.exists())
        assertTrue(paths.hostFile(pending.trashPath).isFile)
    }

    @Test fun undoWithoutDeleteFails() = runTest {
        assertTrue(repo().undoLastDelete() is Outcome.Err)
    }

    @Test fun undoTwiceOnlyFirstSucceeds() = runTest {
        val (_, guest) = session()
        val r = repo()
        r.deleteWithUndo(guest)
        assertTrue(r.undoLastDelete() is Outcome.Ok)
        assertTrue(r.undoLastDelete() is Outcome.Err)
    }

    @Test fun undoRestoresOriginalNameEvenWhenTrashHadSameName() = runTest {
        val r = repo()
        val (f1, g1) = session()
        r.deleteWithUndo(g1)
        val (f2, g2) = session() // 同名会话再次删除，回收目录里会加 ~1 后缀
        f2.appendText("second\n")
        val content = f2.readBytes()
        val p2 = (r.deleteWithUndo(g2) as Outcome.Ok).value
        assertTrue(p2.trashPath.endsWith("~1.jsonl"))
        assertEquals(Outcome.Ok(g2), r.undoDelete(p2))
        assertTrue(f1.isFile && content.contentEquals(f1.readBytes()))
    }
}
