package io.github.zr987459.pigo.feature.chat.ui

/** 建议胶囊与草稿合并（审查 R1 Nit）：草稿为空直接填入，否则换行追加到末尾，不覆盖用户已输入的内容。纯函数。 */
internal object SuggestionText {
    fun merge(draft: String, prompt: String): String = if (draft.isBlank()) prompt else draft.trimEnd() + "\n" + prompt
}
