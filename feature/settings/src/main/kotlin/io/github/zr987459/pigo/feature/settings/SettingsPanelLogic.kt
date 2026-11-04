package io.github.zr987459.pigo.feature.settings

import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.session.FormField
import io.github.zr987459.pigo.contract.session.KeyCap
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.settings.PiSettingType
import io.github.zr987459.pigo.contract.settings.PiSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 设置页面板交互的纯逻辑（[ModelsSettingsPage] 只负责画；这里可在 JVM 单测）。
 *
 * 键帽协议见契约 [KeyCap]：点击产生面板事件 `onPanelEvent(overlay.id, actionId, 高亮项)`，由本页（面板宿主）分发。
 */
object SettingsPanelLogic {
    const val MODEL_OVERLAY = "model.select"

    /** 从「默认模型」「常用模型」行打开模型面板时的选取模式：点一行就写默认值 / 切换常用，**不切当前会话的模型**。 */
    enum class PickMode { DEFAULT, SCOPED }

    sealed interface Click {
        /** 立即发面板事件。 */
        data class Event(val key: String, val value: String? = null) : Click
        /** 只高亮该行（之后键帽作用于它）。 */
        data class Highlight(val key: String) : Click
    }

    /**
     * 点击列表行：
     * - 模型面板 + [PickMode.DEFAULT] → `default:<ref>`（写 defaultProvider / defaultModel / defaultThinkingLevel）；
     * - 模型面板 + [PickMode.SCOPED] → `star:<ref>`（切换 enabledModels）；
     * - 模型面板、没有选取模式 → 只高亮，由键帽（选择 / 设为默认 / 常用）作用于高亮项；
     * - 其他面板（服务商列表、OAuth 选项、退出登录）→ 直接发该行的 key。
     */
    fun rowClick(model: OverlayModel.SelectList, itemKey: String, mode: PickMode?): Click {
        if (model.id != MODEL_OVERLAY || !itemKey.startsWith("model:")) return Click.Event(itemKey)
        val ref = itemKey.removePrefix("model:")
        return when (mode) {
            PickMode.DEFAULT -> Click.Event("default:$ref")
            PickMode.SCOPED -> Click.Event("star:$ref")
            null -> Click.Highlight(itemKey)
        }
    }

    /** 键帽回传的 value：用户高亮的那一项；没有高亮时才退回面板自己的 selectedKey（当前模型）。 */
    fun keyCapValue(model: OverlayModel.SelectList, highlighted: String?): String? = highlighted ?: model.selectedKey

    /** 选取模式下去掉「选择」键帽（它会切当前会话的模型，与设置页的意图不符）。 */
    fun visibleKeyCaps(model: OverlayModel.SelectList, mode: PickMode?): List<KeyCap> =
        if (mode != null && model.id == MODEL_OVERLAY) model.keyCaps.filterNot { it.actionId == "select" || it.actionId.endsWith(":select") }
        else model.keyCaps

    /** 只有 [ActionResult.Done] 关闭面板；Toast 只显示消息、面板保持打开（不丢刚输入的内容）。 */
    fun closesPanel(result: ActionResult): Boolean = result == ActionResult.Done

    /** 关闭面板时要发给面板的事件：OAuth 取消登录、自定义服务商丢弃草稿与 Key；其他面板不发。 */
    fun dismissEvent(model: OverlayModel): String? = when {
        model.id.startsWith("oauth:") && model is OverlayModel.Text && !isOAuthFinal(model) -> "cancel"
        model.id.startsWith("provider.custom") -> "cancel"
        else -> null
    }

    /** OAuth 面板是否到了终点（成功 / 失败）；没到终点时本页每秒发一次 `refresh` 拉取最新进度。 */
    fun isOAuthFinal(model: OverlayModel.Text): Boolean = model.text.startsWith("已登录") || model.text.startsWith("登录失败")

    fun needsOAuthPolling(model: OverlayModel?): Boolean =
        model is OverlayModel.Text && model.id.startsWith("oauth:") && !isOAuthFinal(model)

    private val URL = Regex("https?://\\S+")

    /** OAuth 面板里的授权链接（OpenUrl / DeviceCode）；有就自动打开，并给一个「打开链接」按钮。 */
    fun oauthUrl(model: OverlayModel?): String? =
        (model as? OverlayModel.Text)?.takeIf { it.id.startsWith("oauth:") }?.let { URL.find(it.text)?.value }

    /** Form 的附加按钮：优先用面板自带的 [OverlayModel.Form.actions]。 */
    fun formActions(model: OverlayModel.Form): List<Pair<String, String>> =
        model.actions.map { it.actionId to it.label }.ifEmpty { formActions(model.id) }

