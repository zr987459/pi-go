package io.github.zr987459.pigo.contract.models

import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.session.OverlayModel

/**
 * 模型配置面板（DESIGN-r3 §5.4 依赖反转）：`feature/models` 实现，AppGraph 注入 `core/session`，
 * 供 `PiActions.run` 的 /model、/scoped-models、/login、/logout 等使用；`core/session` 不依赖 `feature/models`。
 * 产出与形态无关的 [OverlayModel]，两种形态各自渲染。
 */
interface ModelPanels {
    /** 模型面板（全部 / 常用 / 最近页签），[query] 为已输入的搜索词。 */
    suspend fun modelPicker(tab: ModelPickerTab = ModelPickerTab.ALL, query: String = ""): OverlayModel

    /** 全部服务商列表（含拼音首字母搜索）。 */
    suspend fun providerList(query: String = ""): OverlayModel

    /** 单个服务商的配置表单（Key、Base URL 等）。 */
    suspend fun providerForm(providerId: String): OverlayModel

    /** 自定义服务商表单；[providerId] 为 null 时新建。 */
    suspend fun customProviderForm(providerId: String? = null): OverlayModel

    /** OAuth 登录流程面板。 */
    suspend fun oauthFlow(providerId: String): OverlayModel

    /** `/logout` 选择器：已登录的服务商。 */
    suspend fun logoutPicker(): OverlayModel

    /** 面板内的操作回传（选中某项、提交表单、点键帽）。 */
    suspend fun onPanelEvent(overlayId: String, key: String, value: String? = null): ActionResult
}

enum class ModelPickerTab { ALL, SCOPED, RECENT }
