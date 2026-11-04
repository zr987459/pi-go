package io.github.zr987459.pigo.feature.commands

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.CommandSource
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.DraftStore
import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.RunOutcomeKind
import io.github.zr987459.pigo.contract.SlashCommand
import io.github.zr987459.pigo.contract.fake.FakeEngineClient
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.contract.fake.FakeSessionIndex
import io.github.zr987459.pigo.feature.commands.data.FileDraftStore
import io.github.zr987459.pigo.feature.commands.data.PiSessionIndex
import io.github.zr987459.pigo.feature.commands.data.SessionMetaStore
import io.github.zr987459.pigo.feature.commands.data.SessionTranscriptReader
import io.github.zr987459.pigo.feature.commands.skills.SkillTemplateManager
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionDataTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun line(s: String) = s + "\n"
    private fun session(dir: File, rel: String, cwd: String, vararg users: String, name: String? = null): File {
        val f = File(dir, rel).apply { parentFile.mkdirs() }
        val sb = StringBuilder(line("""{"type":"session","version":3,"id":"${rel.hashCode()}","timestamp":"2026-11-03T06:00:00.000Z","cwd":"$cwd"}"""))
        var parent: String? = null
        users.forEachIndexed { i, u ->
            val id = "u$i"
            sb.append(line("""{"type":"message","id":"$id","parentId":${parent?.let { "\"$it\"" } ?: "null"},"timestamp":"t","message":{"role":"user","content":"$u"}}"""))
            sb.append(line("""{"type":"message","id":"a$i","parentId":"$id","timestamp":"t","message":{"role":"assistant","content":[{"type":"text","text":"答$i"}]}}"""))
            parent = "a$i"
        }
        name?.let { sb.append(line("""{"type":"session_info","id":"n","parentId":"$parent","timestamp":"t","name":"$it"}""")) }
        f.writeText(sb.toString())
        return f
    }

    @Test
    fun piSessionIndexGroupsPinsArchivesAndSearches() = runTest {
        val dir = tmp.newFolder("sessions")
        val a = session(dir, "--root-pi-cwd--/a.jsonl", "/root/pi-cwd", "登录页点了没反应", "看下控制台报错", "改完跑一遍测试", name = "修登录 bug")
        val b = session(dir, "--root-pi-cwd--/b.jsonl", "/root/pi-cwd", "写一个 README")
        val c = session(dir, "--root-blog--/c.jsonl", "/root/blog", "把博客换成深色主题")
        a.setLastModified(1_000_000); b.setLastModified(2_000_000); c.setLastModified(3_000_000)
        val idx = PiSessionIndex(dir, metaStore = SessionMetaStore(File(tmp.root, "meta.json")), cacheFile = File(tmp.root, "idx.json"))
        idx.rescan()
        var groups = idx.observeGroups().first()
        assertEquals(listOf("/root/blog", "/root/pi-cwd"), groups.map { it.cwd })
        val ga = "/root/.pi/agent/sessions/--root-pi-cwd--/a.jsonl"
        assertEquals(ga, groups[1].sessions.last().summary.guestPath)

        idx.setPinned(ga, true)
        groups = idx.observeGroups().first()
        assertEquals("置顶的组排前、组内置顶在前", listOf("/root/pi-cwd", "/root/blog"), groups.map { it.cwd })
        assertEquals(ga, groups[0].sessions.first().summary.guestPath)

        idx.setArchived(ga, true)
        assertFalse("归档取消置顶", idx.meta(ga).pinned)
        assertTrue(idx.observeGroups().first().flatMap { it.sessions }.none { it.summary.guestPath == ga })
        assertTrue(idx.observeGroups(includeArchived = true).first().flatMap { it.sessions }.any { it.summary.guestPath == ga })

        val hit = idx.search("测试").single()
        assertEquals(ga, hit.summary.guestPath)
        assertEquals(2, hit.messageIndex)
        assertEquals(-1, idx.search("修登录").single().messageIndex)

        // 元数据持久化，新实例读回。
        val again = PiSessionIndex(dir, metaStore = SessionMetaStore(File(tmp.root, "meta.json")), cacheFile = File(tmp.root, "idx.json"))
        assertTrue(again.meta(ga).archived)
        idx.markNameLocked(ga)
        assertTrue(SessionMetaStore(File(tmp.root, "meta.json")).get(ga).nameLockedByUser)
    }

    @Test
    fun pathMappingRejectsOutsidePaths() {
        val dir = tmp.newFolder("sessions")
        val idx = PiSessionIndex(dir, metaStore = SessionMetaStore(File(tmp.root, "m.json")), cacheFile = null)
        assertNull(idx.hostOf("/root/.pi/agent/sessions/../auth.json"))
        assertNull(idx.hostOf("/etc/passwd"))
        assertEquals(File(dir, "x/y.jsonl"), idx.hostOf("/root/.pi/agent/sessions/x/y.jsonl"))
        assertNull(idx.guestOf(File(tmp.root, "other.jsonl").path))
    }

    @Test
    fun transcriptReaderFollowsCurrentBranch() {
        val f = tmp.newFile("t.jsonl")
        f.writeText(
            line("""{"type":"session","version":3,"id":"s","timestamp":"t","cwd":"/r"}""") +
                line("""{"type":"message","id":"u1","parentId":null,"timestamp":"t","message":{"role":"user","content":"根问题"}}""") +
                line("""{"type":"message","id":"a1","parentId":"u1","timestamp":"t","message":{"role":"assistant","content":[{"type":"text","text":"旧分支回答"}]}}""") +
                line("""{"type":"message","id":"a2","parentId":"u1","timestamp":"t","message":{"role":"assistant","content":[{"type":"text","text":"新分支回答"}]}}"""),
        )
        val items = SessionTranscriptReader.read(f)
        assertEquals(2, items.size)
        assertEquals("新分支回答", ((items[1] as ChatItem.Assistant).blocks[0] as ContentBlock.Text).text)
    }

    @Test
    fun fileDraftStoreRoundTrip() = runTest {
        val f = File(tmp.root, "drafts.json")
        val s = FileDraftStore(f)
        s.put("/s/a", "草稿 A")
        s.put(DraftStore.newSessionKey("/root/pi-cwd"), "新会话草稿")
        assertEquals("草稿 A", FileDraftStore(f).get("/s/a"))
        s.put("/s/a", "   ")
        assertNull(FileDraftStore(f).get("/s/a"))
        assertEquals("新会话草稿", FileDraftStore(f).get("new:/root/pi-cwd"))
        s.remove("new:/root/pi-cwd")
        assertNull(FileDraftStore(f).get("new:/root/pi-cwd"))
    }

    @Test
    fun skillTemplateManagerListsTogglesAndCreates() {
        val root = tmp.newFolder("rootfs")
        val host: (String) -> File = { File(root, it.removePrefix("/")) }
        File(root, "root/.pi/agent").mkdirs()
        File(root, "root/.pi/agent/settings.json").writeText("""{"defaultProvider":"deepseek","skills":["~/extra-skills"]}""")
        val m = SkillTemplateManager(host)
        val cmds = listOf(
            SlashCommand("skill:pdf", "处理 PDF", CommandSource.SKILL, "/root/.pi/agent/skills/pdf/SKILL.md", "user"),
            SlashCommand("review", "审查", CommandSource.PROMPT, "/root/proj/.pi/prompts/review.md", "project"),
            SlashCommand("todos", null, CommandSource.EXTENSION),
        )
        val items = m.list(cmds)
        assertEquals(listOf("skill:pdf", "review"), items.map { it.name }.let { l -> l.sortedBy { n -> if (n.startsWith("skill")) 0 else 1 } })
        assertFalse("项目级只读", items.single { it.name == "review" }.editable)

        val pdf = items.single { it.name == "skill:pdf" }
        assertTrue(m.setEnabled(pdf, false) is Outcome.Ok)
        val settings = File(root, "root/.pi/agent/settings.json").readText()
        assertTrue(settings.contains("\"-/root/.pi/agent/skills/pdf/SKILL.md\""))
        assertTrue("其他键原样保留", settings.contains("\"defaultProvider\"") && settings.contains("~/extra-skills"))
        // 停用后 get_commands 不再返回它，但列表里仍显示为已停用。
        val after = m.list(cmds.filterNot { it.name == "skill:pdf" })
        val off = after.single { it.name == "skill:pdf" }
        assertFalse(off.enabled)
        assertTrue(m.setEnabled(off, true) is Outcome.Ok)
        assertFalse(File(root, "root/.pi/agent/settings.json").readText().contains("-/root/.pi/agent/skills/pdf"))

        assertTrue(m.createTemplate("../evil", "", "x") is Outcome.Err)
        val r = m.createTemplate("fix", "修 \"测试\"", "修复 \$1 里失败的测试")
        assertEquals(Outcome.Ok("/root/.pi/agent/prompts/fix.md"), r)
        assertEquals("---\ndescription: \"修 \\\"测试\\\"\"\n---\n修复 \$1 里失败的测试\n", File(root, "root/.pi/agent/prompts/fix.md").readText())
        assertTrue("不覆盖同名", m.createTemplate("fix", "", "y") is Outcome.Err)
        assertTrue((m.read("/root/.pi/agent/prompts/fix.md") as Outcome.Ok).value.contains("修复"))
    }
}
