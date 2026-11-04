package io.github.zr987459.pigo.feature.settings

import io.github.zr987459.pigo.contract.models.ModelPanels
import io.github.zr987459.pigo.contract.models.ModelPickerTab
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SelectGroup
import io.github.zr987459.pigo.contract.settings.PiSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 设置页的界面模型（DESIGN-r3 §6.1）：[SettingsScreen] 有 [ModelPanels] 时**逐行渲染**这里的 [sections]，
 * 分区 key 与顺序取自 [ModelsSettings.root]，内容全部来自 `ModelPanels`（与 /model、/login 同一套实现）
 * 和 pi 的 `settings.json`（[PiSettings]）。
 */
data class SettingsModel(
    val sections: List<Section>,
    val query: String = "",
    val footer: String = ModelsSettings.FOOTER_HINT,
) {
    data class Section(val key: String, val title: String, val rows: List<Row>, val searchable: Boolean = false)

    /**
     * 一行。[open] 为点击后的去向：[Open.Panel] 交给 `ModelPanels`（得到面板），[Open.Event] 是
     * `onPanelEvent(overlayId, key)`。[connected] 为 true 显示实心点（已连接 / 已配置）。
     */
    data class Row(
        val key: String,
        val label: String,
        val detail: String? = null,
        val connected: Boolean? = null,
        val open: Open,
    )

    sealed interface Open {
        /** 打开模型面板；[mode] 非 null 时点一行就写默认值 / 切换常用（不切当前会话的模型）。 */
        data class Picker(val tab: ModelPickerTab, val mode: SettingsPanelLogic.PickMode? = null) : Open
        /** 本页内编辑 pi `settings.json` 的 [settingKey]（思考预算、每模型思考档）。 */
        data class Advanced(val settingKey: String) : Open
        data class Event(val overlayId: String, val key: String) : Open
        data object None : Open
    }

    fun section(key: String): Section? = sections.firstOrNull { it.key == key }
}

object SettingsModelBuilder {
    /** `ModelPanels.providerList` 的面板 id（与 feature/models 的 RealModelPanels 一致）。 */
    const val PROVIDERS_OVERLAY = "auth.login"

    /** 高级三项 → pi `settings.json` 的键 / 动作。 */
    private val ADVANCED = listOf(
        Triple("advanced:thinkingBudgets", ModelsSettings.ADVANCED_ITEMS[0], "thinkingBudgets"),
        Triple("advanced:modelThinkingLevels", ModelsSettings.ADVANCED_ITEMS[1], "modelThinkingLevels"),
        Triple("advanced:refresh", ModelsSettings.ADVANCED_ITEMS[2], null),
    )

    /**
     * 组装设置页。[query] 是「全部服务商」上的搜索词（拼音首字母由 `ModelPanels.providerList` 处理）。
     * 分区：默认模型、常用模型、已连接、全部服务商（N）、自定义、高级。搜索时「已连接」「全部服务商」都按搜索词过滤。
     */
    suspend fun build(panels: ModelPanels, piSettings: PiSettings?, query: String = ""): SettingsModel {
        val spec = ModelsSettings.root().single().sections.associateBy { it.key }
        val values = piSettings?.values?.value.orEmpty()

        // 默认模型
        val dp = values.str("defaultProvider")
        val dm = values.str("defaultModel")
        val dt = values.str("defaultThinkingLevel")
        val defaultDetail = if (dp != null && dm != null) listOfNotNull("$dp/$dm", dt).joinToString(" · ") else "未设置"
        val default = SettingsModel.Section(
            "default", spec.getValue("default").title,
            listOf(SettingsModel.Row("default", "默认模型", defaultDetail, open = SettingsModel.Open.Picker(ModelPickerTab.ALL, SettingsPanelLogic.PickMode.DEFAULT))),
        )

        // 常用模型（= enabledModels / scoped-models）
        val scopedPanel = panels.modelPicker(ModelPickerTab.SCOPED, "") as? OverlayModel.SelectList
        val scopedCount = scopedPanel?.groups?.sumOf { it.items.size } ?: 0
        val scoped = SettingsModel.Section(
            "scoped", spec.getValue("scoped").title,
            listOf(SettingsModel.Row("scoped", "常用模型", if (scopedCount == 0) "未设置" else "$scopedCount 个", open = SettingsModel.Open.Picker(ModelPickerTab.ALL, SettingsPanelLogic.PickMode.SCOPED))),
        )

        // 服务商：同一个 providerList 面板拆成已连接 / 全部 / 自定义三段
        val list = panels.providerList(query) as? OverlayModel.SelectList
        val groups = list?.groups.orEmpty()
        val overlayId = list?.id ?: PROVIDERS_OVERLAY
        fun group(prefix: String): SelectGroup? = groups.firstOrNull { it.title?.startsWith(prefix) == true }
        fun rows(g: SelectGroup?) = g?.items.orEmpty().map {
            SettingsModel.Row(it.key, it.label, it.detail, connected = it.toggled, open = SettingsModel.Open.Event(overlayId, it.key))
        }
        val connectedRows = rows(group("已连接"))
        val restRows = rows(group("全部服务商"))
        // 全部服务商 = 已连接 + 未连接（已连接的在前，实心点），总数取面板标题里的 N
        val total = group("全部服务商")?.title?.let { Regex("（(\\d+)）").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: (connectedRows.size + restRows.size)
        val connected = SettingsModel.Section("connected", spec.getValue("connected").title, connectedRows)
        val providers = SettingsModel.Section("providers", "全部服务商（$total）", connectedRows + restRows, searchable = true)
        val custom = SettingsModel.Section("custom", spec.getValue("custom").title, rows(group("自定义")))

        val advanced = SettingsModel.Section(
            "advanced", spec.getValue("advanced").title,
            ADVANCED.map { (key, label, settingKey) ->
                val detail = settingKey?.let { k -> if (piSettings == null) "暂不可编辑" else values[k]?.let { "已设置" } ?: "默认" } ?: "从 pi 重新读取"
                val open = when {
                    settingKey == null -> SettingsModel.Open.Event("model.select", "refresh")
                    piSettings == null -> SettingsModel.Open.None
                    else -> SettingsModel.Open.Advanced(settingKey)
                }
                SettingsModel.Row(key, label, detail, open = open)
            },
        )
        return SettingsModel(listOf(default, scoped, connected, providers, custom, advanced), query)
    }

    private fun Map<String, JsonElement>.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
