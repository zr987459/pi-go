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
import org.junit.Assert.assertTrue
import org.junit.Test

/** r3/core：`@文件` 模糊搜索（行为照 pi-tui getFuzzyFileSuggestions，见 FileSearch 文档）。 */
class AtFileSearchTest {
    private val tmp: File = Files.createTempDirectory("pigo-at").toFile()
    private val rootfs = File(tmp, "rootfs")
    private val proj = File(rootfs, "root/proj")

    @After
    fun tearDown() { tmp.deleteRecursively() }

    private fun touch(rel: String, text: String = "x") = File(proj, rel).apply { parentFile.mkdirs(); writeText(text) }

    private fun ws() = RootfsWorkspace(rootfs, { _, _ -> ExecResult(1, "", "") }, Dispatchers.Unconfined)

    private fun search(q: String, limit: Int = 20) = runBlocking {
        (ws().searchFiles("/root/proj", q, limit) as Outcome.Ok).value
    }

    @Test
    fun scoringOrderMatchesPi() {
        touch("main.kt")
        touch("src/main.kt")
        touch("src/main/App.kt")
        touch("src/domain.kt")
        touch("docs/notes-main.md")
        touch("mainline/readme.md")
        val r = search("main")
        // 不含 / 时 fd 只按文件名过滤：src/main/App.kt、mainline/readme.md 不是候选
        assertEquals(listOf("src/main", "mainline", "main.kt", "src/main.kt", "src/domain.kt", "docs/notes-main.md"), r.map { it.path })
        assertEquals(listOf(110, 90, 80, 80, 50, 50), r.map { it.score })
        assertTrue(r[0].isDir)
        // 含 / 时按相对路径过滤，才会出现「路径含查询」30 分
        val p = search("src/main")
        assertTrue(p.any { it.path == "src/main/App.kt" && it.score == 30 })
        assertEquals("@src/main/", r[0].completion)
        assertEquals("/root/proj/src/main", r[0].guestPath)
    }

    @Test
    fun hiddenIncludedGitExcludedGitignoreHonoured() {
        touch(".env.local")
        touch(".git/config")
        touch("build/out.kt")
        touch("app/build/gen.kt")
        touch("keep.log")
        touch("a/b.log")
        touch("app/src/Real.kt")
        touch(".gitignore", "# 注释\nbuild/\n*.log\n")
        touch("app/.gitignore", "/src/Real.kt\n")
        val all = search("").map { it.path }
        assertTrue("隐藏文件（--hidden）", ".env.local" in all)
        assertFalse(".git 排除", all.any { it == ".git" || it.startsWith(".git/") })
        val kt = search(".kt").map { it.path }
        assertFalse("build/ 忽略（任意层级）", kt.any { it.contains("build/") })
        assertFalse("子目录 .gitignore 锚定规则", "app/src/Real.kt" in kt)
        assertTrue(search("log").none { it.path.endsWith(".log") })
    }

    @Test
    fun smartCaseAndPathQueries() {
        touch("src/ui/Theme.kt")
        touch("src/ui/theme.json")
        touch("lib/ui-kit/Theme.kt")
        assertEquals(3, search("theme").size)
        assertEquals("含大写区分大小写", setOf("src/ui/Theme.kt", "lib/ui-kit/Theme.kt"), search("Theme").map { it.path }.toSet())
        val p = search("ui/the").map { it.path }
        assertEquals("含 / 时匹配相对路径", setOf("src/ui/Theme.kt", "src/ui/theme.json"), p.toSet())
        assertEquals(listOf("src/ui"), search("ui/").filter { it.isDir }.map { it.path })
    }

    @Test
    fun emptyQueryListsFirstLevelFirstAndLimit() {
        touch("z.txt"); touch("a/b/c.txt"); touch("m.txt")
        val r = search("", limit = 3)
        assertEquals(3, r.size)
        assertTrue("空查询：浅层优先", r.all { !it.path.contains('/') })
    }

    @Test
    fun quotingAndSymlinkEscape() {
        touch("my notes.md")
        assertEquals("@\"my notes.md\"", search("notes").single().completion)
        val outside = File(tmp, "outside-secret").apply { mkdirs(); File(this, "secret.txt").writeText("s") }
        Files.createSymbolicLink(File(proj, "leak").toPath(), outside.toPath())
        assertTrue("不跟随指向 rootfs 外的链接", search("secret").isEmpty())
        val inside = File(rootfs, "root/shared").apply { mkdirs(); File(this, "shared.txt").writeText("s") }
        Files.createSymbolicLink(File(proj, "link-in").toPath(), inside.toPath())
        assertEquals(listOf("link-in/shared.txt"), search("shared.txt").map { it.path })
    }

    @Test
    fun deniedOutsideAllowedRoots() = runBlocking {
        File(rootfs, "etc").mkdirs()
        assertTrue(ws().searchFiles("/etc", "x") is Outcome.Err)
        assertTrue(ws().searchFiles("/root/../etc", "x") is Outcome.Err)
    }

    @Test
    fun scoreFunction() {
        assertEquals(100, FileSearch.score("a/Main.kt", "main.kt", false))
        assertEquals(90, FileSearch.score("a/mainx", "main", true))
        assertEquals(0, FileSearch.score("a/b", "zzz", true))
        assertTrue(FileSearch.fdMatches("src/ui/Theme.kt", "ui/th"))
        assertFalse(FileSearch.fdMatches("src/ui/Theme.kt", "Ui/th"))
    }
}
