package io.github.zr987459.pigo.feature.chat

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.feature.chat.logic.LocalSend
import io.github.zr987459.pigo.feature.chat.logic.Outbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PR #10 审查第 1 轮：带图发送的对账（Important 1）。 */
class ReviewFixesTest {
    private fun sending(text: String, chat: ChatState) =
        LocalSend(id = "l1", text = text, status = Delivery.SENDING, baselineUserCount = Outbox.userCount(chat, text))

    @Test fun textWithImageAbsorbedOnceEngineEchoesUserMessage() {
        val before = ChatState(sessionFile = "/s/a.jsonl")
        val l = sending("看看这张图", before)
        // 引擎 ChatReducer 把 content=[text, image] 拼成「原文[图片]」，整轮仍在 running
        val echoed = before.copy(running = true, items = listOf(ChatItem.User("u1", "看看这张图[图片]")))
        assertTrue("引擎回放后立即吸收，不能等整轮结束", Outbox.isAbsorbed(l, echoed))
        assertTrue(Outbox.visible(listOf(l), echoed).isEmpty())
        assertTrue(Outbox.reconcile(listOf(l), echoed).isEmpty())
    }

    @Test fun imageOnlySendAbsorbed() {
        val before = ChatState()
        val l = sending("", before)
        val echoed = before.copy(running = true, items = listOf(ChatItem.User("u1", "[图片][图片]")))
        assertTrue(Outbox.isAbsorbed(l, echoed))
    }

    @Test fun baselineCountsEarlierSameTextImageMessages() {
        val before = ChatState(items = listOf(ChatItem.User("u0", "同一句[图片]")))
        val l = sending("同一句", before)
        assertEquals(1, l.baselineUserCount)
        assertTrue("历史里那条不算新的回放", !Outbox.isAbsorbed(l, before.copy(running = true)))
        val echoed = before.copy(running = true, items = before.items + ChatItem.User("u1", "同一句[图片]"))
        assertTrue(Outbox.isAbsorbed(l, echoed))
    }

    @Test fun matchKeyStripsPlaceholders() {
        assertEquals("a b", Outbox.matchKey(" a b[图片] "))
        assertEquals("", Outbox.matchKey("[图片]"))
        assertEquals("纯文本", Outbox.matchKey("纯文本"))
    }
}
