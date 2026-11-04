package io.github.zr987459.pigo.attach

import io.github.zr987459.pigo.contract.session.DraftImage
import io.github.zr987459.pigo.contract.session.DraftState

/**
 * 「＋ › 图片」选中的图片并进草稿（PR #21 审查第 1 轮 Important 1）。草稿在 SessionHostImpl 里，UI / 终端两形态共用，
 * 发送时由 `ChatControllerPort` 经 `SessionBindings.imageLoader` 读成 base64 随消息发给 pi（pi-features §10 带图提问）。
 */
object ImageAttach {
    /** 一次最多选几张（系统照片选择器的上限参数，须 > 1）。 */
    const val MAX_PICK = 8

    /** 追加图片：同一 URI 不重复添加，保持原有顺序。 */
    fun add(draft: DraftState, images: List<DraftImage>): DraftState {
        if (images.isEmpty()) return draft
        val seen = draft.images.map { it.uri }.toMutableSet()
        val fresh = images.filter { seen.add(it.uri) }
        return if (fresh.isEmpty()) draft else draft.copy(images = draft.images + fresh)
    }
}
