package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.session.BashMode
import io.github.zr987459.pigo.contract.session.DraftImage
import io.github.zr987459.pigo.contract.session.DraftState
import org.junit.Assert.assertEquals
import org.junit.Test

/** 草稿持久化格式（审查第 1 轮 I1）：纯文字与旧 drafts.json 兼容，其余字段完整往返。 */
class DraftCodecTest {
    @Test
    fun plainTextStaysPlainForOldFormat() {
        assertEquals("只有文字", DraftCodec.encode(DraftState(text = "只有文字")))
        assertEquals(DraftState(text = "旧版草稿"), DraftCodec.decode("旧版草稿"))
        assertEquals(DraftState.EMPTY, DraftCodec.decode(null))
    }

    @Test
    fun fullDraftRoundTrips() {
        val d = DraftState(text = "看 @a.kt", selectionStart = 2, selectionEnd = 4, images = listOf(DraftImage("content://x/1", "image/png", "a.png")),
            fileRefs = listOf("a.kt"), bashMode = BashMode.EXCLUDE, recalled = listOf("撤回的"))
        assertEquals(d, DraftCodec.decode(DraftCodec.encode(d)))
    }

    @Test
    fun brokenJsonFallsBackToText() {
        assertEquals("坏了", DraftCodec.decode(DraftCodec.MARK + "坏了").text.let { if (it.startsWith("{")) it else it })
    }
}
