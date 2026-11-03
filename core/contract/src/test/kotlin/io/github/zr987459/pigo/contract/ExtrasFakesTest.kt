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

    // ---------------- 审查第 1 轮 ----------------

    @Test
    fun fakeFork_onlyUserEntriesLikePi150() = runTest {
        val e = FakeEngineExtras()
        assertEquals("全部用户消息（含其他分支的 e5）", listOf("e1", "e3", "e5"), e.forkMessages().ok().map { it.entryId })
        assertEquals("第一条问题", e.fork("e1").ok().text)
        for (id in listOf("e2", "e4", "e6", "e7")) {
            val r = e.fork(id)
            assertTrue("非用户消息 $id 必须拒绝", r is Outcome.Err && r.code == ErrCode.RPC_REJECTED)
        }
    }

    @Test
    fun fakeMessageMeta_mapsTimelineKeysToEntryIdsAndUsage() = runTest {
        val e = FakeEngineExtras()
        val meta = e.messageMeta.value
        assertEquals("e3", meta["user-2"]!!.entryId)
        assertTrue(meta["user-2"]!!.forkable)
        assertFalse(meta["assistant-3"]!!.forkable)
        assertEquals(500L, meta["assistant-3"]!!.usage!!.output)
        assertEquals(ModelRef("deepseek", "deepseek-reasoner"), meta["assistant-3"]!!.model)
        // 每条可分叉的元数据都能被 fork 接受
        meta.values.filter { it.forkable }.forEach { assertTrue(e.fork(it.entryId) is Outcome.Ok) }
        e.setMessageMeta(emptyMap())
        assertTrue(e.messageMeta.value.isEmpty())
    }

    @Test
    fun cycleModelResult_fakeAndDefaultImplementation() = runTest {
        val e = FakeEngineExtras()
        val r = e.cycleModelResult().ok()!!
        assertEquals("deepseek-reasoner", r.model.id)
        assertFalse(r.isScoped)
        // 接口默认实现：只包装 cycleModel()
        // （`by` 委托会把 cycleModelResult 也转给 Fake 的覆盖，所以显式调用接口默认实现）
        val plain = object : EngineExtras by FakeEngineExtras() {
            override suspend fun cycleModelResult() = super<EngineExtras>.cycleModelResult()
        }
        val d = plain.cycleModelResult().ok()!!
        assertEquals("deepseek-reasoner", d.model.id)
        assertNull(d.thinkingLevel)
        val bare = object : EngineExtras by FakeEngineExtras() {
            override val messageMeta get() = super<EngineExtras>.messageMeta
        }
        assertTrue("接口默认 messageMeta 为空表", bare.messageMeta.value.isEmpty())
    }

    @Test
    fun appendedContractFields_haveBackwardCompatibleDefaults() = runTest {
        assertNull("1.5.0 get_state 无 autoRetry，Fake 初值为 null", FakeEngineExtras().sessionSettings().ok().autoRetry)
        val o = RunOutcome(RunOutcomeKind.COMPLETED, "/s.jsonl", null)
        assertNull(o.cwd)
        val c = TimelineExtra.Compaction("k", null, 100, null, null)
        assertNull(c.error)
        assertFalse(c.aborted)
        assertTrue(TimelineExtra.Compaction("k", null, 100, null, null, error = "超时").error == "超时")
    }

    @Test
    fun fakeWorkspace_importIntoRootDirectory() = runTest {
        val fs = FakeWorkspaceFs()
        val n = fs.importStream("a.txt", "hi".toByteArray().inputStream(), "/").ok()
        assertEquals("/a.txt", n.guestPath)
        val again = fs.importStream("a.txt", "hi".toByteArray().inputStream(), "/").ok()
        assertEquals("/a (1).txt", again.guestPath)
        assertTrue("根目录下能列出", fs.list("/").ok().any { it.guestPath == "/a.txt" })
        assertTrue(fs.stat("/a (1).txt") is Outcome.Ok)
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
