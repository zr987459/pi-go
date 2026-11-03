package io.github.zr987459.pigo.feature.chat

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.RetryBanner
import io.github.zr987459.pigo.contract.ToolStatus
import io.github.zr987459.pigo.feature.chat.logic.ErrorText
import io.github.zr987459.pigo.feature.chat.logic.LocalSend
import io.github.zr987459.pigo.feature.chat.logic.PhaseLabel
import io.github.zr987459.pigo.feature.chat.logic.RetryAnchor
import io.github.zr987459.pigo.feature.chat.logic.RetryVisibility
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** piweb-study G5（阶段提示）、G4（重试黄条清除时机）与「去设置模型」入口的纯逻辑。 */
class PhaseLabelTest {
    private val idle = EngineStatus.Stopped
    private val sending = listOf(LocalSend("s0", "你好", Delivery.SENDING))
    private val user = ChatItem.User("u1", "你好")

    @Test
    fun startingEngineWhileSending() {
        assertEquals(PhaseLabel.STARTING_ENGINE, PhaseLabel.of(EngineStatus.Starting, ChatState(), sending, stopping = false))
        assertNull("空闲时引擎启动不打扰", PhaseLabel.of(EngineStatus.Starting, ChatState(), emptyList(), stopping = false))
    }

    @Test
    fun connectingThenWaitingThenNullOnceTextStreams() {
        assertEquals(PhaseLabel.CONNECTING, PhaseLabel.of(idle, ChatState(), sending, stopping = false))
        val running = ChatState(items = listOf(user), running = true)
        assertEquals(PhaseLabel.WAITING, PhaseLabel.of(idle, running, emptyList(), stopping = false))
        val emptyAssistant = running.copy(items = listOf(user, ChatItem.Assistant("a1")))
        assertEquals(PhaseLabel.WAITING, PhaseLabel.of(idle, emptyAssistant, emptyList(), stopping = false))
        val thinking = running.copy(items = listOf(user, ChatItem.Assistant("a1", listOf(ContentBlock.Thinking(0, "嗯")))))
        assertEquals(PhaseLabel.THINKING, PhaseLabel.of(idle, thinking, emptyList(), stopping = false))
        val text = running.copy(items = listOf(user, ChatItem.Assistant("a1", listOf(ContentBlock.Text(0, "好的")))))
        assertNull(PhaseLabel.of(idle, text, emptyList(), stopping = false))
        assertNull("回合结束后不显示", PhaseLabel.of(idle, ChatState(items = text.items), emptyList(), stopping = false))
    }

    @Test
    fun runningToolShowsNameAndLastOutputLine() {
        val partial = buildJsonObject {
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "a.txt\nb.txt\n\n  README.md  \n") }) })
        }
        val tool = ChatItem.Tool("t1", "call1", "bash", status = ToolStatus.RUNNING, partialResult = partial)
        val st = ChatState(items = listOf(user, ChatItem.Assistant("a1", streaming = false), tool), running = true)
        assertEquals("正在运行 bash：README.md", PhaseLabel.of(idle, st, emptyList(), stopping = false))
        val noOutput = st.copy(items = listOf(user, tool.copy(partialResult = null)))
        assertEquals("正在运行 bash…", PhaseLabel.of(idle, noOutput, emptyList(), stopping = false))
        val long = "x".repeat(200)
        assertEquals(PhaseLabel.MAX_TOOL_LINE, PhaseLabel.lastOutputLine(JsonPrimitive(long))!!.length)
    }

    @Test
    fun compactionWithQueueAndStoppingAreHandled() {
        val st = ChatState(items = listOf(user), running = true, statusLine = "正在压缩上下文…", queue = QueueSnapshot(emptyList(), listOf("a", "b")))
        assertEquals("正在压缩上下文（2 条排队）", PhaseLabel.of(idle, st, emptyList(), stopping = false))
        assertNull(PhaseLabel.of(idle, st.copy(queue = QueueSnapshot.EMPTY), emptyList(), stopping = false))
        assertNull(PhaseLabel.of(idle, ChatState(items = listOf(user), running = true), emptyList(), stopping = true))
    }

    @Test
    fun retryBarHidesOnceRetryStartsProducing() {
        val failed = ChatItem.Assistant("a1", listOf(ContentBlock.Text(0, "")), streaming = false, stopReason = "error")
        val banner = RetryBanner(1, 3, 2000, "503")
        val st = ChatState(items = listOf(user, failed), running = true, retry = banner)
        val anchor = RetryVisibility.lastAssistantKey(st)
        assertTrue(RetryVisibility.visible(st, anchor))
        // 重试那一轮开始了新的助手消息但还没出字：仍显示
        val started = st.copy(items = st.items + ChatItem.Assistant("a2"))
        assertTrue(RetryVisibility.visible(started, anchor))
        // 出字了：立即隐藏，不必等 auto_retry_end
        val producing = st.copy(items = st.items + ChatItem.Assistant("a2", listOf(ContentBlock.Text(0, "好"))))
        assertFalse(RetryVisibility.visible(producing, anchor))
        // 回合结束：不残留
        assertFalse(RetryVisibility.visible(st.copy(running = false), anchor))
        assertFalse(RetryVisibility.visible(st.copy(retry = null), anchor))
    }

    @Test
    fun settingsShortcutForModelProblems() {
        assertTrue(ErrorText.classify("401 Unauthorized").suggestsSettings)
        assertTrue(ErrorText.classify("model_not_found").suggestsSettings)
        assertTrue("模型服务连不上", ErrorText.classify("getaddrinfo ENOTFOUND api.example.com").suggestsSettings)
        assertFalse(ErrorText.classify("500 internal error").suggestsSettings)
    }

    @Test
    fun secondRetryStaysVisibleAfterFirstRetryStreamedPartialText() {
        // 第 1 次重试流出部分文字后失败（连接中途被重置）；第 2 次重试等待期间黄条仍要显示。
        val failed = ChatItem.Assistant("a1", listOf(ContentBlock.Text(0, "")), streaming = false, stopReason = "error")
        val anchor = RetryAnchor()
        var st = ChatState(items = listOf(user, failed), running = true, retry = RetryBanner(1, 3, 2000, "503"))
        assertEquals(1, anchor.update(st)?.attempt)
        // 第 1 次重试开始出字：隐藏
        val partial = ChatItem.Assistant("a2", listOf(ContentBlock.Text(0, "半句")), streaming = true)
        st = st.copy(items = listOf(user, failed, partial))
        assertNull(anchor.update(st))
        // 第 1 次重试中途失败，进入第 2 次重试的等待：重新显示「第 2/3 次」
        val partialFailed = partial.copy(streaming = false, stopReason = "error")
        st = st.copy(items = listOf(user, failed, partialFailed), retry = RetryBanner(2, 3, 4000, "connection reset"))
        assertEquals(2, anchor.update(st)?.attempt)
        // 第 2 次重试开始出字：再次隐藏
        st = st.copy(items = st.items + ChatItem.Assistant("a3", listOf(ContentBlock.Text(0, "好"))))
        assertNull(anchor.update(st))
        // 重试结束
        assertNull(anchor.update(st.copy(retry = null)))
    }
}
