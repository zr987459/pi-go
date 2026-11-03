package io.github.zr987459.pigo.feature.chat.logic

import androidx.compose.runtime.Immutable
import io.github.zr987459.pigo.contract.ExtUiRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 扩展 UI 子协议（DESIGN.md §3.6）在界面侧的分类。
 *
 * - 对话类（select/confirm/input/editor）阻塞 pi，必须应答：弹原生对话框，关闭 = cancelled。
 * - 通知类（notify/setStatus/setWidget/setTitle/set_editor_text）不需要应答。
 * - 其他一律视为未实现的 method，**立即回 cancelled**，避免 pi 永久挂起。
 */
sealed interface ExtUiAction {
    data class Dialog(val request: ExtUiRequest) : ExtUiAction
    data class Notify(val text: String, val level: String?) : ExtUiAction

    /** [text] 为 null 表示清除该条。 */
    data class Status(val key: String, val text: String?) : ExtUiAction
    data class Title(val title: String?) : ExtUiAction
    data class EditorText(val text: String) : ExtUiAction
    data class CancelUnknown(val id: String, val method: String) : ExtUiAction
}

/** 对话框的应答（与 `EngineClient.answerUi` 一一对应）。 */
@Immutable
data class ExtUiAnswer(val value: String? = null, val confirmed: Boolean? = null, val cancelled: Boolean = false) {
    companion object {
        val CANCELLED = ExtUiAnswer(cancelled = true)
    }
}

object ExtUi {
    val DIALOG_METHODS = ExtUiRequest.BLOCKING_METHODS
    val FIRE_AND_FORGET = setOf("notify", "setStatus", "setWidget", "setTitle", "set_editor_text")

    fun classify(r: ExtUiRequest): ExtUiAction = when (r.method) {
        in DIALOG_METHODS -> ExtUiAction.Dialog(r)
        "notify" -> ExtUiAction.Notify(
            text = r.message ?: r.raw.str("message") ?: r.title ?: "",
            level = r.raw.str("notifyType") ?: r.raw.str("level"),
        )
        "setStatus" -> ExtUiAction.Status(
            key = "status:" + (r.raw.str("statusKey") ?: r.raw.str("key") ?: "default"),
            text = r.raw.str("statusText") ?: r.raw.str("text"),
        )
        "setWidget" -> ExtUiAction.Status(
            key = "widget:" + (r.raw.str("widgetKey") ?: r.raw.str("key") ?: "default"),
            text = r.raw.lines("widgetLines")?.joinToString("\n") ?: r.raw.str("text"),
        )
        "setTitle" -> ExtUiAction.Title(r.title ?: r.raw.str("title"))
        "set_editor_text" -> ExtUiAction.EditorText(r.raw.str("text") ?: r.prefill ?: "")
        else -> ExtUiAction.CancelUnknown(r.id, r.method)
    }

    /** 对话框标题：扩展没给时用中文默认值。 */
    fun titleOf(r: ExtUiRequest): String = r.title?.takeIf { it.isNotBlank() } ?: when (r.method) {
        "select" -> "请选择"
        "confirm" -> "请确认"
        "input" -> "请输入"
        "editor" -> "编辑内容"
        else -> "扩展请求"
    }

    /** 倒计时剩余秒数；没有 timeout 时为 null。 */
    fun remainingSeconds(r: ExtUiRequest, shownAtMillis: Long, nowMillis: Long): Long? {
        val t = r.timeoutMs ?: return null
        val left = t - (nowMillis - shownAtMillis)
        return if (left <= 0) 0 else (left + 999) / 1000
    }

    private fun JsonObject.str(key: String): String? = when (val v = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> v.contentOrNull
        else -> v.toString()
    }

    private fun JsonObject.lines(key: String): List<String>? =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