    /** 旧面板（没带 actions）时的兜底。 */
    fun formActions(overlayId: String): List<Pair<String, String>> = when {
        overlayId.startsWith("provider.form:") -> listOf("test" to "测试", "oauth" to "账号登录", "clear" to "清除")
        overlayId.startsWith("provider.custom") -> listOf("discover" to "获取模型", "test" to "测试")
        else -> emptyList()
    }

    // ---------------------------------------------------------------- 高级：思考预算 / 每模型思考档（直接写 pi settings）

    const val ADVANCED_PREFIX = "settings.advanced:"
    val BUDGET_LEVELS = listOf("minimal", "low", "medium", "high")

    fun isAdvanced(overlayId: String) = overlayId.startsWith(ADVANCED_PREFIX)

    /** 「思考预算」「每模型思考档」的编辑表单（本页自己画，提交写 pi `settings.json` 的同名键）。 */
    fun advancedForm(settingKey: String, values: Map<String, JsonElement>): OverlayModel.Form? = when (settingKey) {
        "thinkingBudgets" -> {
            val cur = values["thinkingBudgets"] as? JsonObject
            OverlayModel.Form(
                id = ADVANCED_PREFIX + settingKey,
                title = "思考预算（token）",
                fields = BUDGET_LEVELS.map { FormField(it, it, PiSettingType.Number(min = 0.0, integer = true), help = "留空 = 用 pi 默认值") },
                values = BUDGET_LEVELS.mapNotNull { l -> (cur?.get(l) as? JsonPrimitive)?.content?.let { l to it } }.toMap(),
                submitLabel = "保存",
            )
        }
        "modelThinkingLevels" -> {
            val cur = values["modelThinkingLevels"] as? JsonObject
            OverlayModel.Form(
                id = ADVANCED_PREFIX + settingKey,
                title = "每模型思考档",
                fields = listOf(FormField("entries", "模型=思考档（每行一个）", PiSettingType.StrList, help = "例如 anthropic/claude-sonnet-4-5=high；留空 = 全部清除")),
                values = mapOf("entries" to cur?.entries?.joinToString("\n") { (k, v) -> "$k=${(v as? JsonPrimitive)?.content.orEmpty()}" }.orEmpty()),
                submitLabel = "保存",
            )
        }
        else -> null
    }

    /** 解析表单提交：Ok(null) = 删除该键（回到默认）；Err = 输入有误，不写。 */
    fun parseAdvanced(settingKey: String, form: Map<String, String>): Outcome<JsonElement?> = when (settingKey) {
        "thinkingBudgets" -> {
            val out = linkedMapOf<String, JsonElement>()
            var bad: String? = null
            for (l in BUDGET_LEVELS) {
                val v = form[l]?.trim().orEmpty()
                if (v.isEmpty()) continue
                val n = JsonPrimitive(v).intOrNull
                if (n == null || n < 0) bad = l else out[l] = JsonPrimitive(n)
            }
            if (bad != null) Outcome.Err(io.github.zr987459.pigo.contract.ErrCode.UNKNOWN, "$bad 要填不小于 0 的整数")
            else Outcome.Ok(if (out.isEmpty()) null else JsonObject(out))
        }
        "modelThinkingLevels" -> {
            val out = linkedMapOf<String, JsonElement>()
            var bad: String? = null
            form["entries"].orEmpty().split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                val i = line.indexOf('=')
                val k = if (i > 0) line.substring(0, i).trim() else ""
                val v = if (i > 0) line.substring(i + 1).trim() else ""
                if (k.isEmpty() || v !in THINKING_LEVELS) bad = line else out[k] = JsonPrimitive(v)
            }
            if (bad != null) Outcome.Err(io.github.zr987459.pigo.contract.ErrCode.UNKNOWN, "无法识别：$bad（思考档只能是 ${THINKING_LEVELS.joinToString("/")}）")
            else Outcome.Ok(if (out.isEmpty()) null else JsonObject(out))
        }
        else -> Outcome.Err(io.github.zr987459.pigo.contract.ErrCode.UNKNOWN, "未知设置：$settingKey")
    }

    val THINKING_LEVELS = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

    /** 提交高级表单：解析 → `PiSettings.set`；返回给页面显示的结果（成功关闭面板）。 */
    suspend fun submitAdvanced(piSettings: PiSettings, overlayId: String, form: Map<String, String>): ActionResult {
        val key = overlayId.removePrefix(ADVANCED_PREFIX)
        return when (val p = parseAdvanced(key, form)) {
            is Outcome.Err -> ActionResult.Toast(p.message)
            is Outcome.Ok -> when (val r = piSettings.set(key, p.value)) {
                is Outcome.Ok -> ActionResult.Done
                is Outcome.Err -> ActionResult.Toast("保存失败：${r.message}")
            }
        }
    }
}
