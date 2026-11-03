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
import io.github.zr987459.pigo.feature.commands.data.AutoNamer
import io.github.zr987459.pigo.feature.commands.data.AutoTitlePolicy
import io.github.zr987459.pigo.feature.commands.data.FileDraftStore
import io.github.zr987459.pigo.feature.commands.data.PiSessionIndex
import io.github.zr987459.pigo.feature.commands.data.SessionMetaStore
import io.github.zr987459.pigo.feature.commands.data.SessionTitle
import io.github.zr987459.pigo.feature.commands.data.SessionTranscriptReader
import io.github.zr987459.pigo.feature.commands.data.TitleGenerator
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
        val idx = PiSessionIndex(dir, metaStore = SessionMetaStore(File(tmp.root, "meta.json")), cacheFile = File(tmp.root, "idx.json"), titles = null)
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
        val again = PiSessionIndex(dir, metaStore = SessionMetaStore(File(tmp.root, "meta.json")), cacheFile = File(tmp.root, "idx.json"), titles = null)
        assertTrue(again.meta(ga).archived)
        idx.markNameLocked(ga)
        assertTrue(SessionMetaStore(File(tmp.root, "meta.json")).get(ga).nameLockedByUser)
    }

    @Test
    fun pathMappingRejectsOutsidePaths() {
        val dir = tmp.newFolder("sessions")
        val idx = PiSessionIndex(dir, metaStore = SessionMetaStore(File(tmp.root, "m.json")), cacheFile = null, titles = null)
        assertNull(idx.hostOf("/root/.pi/agent/sessions/../auth.json"))
        assertNull(idx.hostOf("/etc/passwd"))
        assertEquals(File(dir, "x/y.jsonl"), idx.hostOf("/root/.pi/agent/sessions/x/y.jsonl"))
        assertNull(idx.guestOf(File(tmp.root, "other.jsonl").path))
    }

    @Test
    fun suggestTitleUsesCurrentBranchAndPiPrint() = runTest {
        val dir = tmp.newFolder("sessions")
        session(dir, "--root-pi-cwd--/a.jsonl", "/root/pi-cwd", "帮我修登录页的空指针", "再加个单测")
        val cmds = mutableListOf<List<String>>()
        var promptSeen = ""
        val gen = TitleGenerator(
            exec = { argv -> cmds += argv; ExecResult(0, "标题：「修复登录页空指针」\n", "") },
            writePrompt = { text -> promptSeen = text; File(tmp.root, "p.txt").apply { writeText(text) } to "/tmp/pigo-title.txt" },
        )
        val idx = PiSessionIndex(dir, metaStore = SessionMetaStore(File(tmp.root, "m.json")), cacheFile = null, titles = gen,
            titleModel = { ModelInfo("deepseek", "deepseek-reasoner", reasoning = true) })
        val r = idx.suggestTitle("/root/.pi/agent/sessions/--root-pi-cwd--/a.jsonl")
        assertEquals(Outcome.Ok("修复登录页空指针"), r)
        val argv = cmds.single()
        assertEquals(listOf("pi", "--print", "--no-session", "--no-tools"), argv.take(4))
        assertTrue(argv.containsAll(listOf("--provider", "deepseek", "--model", "deepseek-reasoner", "--thinking", "off", "@/tmp/pigo-title.txt")))
        assertTrue(promptSeen.contains("User: 帮我修登录页的空指针"))
        assertTrue(promptSeen.contains("Assistant: 答1"))
        assertFalse("临时提示词文件用完即删", File(tmp.root, "p.txt").exists())
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
    fun titleParsingAndTranscriptBounds() {
        assertEquals("修复登录", SessionTitle.parse("```\n\"修复登录\"\n```"))
        assertEquals("Fix login bug", SessionTitle.parse("""{"title":"Fix login bug."}"""))
        assertEquals("重构", SessionTitle.parse("Title: 重构\n解释……"))
        assertNull(SessionTitle.parse("。。。"))
        assertEquals(80, SessionTitle.parse("长".repeat(100))!!.length)

        val many = (0 until 60).flatMap { i ->
            listOf(ChatItem.User("u$i", "问题$i " + "x".repeat(200)), ChatItem.Assistant("a$i", listOf(ContentBlock.Text(0, "回答$i")), streaming = false))
        }
        val t = SessionTitle.transcript(many)
        assertTrue(t.length <= SessionTitle.TRANSCRIPT_CHARS)
        assertTrue("保留开头", t.startsWith("User: 问题0"))
        assertTrue("保留结尾", t.endsWith("Assistant: 回答59"))
        assertTrue(t.contains("[…]"))
    }

    @Test
    fun autoTitlePolicyOncePerSession() {
        val p = AutoTitlePolicy()
        val items = listOf(ChatItem.User("u", "hi"), ChatItem.Assistant("a", listOf(ContentBlock.Text(0, "hello")), streaming = false))
        assertFalse(p.claim("/s/a", "已有名字", false, items))
        assertFalse(p.claim("/s/a", null, true, items))
        assertFalse(p.claim("/s/a", null, false, items.take(1)))
        assertTrue(p.claim("/s/a", null, false, items))
        assertFalse("只试一次", p.claim("/s/a", null, false, items))
    }

    @Test
    fun autoNamerRenamesAfterCompletedTurnOnly() = runTest {
        val engine = FakeEngineClient(backgroundScope, stepDelayMs = 0)
        engine.openSession("/root/.pi/agent/sessions/x/a.jsonl", "/root/pi-cwd")
        engine.send("帮我写个 README")
        // 等 Fake 回放结束（有会话文件和助手回复）。
        engine.chat.first { !it.running && it.items.any { i -> i is ChatItem.Assistant } }
        val extras = FakeEngineExtras()
        val index = FakeSessionIndex()
        val namer = AutoNamer(engine, extras, index,
            TitleGenerator(exec = { ExecResult(0, "写 README", "") }, writePrompt = { File(tmp.root, "p").apply { writeText(it) } to "/tmp/p" }),
            backgroundScope)
        assertEquals("/root/.pi/agent/sessions/x/a.jsonl", engine.chat.value.sessionFile)
        assertEquals("写 README", namer.maybeName())
        assertNull("同一会话不再命名", namer.maybeName())
        // 用户锁定名字的会话不自动命名。
        engine.openSession("/root/.pi/agent/sessions/x/b.jsonl", "/root/pi-cwd")
        engine.send("另一个")
        engine.chat.first { !it.running && it.sessionFile!!.endsWith("b.jsonl") && it.items.any { i -> i is ChatItem.Assistant } }
        index.markNameLocked("/root/.pi/agent/sessions/x/b.jsonl")
        assertNull(namer.maybeName())
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
