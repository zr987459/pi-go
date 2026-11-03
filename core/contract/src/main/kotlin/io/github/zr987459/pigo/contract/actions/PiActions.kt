package io.github.zr987459.pigo.contract.actions

import io.github.zr987459.pigo.contract.session.OverlayModel

/**
 * 一个 pi 功能在两种形态里的入口（DESIGN-r3 §5.4）。全部动作列在 [PiActionCatalog]；
 * 覆盖测试按 [id] 与 `docs/r3/coverage-map.tsv` 核对（§7.1）。
 */
data class PiActionSpec(
    /** 如 "session.fork"。 */
    val id: String,
    /** 中文标题，如「从消息分叉」；⌘ 里按它、斜杠名、拼音首字母搜索。 */
    val title: String,
    val group: ActionGroup,
    /** 斜杠名（不带 `/`），如 ["fork"]；没有斜杠命令时为空。 */
    val slash: List<String>,
    /** 对应清单行，如 ["§2.1 fork", "§3 /fork"]；不得为空。 */
    val featureRefs: List<String>,
    val ui: UiEntry,
    val term: TermEntry,
)

enum class ActionGroup { SESSION, MODEL, SETTINGS, RESOURCES, EXT, HELP }

/** UI 形态入口。 */
sealed interface UiEntry {
    /** ⌘ 命令中心里的一项。 */
    data object Command : UiEntry

    /** 常驻控件；[tag] 是 Compose testTag。 */
    data class Persistent(val tag: String) : UiEntry

    /** 长按菜单项；[tag] 是菜单项 testTag。 */
    data class LongPress(val tag: String) : UiEntry

    /** 说明条目（清单写不适用 / 只做说明的项）；[text] 不得为空。 */
    data class Explain(val text: String) : UiEntry
}

/** 终端形态入口。 */
sealed interface TermEntry {
    /** 斜杠命令（取 [PiActionSpec.slash] 第一项）。 */
    data object Slash : TermEntry

    /** 编辑器下方触屏键条上的键。 */
    data class KeyStrip(val key: String) : TermEntry

    /** 页脚上可点的段。 */
    data class Footer(val segment: String) : TermEntry

    /** 某个选择器底部的键帽。 */
    data class KeyCap(val selector: String, val key: String) : TermEntry

    /** 说明条目；[text] 不得为空。 */
    data class Explain(val text: String) : TermEntry
}

interface PiActions {
    val all: List<PiActionSpec>

    /** 执行动作；两种形态共用同一实现（`core/session`，r3/session）。 */
    suspend fun run(id: String, args: String? = null): ActionResult
}

sealed interface ActionResult {
    data object Done : ActionResult

    /** 压进 overlay 栈，由各形态自己画。 */
    data class Overlay(val model: OverlayModel) : ActionResult

    data class Toast(val text: String) : ActionResult

    data class LocalBlock(val title: String, val text: String) : ActionResult
}
