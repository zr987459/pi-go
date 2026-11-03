package io.github.zr987459.pigo.core.workspace

import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * 用宿主真实 git 跑 [RootfsWorkspace.gitDiff]（容器路径 ↔ 宿主路径在 exec 里换算）。宿主没有 git 时跳过。
 * 覆盖审查第 1 轮：干净文件、只有暂存改动、被忽略的文件不得生成「全部新增」；未跟踪文件生成，且头路径相对仓库根。
 */
class GitDiffRealTest {
    private lateinit var rootfs: File
    private lateinit var ws: RootfsWorkspace
    private val repo get() = File(rootfs, "root/proj")

    private fun hostGit(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-C", dir.path) + args).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor(30, TimeUnit.SECONDS)
        check(p.exitValue() == 0) { "git ${args.toList()} 失败：$out" }
        return out
    }

    @Before
    fun setUp() {
        assumeTrue("宿主没有 git", runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false))
        rootfs = Files.createTempDirectory("pigo-git").toFile().canonicalFile
        repo.mkdirs()
        hostGit(repo, "init", "-q")
        hostGit(repo, "config", "user.email", "t@t")
        hostGit(repo, "config", "user.name", "t")
        File(repo, "clean.kt").writeText("a\nb\n")
        File(repo, "staged.kt").writeText("x\n")
        File(repo, ".gitignore").writeText("ignored.log\n")
        File(repo, "sub").mkdirs()
        File(repo, "sub/mod.kt").writeText("1\n")
        hostGit(repo, "add", "-A")
        hostGit(repo, "commit", "-qm", "init")
        File(repo, "staged.kt").writeText("x\ny\n")
        hostGit(repo, "add", "staged.kt")
        File(repo, "sub/mod.kt").writeText("1\n2\n")
        File(repo, "ignored.log").writeText("secret\n")
        File(repo, "new.kt").writeText("n1\nn2\n")
        File(repo, "sub/newsub.kt").writeText("s1\ns2\n")
        File(repo, "empty.txt").writeText("")

        val root = rootfs.path
        ws = RootfsWorkspace(rootfs, { cmd, _ ->
            // 容器绝对路径（-C 目录、绝对路径的 pathspec）换成宿主路径
            val host = cmd.mapIndexed { i, a -> if (i > 0 && a.startsWith("/")) root + a else a }
            val p = ProcessBuilder(host).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
            p.waitFor(30, TimeUnit.SECONDS)
            ExecResult(p.exitValue(), out.replace(root, ""), err)
        }, Dispatchers.Unconfined)
        runBlocking { ws.roots("/root/proj") }
    }

    @After
    fun tearDown() { if (::rootfs.isInitialized) rootfs.deleteRecursively() }

    private fun diff(path: String, staged: Boolean = false, cwd: String = "/root/proj") = runBlocking {
        when (val r = ws.gitDiff(cwd, path, staged)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> throw AssertionError("gitDiff($path) 失败：${r.message}", r.cause)
        }
    }

    @Test
    fun cleanStagedOnlyAndIgnoredFilesGetNoAddedPatch() {
        assertEquals("干净的已跟踪文件", emptyList<Any>(), diff("clean.kt"))
        assertEquals("绝对路径的干净文件", emptyList<Any>(), diff("/root/proj/clean.kt"))
        assertEquals("只有暂存改动（看工作区）", emptyList<Any>(), diff("staged.kt"))
        assertEquals(1, diff("staged.kt", staged = true).single().added)
        assertEquals("被 .gitignore 忽略", emptyList<Any>(), diff("ignored.log"))
    }

    @Test
    fun untrackedFileGetsAddedPatchWithRepoRelativeHeader() {
        val d = diff("/root/proj/new.kt").single()
        assertEquals("new.kt", d.path)
        assertEquals(2, d.added)
        assertEquals("new.kt", diff("new.kt").single().path)
        assertEquals("sub/mod.kt", diff("/root/proj/sub/mod.kt").single().path)
        val e = diff("empty.txt").single()
        assertEquals("空文件不生成 hunk", 0, e.hunks.size)
        assertEquals(0, e.added)
    }

    /** 审查第 2 轮回归：cwd 为仓库子目录时，未跟踪文件（在 cwd 内外）都要生成「全部新增」，头路径仍相对仓库根。 */
    @Test
    fun untrackedFileDiffWhenCwdIsRepoSubdirectory() {
        val sub = "/root/proj/sub"
        val outside = diff("new.kt", cwd = sub).single()
        assertEquals("cwd 之外（仓库根下）的未跟踪文件", "new.kt", outside.path)
        assertEquals(2, outside.added)
        assertEquals("new.kt", diff("/root/proj/new.kt", cwd = sub).single().path)
        val inside = diff("sub/newsub.kt", cwd = sub).single()
        assertEquals("cwd 之内的未跟踪文件", "sub/newsub.kt", inside.path)
        assertEquals(2, inside.added)
        assertEquals("sub/newsub.kt", diff("/root/proj/sub/newsub.kt", cwd = sub).single().path)
        assertEquals("已跟踪改动不受影响", "sub/mod.kt", diff("sub/mod.kt", cwd = sub).single().path)
        assertEquals("子目录下干净文件仍为空", emptyList<Any>(), diff("clean.kt", cwd = sub))
        assertEquals("子目录下被忽略文件仍为空", emptyList<Any>(), diff("ignored.log", cwd = sub))
    }

    @Test
    fun gitEntryPointsDoNotWidenAllowedRoots() = runBlocking {
        assertTrue("未登记的 / 不能 gitStatus", ws.gitStatus("/") is Outcome.Err)
        assertTrue("gitStatus 失败后 /etc 仍不可读", ws.list("/etc") is Outcome.Err)
        assertTrue(ws.gitDiff("/tmp", null, false) is Outcome.Err)
    }

    @Test
    fun repoRelativeMapping() {
        assertEquals("a/b.kt", RootfsWorkspace.repoRelative("/root/proj", "/root/proj/a/b.kt"))
        assertEquals("a/b.kt", RootfsWorkspace.repoRelative("/root/proj", "a/b.kt"))
        assertNull(RootfsWorkspace.repoRelative("/root/proj", "/root/other/x"))
        assertNull(RootfsWorkspace.repoRelative("/root/proj", "../x"))
        assertEquals("x", RootfsWorkspace.repoRelative("/", "/x"))
    }
}
