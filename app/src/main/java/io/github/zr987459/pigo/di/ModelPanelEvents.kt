package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.models.ModelPanels
import io.github.zr987459.pigo.feature.chat.ui.calm.PanelEventSink

/**
 * 把 UI 形态里模型配置面板（`ModelPanels` 产出的 overlay）的点击、键帽、表单、确认框转给 [ModelPanels.onPanelEvent]
 * （KeyCap 点击协议，PR #19 调度者裁定）。面板 id 见 `RealModelPanels` 的 companion：
 * `model.select`、`auth.login`、`auth.logout`、`provider.custom[:id]`、`provider.form:<id>`、`oauth:<id>`、`confirm.*:<id>`。
 */
class ModelPanelEvents(private val panels: ModelPanels) : PanelEventSink {
    override fun owns(overlayId: String): Boolean = isModelPanel(overlayId)

    override suspend fun onEvent(overlayId: String, key: String, value: String?): ActionResult =
        panels.onPanelEvent(overlayId, key, value)

    companion object {
        private val EXACT = setOf("model.select", "auth.login", "auth.logout", "provider.custom")
        private val PREFIXES = listOf("provider.custom:", "provider.form:", "oauth:", "confirm.remove:", "confirm.clear:", "confirm.logout:")

        fun isModelPanel(overlayId: String): Boolean = overlayId in EXACT || PREFIXES.any { overlayId.startsWith(it) }
    }
}
