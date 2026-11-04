package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.contract.fake.FakePiSettings
import io.github.zr987459.pigo.contract.fake.FakeScriptedEngine
import io.github.zr987459.pigo.contract.fake.FakeSessionCatalog
import io.github.zr987459.pigo.contract.fake.StreamScript
import io.github.zr987459.pigo.contract.session.OverlayModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 审查第 2 轮 N1（搜索本会话真实检索）、N2（问题报告打码）的行为测试：经真实的 SessionHostImpl + PiActionsImpl。 */
@OptIn(ExperimentalCoroutinesApi::class)
class Review2NitsTest {

    private class Ctx(val host: SessionHostImpl, val engine: FakeScriptedEngine, val ops: RecordingOps)

    private fun TestScope.ctx(settings: FakePiSettings = FakePiSettings()): Ctx {
        val engine = FakeScriptedEngine(backgroundScope).also { it.start() }
        val ops = RecordingOps(engine)
        val host = SessionHostImpl(
            engine = engine, extras = FakeEngineExtras(), scope = backgroundScope, settings = settings,
            sessions = FakeSessionCatalog(engine), chatPort = TestChatPort(engine, backgroundScope),
            actionsFactory = { h -> PiActionsImpl(h, ops) },
        )
        testScheduler.runCurrent()
        return Ctx(host, engine, ops)
    }

    private fun TestScope.turn(c: Ctx, user: String, reply: String) {
        val job = c.engine.startStream(StreamScript(listOf(reply), intervalMs = 5), userText = user)
        testScheduler.advanceTimeBy(1_000); testScheduler.runCurrent()
        assertTrue(job.isCompleted)
    }

    @Test
    fun transcriptSearchReturnsMatchingRowsAndJumpMovesReadingPosition() = runTest {
        val c = ctx()
        turn(c, "怎么配置 HTTP 代理？", "在 settings.json 里设置 httpProxy。")
        turn(c, "顺便看看 Gradle 报错", "这是依赖冲突，和代理无关。")
        turn(c, "谢谢", "不客气。")

        val r = c.host.runAction("transcript.search", "代理")
        val list = (r as ActionResult.Overlay).model as OverlayModel.SelectList
        val items = list.groups.flatMap { it.items }
        val users = c.host.chat.value.items.filterIsInstance<ChatItem.User>()
        val assistants = c.host.chat.value.items.filterIsInstance<ChatItem.Assistant>()
        assertEquals("命中：第一问、第二答（不分先后之外不多不少）：$items", 2, items.size)
        assertEquals(users[0].key, items[0].key)
        assertEquals(assistants[1].key, items[1].key)
        assertTrue("摘录包含关键词", items.all { "代理" in it.label })
        assertEquals("你", items[0].detail)
        assertEquals("代理", list.query)

        // 不分大小写。
        val gradle = ((c.host.runAction("transcript.search", "gradle") as ActionResult.Overlay).model as OverlayModel.SelectList).groups.flatMap { it.items }
        assertEquals(listOf(users[1].key), gradle.map { it.key })

        // 没有命中 / 空查询：空列表。
        val none = ((c.host.runAction("transcript.search", "不存在的词") as ActionResult.Overlay).model as OverlayModel.SelectList)
        assertTrue(none.groups.isEmpty())

        // 选中结果跳过去：阅读位置定到那条。
        assertEquals(ActionResult.Done, c.host.runAction("transcript.jump", items[1].key))
        assertEquals(items[1].key, c.host.scrollAnchor.value?.itemKey)
        assertFalse(c.host.scrollAnchor.value!!.followBottom)
        assertTrue(c.host.runAction("transcript.jump", "no-such-key") is ActionResult.Toast)
    }

    @Test
    fun snippetIsBoundedAndCentredOnHit() {
        val long = "前".repeat(100) + "关键词" + "后".repeat(100)
        val s = TranscriptSearch.snippet(long, 100, 3)
        assertTrue(s.startsWith("…") && s.endsWith("…") && "关键词" in s)
        assertTrue(s.length < 60)
    }

    @Test
    fun bugReportRedactsProxyCredentialsAndSecretValues() = runTest {
        val settings = FakePiSettings(
            initial = mapOf(
                "httpProxy" to JsonPrimitive("http://alice:s3cr3t@proxy.local:8080"),
                "https_proxy" to JsonPrimitive("socks5://bob@10.0.0.1:1080"),
                "defaultProvider" to JsonPrimitive("deepseek"),
                "apiKey" to JsonPrimitive("sk-live-123456"),
                "extensions" to JsonObject(mapOf("mcp" to JsonObject(mapOf("token" to JsonPrimitive("ghp_abcdef"), "url" to JsonPrimitive("https://u:p@mcp.example/x"))))),
                "mirrors" to JsonArray(listOf(JsonPrimitive("https://carol:pw@mirror.example/npm"))),
                "theme" to JsonPrimitive("dark"),
            ),
        )
        val c = ctx(settings)
        val r = c.host.runAction("bug", "崩了")
        assertTrue(r is ActionResult.LocalBlock)
        val report = c.ops.reports.single().second
        for (secret in listOf("alice", "s3cr3t", "bob@", "sk-live-123456", "ghp_abcdef", "u:p@", "carol", ":pw@")) {
            assertFalse("报告里不能有 $secret：\n$report", secret in report)
        }
        assertTrue("主机与端口保留便于排查", "proxy.local:8080" in report && "10.0.0.1:1080" in report && "mirror.example/npm" in report)
        assertTrue("普通设置原样", "defaultProvider = \"deepseek\"" in report && "theme = \"dark\"" in report)
        assertTrue("键名保留、值打码", "apiKey = \"***\"" in report)
        assertTrue("描述还在", "崩了" in report)
    }

    @Test
    fun redactTextOnlyTouchesUserInfo() {
        assertEquals("http://***@h:1/p", ReportRedaction.redactText("http://a:b@h:1/p"))
        assertEquals("http://h:1/p?x=a@b", ReportRedaction.redactText("http://h:1/p?x=a@b"))
        assertEquals("纯文本 a@b.com", ReportRedaction.redactText("纯文本 a@b.com"))
    }
}
