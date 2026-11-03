package io.github.zr987459.pigo.contract.fake

import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.actions.PiActionCatalog
import io.github.zr987459.pigo.contract.actions.PiActionSpec
import io.github.zr987459.pigo.contract.actions.PiActions
import io.github.zr987459.pigo.contract.actions.TermEntry
import io.github.zr987459.pigo.contract.actions.UiEntry
import io.github.zr987459.pigo.contract.session.OverlayModel

/**
 * 动作表占位实现（开发期给 r3/calm、r3/term 用）：
 * - 说明条目 → [ActionResult.LocalBlock]（标题 `explain:<id>`）；
 * - 其他动作 → 返回 [ActionResult.Overlay]（[OverlayModel.Text]，id = 动作 id，界面据此打 `overlay:<id>` 标记）；
 *   **不压栈**：压栈由 `SessionHost.runAction` 做一次（见 [PiActions] 说明）；
 * - 每次调用都记进 [ran]，并在引擎命令日志里记 `action:<id>`（不是 pi RPC，只供断言）。
 *
 * 真正的 `run` 由 r3/session 在 `core/session` 实现。
 */
class FakePiActions(
    private val engine: FakeScriptedEngine,
    override val all: List<PiActionSpec> = PiActionCatalog.all,
) : PiActions {

    private val _ran = mutableListOf<Pair<String, String?>>()
    val ran: List<Pair<String, String?>> get() = _ran.toList()

    /** 测试可替换某个动作的结果。 */
    val overrides = mutableMapOf<String, suspend (String?) -> ActionResult>()

    override suspend fun run(id: String, args: String?): ActionResult {
        _ran += id to args
        overrides[id]?.let { return it(args) }
        val spec = all.firstOrNull { it.id == id } ?: return ActionResult.Toast("未知动作：$id")
        engine.record("action:$id", args?.let { mapOf("args" to it) } ?: emptyMap())
        val explain = (spec.ui as? UiEntry.Explain)?.text ?: (spec.term as? TermEntry.Explain)?.text
        if (explain != null) return ActionResult.LocalBlock("explain:$id", explain)
        val model = OverlayModel.Text(id = id, title = spec.title, text = "占位：「${spec.title}」由 r3/session 实现。")
        return ActionResult.Overlay(model)
    }
}
