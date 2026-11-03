package io.github.zr987459.pigo.core.workspace

import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.Outcome
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** r3/core：RootfsWorkspace 收缩后的 branchName()、只列目录、读文件，以及 git 兼容存根（DESIGN-r3 §1.1 X5、§1.2）。 */
class WorkspaceShrinkTest {
    private val tmp: File = Files.createTempDirectory("pigo-shrink").toFile()
    private val rootfs = File(tmp, "rootfs")
    private val execCalls = ArrayList<List<String>>()
    private var execResult = ExecResult(1, "", "")

    @After
    fun tearDown() { tmp.deleteRecursively() }

    private fun ws() = RootfsWorkspace(rootfs, { cmd, _ -> execCalls += cmd; execResult }, Dispatchers.Unconfined)
    private fun f(guest: String, text: String? = null) = File(rootfs, guest.removePrefix("/")).apply {
        if (text == null) mkdirs() else { parentFile.mkdirs(); writeText(text) }
    }

    @Test
    fun branchNameLikePiFooter() = runBlocking {
        f("/root/repo/.git/HEAD", "ref: refs/heads/feature/x\n")
        f("/root/repo/src/deep")
        assertEquals("feature/x", ws().branchName("/root/repo/src/deep"))
        f("/root/det/.git/HEAD", "3f2a9c0d\n")
        assertEquals("detached", ws().branchName("/root/det"))
        f("/root/plain")
        assertNull(ws().branchName("/root/plain"))
        assertTrue("不调用 git 进程", execCalls.isEmpty())
    }

    @Test
    fun branchNameWorktreeAndReftable() = runBlocking {
        f("/root/main/.git/worktrees/wt/HEAD", "ref: refs/heads/wt-branch\n")
        f("/root/wt/.git", "gitdir: /root/main/.git/worktrees/wt\n")
        assertEquals("wt-branch", ws().branchName("/root/wt"))
        f("/root/rel/.git", "gitdir: ../main/.git/worktrees/wt\n")
        assertEquals("相对 gitdir", "wt-branch", ws().branchName("/root/rel"))
        f("/root/rt/.git/HEAD", "ref: refs/heads/.invalid\n")
        execResult = ExecResult(0, "real\n", "")
        assertEquals("reftable 用 git symbolic-ref 兜底", "real", ws().branchName("/root/rt"))
        assertTrue(execCalls.single().containsAll(listOf("symbolic-ref", "--short", "HEAD")))
        execResult = ExecResult(1, "", "")
        assertEquals("detached", ws().branchName("/root/rt"))
    }

    @Test
    fun listDirectoriesOnlyDirs() = runBlocking {
        f("/root/a"); f("/root/b/c"); f("/root/file.txt", "x"); f("/root/.hidden")
        val names = (ws().listDirectories("/root") as Outcome.Ok).value.map { it.name }
        assertEquals(listOf("a", "b"), names)
        assertEquals(listOf(".hidden", "a", "b"), (ws().listDirectories("/root", showHidden = true) as Outcome.Ok).value.map { it.name })
        assertTrue(ws().listDirectories("/etc") is Outcome.Err)
    }

    @Test
    fun readFileForAtExpansion() = runBlocking {
        f("/root/p/a.kt", "fun main() {}\n")
        val t = (ws().readFile("/root/p/a.kt") as Outcome.Ok).value
        assertEquals("fun main() {}\n", t.text)
        assertFalse(t.truncated)
        assertTrue((ws().readFile("/root/p/a.kt", maxBytes = 3) as Outcome.Ok).value.truncated)
    }

    @Suppress("DEPRECATION")
    @Test
    fun gitCompatStubs() = runBlocking {
        f("/root/repo/.git/HEAD", "ref: refs/heads/main\n")
        f("/root/plain")
        val st = (ws().gitStatus("/root/repo") as Outcome.Ok).value
        assertTrue(st.isRepo); assertEquals("main", st.branch); assertTrue("不再列改动", st.files.isEmpty())
        assertFalse((ws().gitStatus("/root/plain") as Outcome.Ok).value.isRepo)
        assertTrue("未登记的目录仍拒绝", ws().gitStatus("/root/../etc") is Outcome.Err)
        assertTrue(ws().gitDiff("/root/repo", null, false) is Outcome.Err)
        assertTrue(ws().gitWorktrees("/root/repo") is Outcome.Err)
        assertTrue("不再执行 git", execCalls.isEmpty())
    }

    @Test
    fun gitParseDeletedAndUnreferenced() {
        val main = File("src/main/kotlin/io/github/zr987459/pigo/core/workspace")
        assertTrue(main.isDirectory)
        assertFalse("GitParse.kt 已删除", File(main, "GitParse.kt").exists())
        // 全仓 src/main 不再引用 GitParse（§8.5「GitParse 已删且无引用」）
        val repo = File("../..").canonicalFile
        val refs = repo.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" && it.path.contains("/src/") }
            .filter { Regex("""\bGitParse\.""").containsMatchIn(it.readText()) }
            .map { it.relativeTo(repo).path }
            .filterNot { it.endsWith("WorkspaceShrinkTest.kt") }
            .toList()
        assertEquals("引用 GitParse 的文件：$refs", emptyList<String>(), refs)
    }
}
