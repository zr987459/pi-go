package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.session.OverlayStack

/**
 * 选择面板选完即关（r3/app PR 2，交接残留）：`/model` 里选中一个模型并切换成功后，关掉模型选择面板，提示照常显示。
 * UI 形态（CalmPanelLogic）与终端形态（TermPanelRouter）都把 Toast 当作「面板保持打开」，所以由装配层在结果落地前关面板，
 * 两种形态走同一份逻辑。切换失败、去配置服务商（返回 Overlay）、星标 / 设为默认等键帽都不关。
 */
object PickClose {
    const val MODEL_PICKER = "model.select"

    /** 选中一个模型的事件：点行（`model:<ref>`）或「选用」键帽（`select`，value = 高亮项）。 */
    fun isPick(overlayId: String, key: String): Boolean =
        overlayId == MODEL_PICKER && (key.startsWith("model:") || key == "select")

    fun closes(overlayId: String, key: String, r: ActionResult): Boolean =
        isPick(overlayId, key) && r is ActionResult.Toast && r.text.startsWith("已切换")

    fun after(stack: OverlayStack, overlayId: String, key: String, r: ActionResult): ActionResult {
        if (closes(overlayId, key, r)) stack.remove(overlayId)
        return r
    }
}
