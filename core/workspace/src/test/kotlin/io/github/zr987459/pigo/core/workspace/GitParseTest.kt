package io.github.zr987459.pigo.core.workspace

import io.github.zr987459.pigo.contract.DiffLineKind
import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GitParseTest {
    // git status --porcelain=v2 -z --branch --untracked-files=all 的真实输出（git 2.43，按 NUL 分隔）
    private val status = listOf(
        "# branch.oid 1b2c3d4e5f60718293a4b5c6d7e8f90123456789",
        "# branch.head main",
        "# branch.upstream origin/main",
        "# branch.ab +2 -1",
        "1 .M N... 100644 100644 100644 e69de29bb2d1d6434b8b29ae775ad8c2e48c5391 e69de29bb2d1d6434b8b29ae775ad8c2e48c5391 src/main.kt",
        "1 A. N... 000000 100644 100644 0000000000000000000000000000000000000000 3b18e512dba79e4c8300dd08aeb37f8e728b8dad 新文件 含空格.md",
        "1 D. N... 100644 000000 000000 e69de29bb2d1d6434b8b29ae775ad8c2e48c5391 0000000000000000000000000000000000000000 old.txt",
        "2 R. N... 100644 100644 100644 e69de29bb2d1d6434b8b29ae775ad8c2e48c5391 e69de29bb2d1d6434b8b29ae775ad8c2e48c5391 R100 docs/new name.md",
        "docs/old name.md",
        "u UU N... 100644 100644 100644 100644 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb cccccccccccccccccccccccccccccccccccccccc conflict.kt",
        "? notes/todo.txt",
        "! build/out.o",
        "x 未知记录",
        "",
    ).joinToString("\u0000")

    @Test
    fun porcelainV2IsParsed() {
        val s = GitParse.porcelainV2(status)
        assertEquals("main", s.branch)
        assertEquals("origin/main", s.upstream)
        assertEquals(2, s.ahead)
        assertEquals(1, s.behind)
        val by = s.entries.associateBy { it.path }
        assertEquals(GitParse.Kind.MODIFIED, by.getValue("src/main.kt").kind)
        assertEquals('.', by.getValue("src/main.kt").index)
        assertEquals('M', by.getValue("src/main.kt").worktree)
        assertEquals(GitParse.Kind.ADDED, by.getValue("新文件 含空格.md").kind)
        assertEquals(GitParse.Kind.DELETED, by.getValue("old.txt").kind)
        assertEquals(GitParse.Kind.RENAMED, by.getValue("docs/new name.md").kind)
        assertEquals("docs/old name.md", by.getValue("docs/new name.md").originalPath)
        assertEquals(GitParse.Kind.CONFLICT, by.getValue("conflict.kt").kind)
        assertEquals(GitParse.Kind.UNTRACKED, by.getValue("notes/todo.txt").kind)
        assertEquals(GitParse.Kind.IGNORED, by.getValue("build/out.o").kind)
        assertEquals(7, s.entries.size)
    }

    @Test
    fun detachedHeadAndEmptyOutput() {
        val s = GitParse.porcelainV2("# branch.oid abc\u0000# branch.head (detached)\u0000")
        assertEquals("(分离头指针)", s.branch)
        assertTrue(s.entries.isEmpty())
        assertTrue(GitParse.porcelainV2("").entries.isEmpty())
    }

    private val diff = """
        diff --git a/src/main.kt b/src/main.kt
        index e69de29..3b18e51 100644
        --- a/src/main.kt
        +++ b/src/main.kt
        @@ -1,4 +1,5 @@ fun main() {
         fun main() {
        -    println("hi")
        +    println("hello")
        +    println("--- 不是文件头")
         }
        ${""}
        @@ -10 +11 @@
        -old
        +new
        \ No newline at end of file
        diff --git a/new.txt b/new.txt
        new file mode 100644
        index 0000000..ce01362
        --- /dev/null
        +++ b/new.txt
        @@ -0,0 +1,2 @@
        +a
        ++++ 也不是文件头
        diff --git a/logo.png b/logo.png
        index 1111111..2222222 100644
        Binary files a/logo.png and b/logo.png differ
        diff --git a/gone.txt b/gone.txt
        deleted file mode 100644
        --- a/gone.txt
        +++ /dev/null
        @@ -1 +0,0 @@
        -bye
    """.trimIndent()

    @Test
    fun unifiedDiffIsParsedWithLineNumbers() {
        val files = GitParse.unifiedDiff(diff)
        assertEquals(listOf("src/main.kt", "new.txt", "logo.png", "gone.txt"), files.map { it.path })
        val main = files[0]
        assertEquals(2, main.hunks.size)
        val h = main.hunks[0]
        assertEquals(1, h.oldStart); assertEquals(4, h.oldCount); assertEquals(1, h.newStart); assertEquals(5, h.newCount)
        assertEquals(listOf(GitParse.LineType.CONTEXT, GitParse.LineType.DEL, GitParse.LineType.ADD, GitParse.LineType.ADD, GitParse.LineType.CONTEXT, GitParse.LineType.CONTEXT), h.lines.map { it.type })
        assertEquals("    println(\"--- 不是文件头\")", h.lines[3].text)
        assertEquals(2, h.lines[1].oldNo); assertNull(h.lines[1].newNo)
        assertEquals(3, h.lines[3].newNo)
        assertEquals("", h.lines[5].text)
        val h2 = main.hunks[1]
        assertEquals(listOf(GitParse.LineType.DEL, GitParse.LineType.ADD, GitParse.LineType.NO_NEWLINE), h2.lines.map { it.type })
        assertEquals(10, h2.lines[0].oldNo); assertEquals(11, h2.lines[1].newNo)
        assertEquals(3, main.additions); assertEquals(2, main.deletions)

        val added = files[1]
        assertTrue(added.newFile); assertNull(added.oldPath)
        assertEquals(listOf("a", "+++ 也不是文件头"), added.hunks.single().lines.map { it.text })
        assertTrue(files[2].binary)
        assertTrue(files[3].deletedFile); assertNull(files[3].newPath); assertEquals("gone.txt", files[3].path)
    }

    @Test
    fun addedFilePatchRoundTrips() {
        val p = GitParse.addedFilePatch("a.txt", "x\ny")
        val f = GitParse.unifiedDiff(p).single()
        assertEquals(listOf("x", "y"), f.hunks.single().lines.filter { it.type == GitParse.LineType.ADD }.map { it.text })
        assertEquals(GitParse.LineType.NO_NEWLINE, f.hunks.single().lines.last().type)
    }

    @Test
    fun workspaceRunsGitInsideContainerAndMapsToContract() = runBlocking {
        val tmp = Files.createTempDirectory("pigo-git").toFile()
        java.io.File(tmp, "root/proj").mkdirs()
        val calls = ArrayList<List<String>>()
        val ws = RootfsWorkspace(tmp, { cmd, _ ->
            calls += cmd
            when {
                "rev-parse" in cmd -> ExecResult(0, "/root/proj\n", "")
                "status" in cmd -> ExecResult(0, status, "")
                "diff" in cmd -> ExecResult(0, diff, "")
                "worktree" in cmd -> ExecResult(0, "worktree /root/proj\nHEAD abc\nbranch refs/heads/main\n\nworktree /root/wt\nHEAD def\ndetached\n", "")
                else -> ExecResult(1, "", "?")
            }
        }, Dispatchers.Unconfined)
        try {
            val st = (ws.gitStatus("/root/proj") as Outcome.Ok).value
            assertTrue(st.isRepo)
            assertEquals("main", st.branch)
            assertEquals(6, st.files.size) // 忽略的文件不列
            assertTrue(st.files.first { it.path == "notes/todo.txt" }.untracked)
            assertEquals(listOf("git", "-C", "/root/proj"), calls.first { "status" in it }.take(3))
            assertTrue(calls.first { "status" in it }.containsAll(listOf("--porcelain=v2", "-z")))

            val d = (ws.gitDiff("/root/proj", "src/main.kt") as Outcome.Ok).value
            assertEquals(4, d.size)
            assertEquals(DiffLineKind.ADD, d[0].hunks[0].lines[2].kind)
            assertEquals(DiffLineKind.META, d[0].hunks[1].lines.last().kind)
            assertEquals(3, d[0].added)
            assertEquals(listOf("--", ":(top)src/main.kt"), calls.last().takeLast(2))

            val wt = (ws.gitWorktrees("/root/proj") as Outcome.Ok).value
            assertEquals(2, wt.size)
            assertEquals("main", wt[0].branch)
            assertNull(wt[1].branch)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun notARepositoryAndEscapingCwd() = runBlocking {
        val tmp = Files.createTempDirectory("pigo-git2").toFile()
        java.io.File(tmp, "root/plain").mkdirs()
        val ws = RootfsWorkspace(tmp, { _, _ -> ExecResult(128, "", "fatal: not a git repository") }, Dispatchers.Unconfined)
        try {
            assertFalse((ws.gitStatus("/root/plain") as Outcome.Ok).value.isRepo)
            assertTrue(ws.gitStatus("/root/../etc") is Outcome.Err)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
