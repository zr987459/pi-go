package io.github.zr987459.pigo.contract

import io.github.zr987459.pigo.contract.fake.FakeAppSettingsStore
import io.github.zr987459.pigo.contract.fake.FakeAttachmentProcessor
import io.github.zr987459.pigo.contract.fake.FakeAuthManager
import io.github.zr987459.pigo.contract.fake.FakeCustomProviderStore
import io.github.zr987459.pigo.contract.fake.FakeDraftStore
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.contract.fake.FakeModelCatalog
import io.github.zr987459.pigo.contract.fake.FakeSessionExporter
import io.github.zr987459.pigo.contract.fake.FakeSessionIndex
import io.github.zr987459.pigo.contract.fake.FakeWorkspaceFs
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W0 新契约的 Fake：每个 Fake 一条单测，同时校验契约里的小工具函数。 */
class ExtrasFakesTest {

    private fun <T> Outcome<T>.ok(): T = (this as Outcome.Ok).value

    @Test
    fun fakeEngineExtras_coversForkTreeCompactBashAndImages() = runTest {
        val e = FakeEngineExtras()
        assertEquals(3, e.commands().ok().size)
        assertEquals("/skill:pdf", e.commands().ok().first { it.source == CommandSource.SKILL }.invocation)

        val tree = e.tree().ok()
        assertEquals(setOf("e4", "e6", "e7"), tree.leaves().map { it.entry.id }.toSet())
        assertEquals("换个问法", e.fork("e5").ok().text)
        assertTrue(e.fork("nope") is Outcome.Err)
        assertEquals(listOf("e5", "e6", "e7"), e.entries(since = "e4").ok().entries.map { it.id })

        val c = e.compact("只留代码改动").ok()
        assertEquals(60_000L, c.tokensBefore)
        assertNull("压缩后 percent 置空", e.usageState.value!!.context!!.percent)
        assertTrue(e.timelineExtras.value.last() is TimelineExtra.Compaction)

        val events = e.bash("echo hi").toList()
        assertEquals(BashEvent.Output("hi\n"), events.first())
        assertEquals(0, (events.last() as BashEvent.Done).result.exitCode)

        val img = ImageInput("image/png", "iVBORw0KGgo=", 1, 1, "a.png")
        assertEquals(Disposition.STARTED, e.send("看图", listOf(img)).ok())
        assertTrue(e.timelineExtras.value.any { it is TimelineExtra.UserImages })

        e.setSteeringMode(QueueMode.ALL)
        assertEquals(QueueMode.ALL, e.sessionSettings().ok().steeringMode)
        assertEquals(QueueMode.ONE_AT_A_TIME, QueueMode.fromWire("one-at-a-time"))
        assertTrue("compact:只留代码改动" in e.calls)
    }

    @Test
    fun fakeModelCatalog_marksMissingAuthAndSearches() = runTest {
        val cat = FakeModelCatalog()
        val entries = cat.entries().ok()
        assertEquals(ModelAvailability.READY, entries.first { it.model.provider == "deepseek" }.availability)
        assertEquals(ModelAvailability.NEEDS_AUTH, entries.first { it.model.provider == "anthropic" }.availability)
        assertTrue(cat.search("qwen").ok().all { it.model.id.contains("qwen") })
        assertTrue(cat.find(ModelRef("qwen-token-plan-cn", "qwen3-vl-plus"))!!.model.supportsImages)
        repeat(7) { cat.markUsed(ModelRef("p", "m$it")) }
        assertEquals(5, cat.recent().size)
        assertEquals(ModelRef("p", "m6"), cat.recent().first())
        assertEquals(ModelRef("openrouter", "anthropic/claude"), ModelRef.parse("openrouter/anthropic/claude"))
        assertEquals(5, cat.refresh().ok().providerCount)
    }

    @Test
    fun fakeAuthManager_deviceAndPkceFlows() = runTest {
        val auth = FakeAuthManager()
        val device = auth.login("github-copilot").toList()
        assertTrue(device.any { it is OAuthStep.DeviceCode })
        assertTrue(auth.state("github-copilot") is AuthState.OAuth)

        val steps = async { auth.login("anthropic").toList() }
        repeat(5) { yield() }
        auth.submitCode("anthropic", " ok ")
        assertTrue(steps.await().last() is OAuthStep.Done)

        auth.setApiKey("deepseek", "  Bearer sk-abcd5678 ")
        assertEquals(AuthState.ApiKey("5678"), auth.state("deepseek"))
        auth.clear("deepseek")
        assertFalse(auth.state("deepseek").configured)
    }

    @Test
    fun fakeCustomProviderStore_upsertDiscoverTest() = runTest {
        val s = FakeCustomProviderStore()
        assertTrue(s.upsert(CustomProvider("x", "ftp://bad"), "k") is Outcome.Err)
        s.upsert(CustomProvider("bailian", "https://dashscope.aliyuncs.com/compatible-mode/v1"), "sk-1")
        assertEquals(2, s.discover("https://x", ProviderApi.OPENAI_COMPLETIONS, null, "bailian").ok().size)
        assertEquals(401, s.test(ModelRef("bad", "m")).ok().httpStatus)
        assertEquals(ProviderApi.ANTHROPIC_MESSAGES, ProviderApi.fromWire("anthropic-messages"))
    }

