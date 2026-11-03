package io.github.zr987459.pigo.feature.chat

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.feature.chat.logic.ErrorKind
import io.github.zr987459.pigo.feature.chat.logic.ErrorText
import io.github.zr987459.pigo.feature.chat.logic.ExtUi
import io.github.zr987459.pigo.feature.chat.logic.ExtUiAction
import io.github.zr987459.pigo.feature.chat.logic.LocalSend
import io.github.zr987459.pigo.feature.chat.logic.MarkdownSegments
import io.github.zr987459.pigo.feature.chat.logic.Outbox
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicMiscTest {

    // ---------- 错误映射（§3.9） ----------

    @Test
    fun errorMessagesMapToChinese() {
        assertEquals(ErrorKind.BAD_KEY, ErrorText.classify("Error: 401 {\"error\":\"Unauthorized\"}").kind)
        assertEquals(ErrorKind.BAD_KEY, ErrorText.classify("403 Forbidden").kind)
        assertEquals(ErrorKind.QUOTA, ErrorText.classify("429 Too Many Requests").kind)
        assertEquals(ErrorKind.QUOTA, ErrorText.classify("402 Insufficient Balance").kind)
        assertEquals(ErrorKind.NO_MODEL, ErrorText.classify("404 model_not_found").kind)
        assertEquals(ErrorKind.NO_NETWORK, ErrorText.classify("getaddrinfo ENOTFOUND api.deepseek.com").kind)
        assertEquals(ErrorKind.SERVER, ErrorText.classify("503 Service Unavailable").kind)
        assertEquals(ErrorKind.UNKNOWN, ErrorText.classify(null).kind)
        assertEquals("Key 错误", ErrorText.classify("401").title)
        // 版本号里的数字不能被当成 HTTP 状态
        assertEquals(ErrorKind.UNKNOWN, ErrorText.classify("model gpt-4.401x failed oddly").kind)
    }

    // ---------- 扩展 UI 分类（§3.6） ----------

    private fun req(method: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        ExtUiRequest(id = "r-$method", method = method, raw = buildJsonObject { put("method", method); extra() })

    @Test
    fun blockingMethodsBecomeDialogsAndUnknownIsCancelled() {
        for (m in listOf("select", "confirm", "input", "editor")) {
            assertTrue(m, ExtUi.classify(req(m)) is ExtUiAction.Dialog)
        }
        val unknown = ExtUi.classify(req("pickColor"))
        assertEquals(ExtUiAction.CancelUnknown("r-pickColor", "pickColor"), unknown)
    }

    @Test
    fun fireAndForgetMethods() {
        assertEquals(ExtUiAction.Notify("构建完成", "info"), ExtUi.classify(req("notify") { put("message", "构建完成"); put("notifyType", "info") }))
        assertEquals(ExtUiAction.Status("status:git", "main 分支"), ExtUi.classify(req("setStatus") { put("statusKey", "git"); put("statusText", "main 分支") }))
        assertEquals(ExtUiAction.Status("status:git", null), ExtUi.classify(req("setStatus") { put("statusKey", "git") }))
        assertEquals(
            ExtUiAction.Status("widget:todo", "1. a\n2. b"),
            ExtUi.classify(req("setWidget") { put("widgetKey", "todo"); put("widgetLines", JsonArray(listOf(JsonPrimitive("1. a"), JsonPrimitive("2. b")))) }),
        )
        assertEquals(ExtUiAction.Title("新标题"), ExtUi.classify(req("setTitle") { put("title", "新标题") }))
        assertEquals(ExtUiAction.EditorText("ls -la"), ExtUi.classify(req("set_editor_text") { put("text", "ls -la") }))
    }

    @Test
    fun countdown() {
        val r = ExtUiRequest(id = "x", method = "confirm", timeoutMs = 10_000)
        assertEquals(10L, ExtUi.remainingSeconds(r, 0, 0))
        assertEquals(5L, ExtUi.remainingSeconds(r, 0, 5_000))
        assertEquals(0L, ExtUi.remainingSeconds(r, 0, 11_000))
        assertEquals(null, ExtUi.remainingSeconds(ExtUiRequest(id = "y", method = "editor"), 0, 0))
    }

    // ---------- 乐观气泡对账（§3.7） ----------

    @Test
    fun outboxAbsorbsOnceEngineHasTheMessage() {
        val sending = LocalSend("s", "你好", Delivery.SENDING, baselineUserCount = 0)
        val empty = ChatState()
        assertFalse(Outbox.isAbsorbed(sending, empty))
        val withUser = ChatState(items = listOf(ChatItem.User("p", "你好", Delivery.SENDING)))
        assertTrue(Outbox.isAbsorbed(sending, withUser))
        // 发过同样的话：只看新增的那一条
        val again = LocalSend("s2", "你好", Delivery.SENDING, baselineUserCount = 1)
        assertFalse(Outbox.isAbsorbed(again, withUser))
    }

    @Test
    fun queuedBubbleYieldsToQueueChip() {
        val queued = LocalSend("q", "再跑一遍测试", Delivery.QUEUED, sawRunning = true)
        val running = ChatState(running = true, queue = QueueSnapshot(emptyList(), listOf("再跑一遍测试")))
        assertTrue(Outbox.isAbsorbed(queued, running))
        assertFalse(Outbox.isAbsorbed(queued.copy(), ChatState(running = true)))
    }

    @Test
    fun failedBubbleIsNeverAbsorbedNorResent() {
        val failed = LocalSend("f", "你好", Delivery.FAILED, error = "x")
        val chat = ChatState(items = listOf(ChatItem.User("p", "你好")))
        assertFalse(Outbox.isAbsorbed(failed, chat))
        assertEquals(listOf(failed), Outbox.reconcile(listOf(failed), chat))
    }

    // ---------- Markdown 分段 ----------

    @Test
    fun markdownSplitKeepsFencesAndTablesWhole() {
        val md = """
            # 标题

            第一段
            还是第一段

            ```kotlin
            fun a() {

                println(1)
            }
            ```

            | a | b |
            |---|---|
            | 1 | 2 |

            - 项目一

              续行段落
            - 项目二
        """.trimIndent()
        val segs = MarkdownSegments.split(md)
        assertEquals("# 标题", segs[0])
        assertEquals("第一段\n还是第一段", segs[1])
        assertTrue(segs[2].startsWith("```kotlin") && segs[2].endsWith("```") && "println(1)" in segs[2])
        assertEquals("| a | b |\n|---|---|\n| 1 | 2 |", segs[3])
        assertTrue("续行段落" in segs[4] && "项目二" in segs[4])
        assertEquals(5, segs.size)
        // 拼回去内容不丢
        assertEquals(md.replace(Regex("\n{2,}"), "\n").replace("\n", ""), segs.joinToString("").replace("\n", ""))
    }
}
