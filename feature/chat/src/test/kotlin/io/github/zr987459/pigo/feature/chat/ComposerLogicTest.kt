package io.github.zr987459.pigo.feature.chat

import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.feature.chat.logic.ComposerLogic
import io.github.zr987459.pigo.feature.chat.logic.ComposerState
import io.github.zr987459.pigo.feature.chat.logic.PrimaryAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerLogicTest {

    @Test
    fun primaryActionFollowsNewbieDefaults() {
        // 空闲：有字=发送，无字=不可点
        assertEquals(PrimaryAction.SEND, ComposerLogic.primaryAction(false, ComposerState(draft = "你好")))
        assertEquals(PrimaryAction.DISABLED, ComposerLogic.primaryAction(false, ComposerState(draft = "  ")))
        // 生成中：有字=排队（默认 followUp），无字=停止
        assertEquals(PrimaryAction.QUEUE, ComposerLogic.primaryAction(true, ComposerState(draft = "再跑一遍测试")))
        assertEquals(PrimaryAction.STOP, ComposerLogic.primaryAction(true, ComposerState(draft = "")))
        // 停止中：一律不可点
        assertEquals(PrimaryAction.DISABLED, ComposerLogic.primaryAction(true, ComposerState(draft = "", stopping = true)))
        assertEquals(PrimaryAction.DISABLED, ComposerLogic.primaryAction(true, ComposerState(draft = "x", stopping = true)))
    }

    @Test
    fun withdrawnGoesBackIntoEmptyDraft() {
        val s = ComposerLogic.onWithdrawn(ComposerState(draft = ""), listOf("先别改测试", "做完再跑一遍测试"))
        assertEquals("先别改测试\n做完再跑一遍测试", s.draft)
        assertTrue(s.withdrawn.isEmpty())
        assertEquals(1, s.revision)
    }

    @Test
    fun withdrawnNeverOverwritesDraft() {
        val before = ComposerState(draft = "我正在写的新草稿", revision = 3)
        val s = ComposerLogic.onWithdrawn(before, listOf("做完再跑一遍测试"))
        assertEquals("我正在写的新草稿", s.draft)
        assertEquals(listOf("做完再跑一遍测试"), s.withdrawn)
        // 草稿没有被程序改写，输入框不会被回写
        assertEquals(3, s.revision)

        val again = ComposerLogic.onWithdrawn(s, listOf("第二条"))
        assertEquals(listOf("做完再跑一遍测试", "第二条"), again.withdrawn)
    }

    @Test
    fun insertWithdrawnAppendsAfterDraft() {
        val s = ComposerLogic.insertWithdrawn(ComposerState(draft = "草稿\n", withdrawn = listOf("a", "b")))
        assertEquals("草稿\na\nb", s.draft)
        assertTrue(s.withdrawn.isEmpty())
        val d = ComposerLogic.discardWithdrawn(ComposerState(draft = "草稿", withdrawn = listOf("a")))
        assertEquals("草稿", d.draft)
        assertTrue(d.withdrawn.isEmpty())
    }

    @Test
    fun withdrawnOrderIsSteeringThenFollowUp() {
        assertEquals(
            listOf("s1", "f1", "f2"),
            ComposerLogic.withdrawnTexts(QueueSnapshot(steering = listOf("s1"), followUp = listOf("f1", "", "f2"))),
        )
    }

    @Test
    fun editorTextOnlyFillsEmptyDraft() {
        assertEquals("扩展写入", ComposerLogic.applyEditorText(ComposerState(), "扩展写入")?.draft)
        assertNull(ComposerLogic.applyEditorText(ComposerState(draft = "我的"), "扩展写入"))
    }
}