    @Test
    fun fakeAppSettingsStore_costFormatting() = runTest {
        val s = FakeAppSettingsStore()
        assertEquals("$0.45", s.costDisplay.value.format(0.45))
        assertEquals(7.1, s.costDisplay.value.usdToCny, 0.0)
        s.setCostDisplay(CostDisplaySettings(showCny = true, usdToCny = 7.0))
        assertEquals("$1.00（约 ¥7.00）", s.costDisplay.value.format(1.0))
        assertEquals("$0.0012", CostDisplaySettings().format(0.0012))
        s.setNetwork(NetworkSettings(proxyUrl = "http://127.0.0.1:7890"))
        assertEquals("http://127.0.0.1:7890", s.network.value.proxyUrl)
    }

    @Test
    fun fakeWorkspaceFs_listReadImportGitAndRejectEscape() = runTest {
        val fs = FakeWorkspaceFs()
        val cwd = FakeWorkspaceFs.FIXTURE_CWD
        val names = fs.list(cwd).ok().map { it.name }
        assertEquals(listOf("src", "logo.png", "README.md"), names)
        assertTrue(fs.list(cwd, showHidden = true).ok().any { it.name == ".hidden" })
        assertTrue(fs.readText("/../etc/passwd") is Outcome.Err)
        assertTrue(fs.readText("$cwd/README.md", maxBytes = 4).ok().truncated)
        assertTrue(fs.preview("$cwd/README.md").ok() is FilePreview.Text)

        val a = fs.importStream("a.txt", "1".byteInputStream(), "$cwd/in").ok()
        val b = fs.importStream("a.txt", "2".byteInputStream(), "$cwd/in").ok()
        assertEquals("a.txt", a.name)
        assertEquals("a (1).txt", b.name)
        val out = ByteArrayOutputStream()
        assertEquals(1L, fs.exportTo(b.guestPath, out).ok())
        assertEquals("2", out.toString())

        val st = fs.gitStatus(cwd).ok()
        assertTrue(st.isRepo && st.files.any { it.untracked })
        assertEquals(1, fs.gitDiff(cwd).ok().single().added)
        assertFalse(fs.gitStatus("/root").ok().isRepo)
    }

    @Test
    fun fakeAttachmentProcessor_composeAndLimits() = runTest {
        val fs = FakeWorkspaceFs()
        val p = FakeAttachmentProcessor(fs)
        val img = p.prepareImage(byteArrayOf(1, 2, 3).inputStream(), "image/png", "a.png").ok()
        assertEquals("AQID", img.base64Data)
        assertTrue(p.prepareImage(ByteArray(10).inputStream(), null, null, maxBytes = 5) is Outcome.Err)
        val ref = p.importFile("log.txt", "x".byteInputStream(), "/root/pi-cwd").ok()
        assertEquals("@/root/pi-cwd/.pigo/uploads/2026-11-03/log.txt", ref.mention)
        val (text, images) = p.compose("看看", listOf(Attachment.Image(img), ref))
        assertEquals("看看\n\n@/root/pi-cwd/.pigo/uploads/2026-11-03/log.txt", text)
        assertEquals(1, images.size)
    }

    @Test
    fun fakeSessionExporter_writesEachFormat() = runTest {
        val ex = FakeSessionExporter()
        for (f in ExportFormat.entries) {
            val file = ex.export("/root/.pi/agent/sessions/x/2026_abc.jsonl", f).ok()
            assertTrue(file.exists())
            assertTrue(file.name.endsWith("." + f.extension))
        }
    }

    @Test
    fun fakeSessionIndex_groupsPinsArchivesSearches() = runTest {
        val idx = FakeSessionIndex()
        val a2 = FakeSessionIndex.FIXTURE_SESSIONS[1].guestPath
        val a1 = FakeSessionIndex.FIXTURE_SESSIONS[0].guestPath
        var groups = idx.observeGroups().first()
        assertEquals(listOf("/root/pi-cwd", "/root/blog"), groups.map { it.cwd })
        assertEquals(a2, groups[0].sessions.first().summary.guestPath)

        idx.setPinned(a1, true)
        groups = idx.observeGroups().first()
        assertEquals(a1, groups[0].sessions.first().summary.guestPath)

        idx.setArchived(a2, true)
        assertEquals(1, idx.observeGroups().first()[0].sessions.size)
        assertEquals(2, idx.observeGroups(includeArchived = true).first()[0].sessions.size)

        val hit = idx.search("测试").single()
        assertEquals(2, hit.messageIndex)
        assertEquals(-1, idx.search("博客主题").single().messageIndex)
        assertTrue(idx.search("  ").isEmpty())
        assertEquals("登录页点了没反应", idx.suggestTitle(a1).ok())
    }

    @Test
    fun fakeDraftStore_perSessionDrafts() = runTest {
        val d = FakeDraftStore()
        d.put("A", "草稿 A")
        d.put(DraftStore.newSessionKey("/root/pi-cwd"), "新会话草稿")
        assertEquals("草稿 A", d.get("A"))
        assertEquals("新会话草稿", d.get("new:/root/pi-cwd"))
        d.put("A", "")
        assertNull(d.get("A"))
    }
}
