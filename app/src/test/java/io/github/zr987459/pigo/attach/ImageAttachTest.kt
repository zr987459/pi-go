package io.github.zr987459.pigo.attach

import io.github.zr987459.pigo.contract.session.DraftImage
import io.github.zr987459.pigo.contract.session.DraftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** PR #21 审查第 1 轮 Important 1：选中的图片并进共用草稿。 */
class ImageAttachTest {
    @Test
    fun appendsKeepsTextAndDedupes() {
        val d0 = DraftState(text = "看看这张图", selectionStart = 5, images = listOf(DraftImage("content://a")))
        val d1 = ImageAttach.add(d0, listOf(DraftImage("content://a"), DraftImage("content://b", "image/png")))
        assertEquals(listOf("content://a", "content://b"), d1.images.map { it.uri })
        assertEquals("看看这张图", d1.text)
        assertEquals(5, d1.selectionStart)
        assertSame(d0, ImageAttach.add(d0, emptyList()))
        assertSame(d0, ImageAttach.add(d0, listOf(DraftImage("content://a"))))
        assertEquals(true, d1.hasSendableContent)
    }
}
