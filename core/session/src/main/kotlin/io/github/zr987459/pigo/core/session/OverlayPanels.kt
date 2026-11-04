package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.session.OverlayModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * SessionHost 自己产出的 `overlay:*` 面板的回传约定（两种形态一致，PR #18 审查附注）：
 * - SelectList 选中 → `runAction(<面板 id>, <项 key>)`；
 * - Form 提交 → `runAction(<面板 id>, "k=v" 每行一个)` 或表单值 JSON 对象；
 * - Confirm 确定 → `runAction(<面板 id>, "confirm")`；
 * - Tree 选中 → `runAction("overlay:session.tree", <节点 id>)` 或 `runAction("session.tree", <节点 id>)`。
 *
 * [SessionHostImpl.runAction] 把 `overlay:` 开头的 id 交给 [PiActionsImpl.onPanel]，不再落到「未知动作」。
 */
object OverlayPanels {
    const val PREFIX = "overlay:"
    const val TREE = "overlay:session.tree"
    const val RESUME = "overlay:session.resume"
    const val FORK = "overlay:session.fork"
    const val THINKING = "overlay:thinking.select"
    const val SEARCH = "overlay:transcript.search"
    const val HISTORY = "overlay:editor.history"
    const val SETTINGS = "overlay:settings"
    const val SETTINGS_GROUP = "overlay:settings:"
    const val NAME = "overlay:session.name"
    const val CWD = "overlay:session.cwd"
    const val CWD_CONFIRM = "overlay:session.cwd.confirm:"
    const val ONESHOT = "overlay:oneshot"
    const val BUG = "overlay:bug"
    const val SUMMARIZE = "overlay:tree.summarize:"
    const val LABEL = "overlay:tree.label:"
    const val THINKING_ADVANCED = "overlay:model.thinkingAdvanced"
    /** 资源列表：选中 = 把 `/名 ` 放进共享草稿（pi 的补全语义）。 */
    val COMMAND_LISTS = setOf("overlay:skills", "overlay:templates", "overlay:ext.commands")
    /** 由 pi 设置 schema 生成的表单（提交 = 逐键写 settings.json）。 */
    val SETTINGS_FORMS = setOf("overlay:settings.contextFiles", "overlay:context.system", "overlay:trust", THINKING_ADVANCED)

    const val SUMMARIZE_YES = "summarize"
    const val SUMMARIZE_NO = "nosummary"

    /** 面板事件的结果：[result] 交给形态；[close] 是要从栈里移除的面板 id（成功时通常是来源面板）；
     *  [replace] 非空时就地替换栈里同 id 的面板（如改完标签后的分支树）。 */
    data class Result(val result: ActionResult, val close: List<String> = emptyList(), val replace: OverlayModel? = null)

    /**
     * 解析表单回传：JSON 对象（终端形态面板事件的格式）或 `k=v` 行（CalmSheet 的格式）。
     * `k=v` 行按 [keys] 切分：不以已知键开头的行是上一个值的续行（多行文字、字符串列表不会被拆坏）。
     * [keys] 为空时接受任何 `名字=` 开头的行。
     */
    fun parseForm(value: String?, keys: Collection<String>): Map<String, String> {
        val v = value ?: return emptyMap()
        val t = v.trim()
        if (t.startsWith("{")) runCatching { Json.parseToJsonElement(t) as JsonObject }.getOrNull()?.let { o ->
            return o.mapValues { (_, e) ->
                when (e) {
                    is JsonPrimitive -> e.content
                    is JsonArray -> e.joinToString("\n") { (it as? JsonPrimitive)?.content ?: it.toString() }
                    else -> e.toString()
                }
            }
        }
        val out = linkedMapOf<String, String>()
        var cur: String? = null
        for (line in v.split('\n')) {
            val i = line.indexOf('=')
            val k = if (i > 0) line.substring(0, i) else null
            val known = k != null && (if (keys.isEmpty()) KEY.matches(k) else k in keys)
            if (known) { cur = k; out[k!!] = line.substring(i + 1) }
            else if (cur != null) out[cur] = out.getValue(cur) + "\n" + line
        }
        return out
    }

    /** 表单值 → JSON 对象文本（ModelPanels 面板事件的 value 格式）。 */
    fun formJson(values: Map<String, String>): String = JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString()

    fun fieldKeys(m: OverlayModel?): List<String> = (m as? OverlayModel.Form)?.fields?.map { it.key }.orEmpty()

    private val KEY = Regex("[A-Za-z0-9_.\\-]+")
}
