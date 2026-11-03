package io.github.zr987459.pigo.feature.chat

import io.github.zr987459.pigo.feature.chat.ui.SuggestionText
import org.junit.Assert.assertEquals
import org.junit.Test

/** 审查 R1 Nit：点建议胶囊不覆盖已有草稿。 */
class SuggestionTextTest {
    @Test fun emptyDraftTakesPrompt() {
        assertEquals("解释这段代码", SuggestionText.merge("", "解释这段代码"))
        assertEquals("解释这段代码", SuggestionText.merge("  \n", "解释这段代码"))
    }

    @Test fun existingDraftIsKeptAndPromptAppended() {
        assertEquals("我的问题：\n解释这段代码", SuggestionText.merge("我的问题：  ", "解释这段代码"))
    }
}
