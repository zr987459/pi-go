package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.core.workspace.RootfsWorkspace
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** r3/app：`@文件` 候选走 core/workspace（RootfsWorkspace → FileSearch），搜索根是会话 cwd。 */
class AppFileSearchTest {
    private val tmp: File = Files.createTempDirectory("pigo-atapp").toFile()
    private val rootfs = File(tmp, "rootfs")

    @After
    fun tearDown() { tmp.deleteRecursively() }

    private fun touch(path: String) = File(rootfs, path.removePrefix("/")).apply { parentFile.mkdirs(); writeText("x") }

    private val ws get() = RootfsWorkspace(rootfs, { _, _ -> ExecResult(1, "", "") }, Dispatchers.Unconfined)

    @Test
    fun suggestsFromSessionCwdWithPiScoring() = runBlocking {
        touch("/root/proj/src/Main.kt")
        touch("/root/proj/main.md")
        touch("/root/other/main.txt")
        var cwd: String? = "/root/proj"
        val s = AppFileSearch(ws) { cwd }
        val hits = s.suggest("main")
        // 文件名以查询开头（80）优先于其他；只在会话 cwd 下找
        assertEquals(listOf("main.md", "src/Main.kt"), hits.map { it.path })
        assertTrue(hits.none { it.guestPath.startsWith("/root/other") })
        cwd = "/root/other"
        assertEquals(listOf("main.txt"), s.suggest("main").map { it.path })
    }

    @Test
    fun cwdOutsideHomeIsAllowedAndDirectoriesGetTrailingSlash() = runBlocking {
        touch("/work/app/lib/a.kt")
        val s = AppFileSearch(ws) { "/work/app" }
        val hits = s.suggest("lib")
        assertEquals("lib", hits.first().path)
        assertTrue(hits.first().isDir)
        assertEquals("lib/", io.github.zr987459.pigo.feature.chat.ui.calm.atRefPath(hits.first()))
    }

    @Test
    fun noEngineCwdMeansNoCandidates() = runBlocking {
        assertTrue(AppFileSearch(ws) { null }.suggest("a").isEmpty())
    }
}
