package io.github.zr987459.pigo.contract.session

import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.settings.PiSettingType

/**
 * 与形态无关的面板模型（DESIGN-r3 §5.4）。两种形态各画一套（CalmSheet / TermSelectList），逻辑只有一份。
 * 打开中的面板放在 [OverlayStack]，切换形态时原样保留（含搜索词、页签、滚动位置、表单已填值）。
 *
 * [id] 是面板标识，覆盖测试据此找 `overlay:<id>` 标记；一般与打开它的动作 id 相同。
 */
sealed interface OverlayModel {
    val id: String
    val title: String

    /** 选择列表：分组、搜索词、星标 / 开关 / 拖动排序、键帽。 */
    data class SelectList(
        override val id: String,
        override val title: String,
        val groups: List<SelectGroup> = emptyList(),
        val query: String = "",
        val tabs: List<String> = emptyList(),
        val tab: String? = null,
        val scrollIndex: Int = 0,
        val selectedKey: String? = null,
        val reorderable: Boolean = false,
        val keyCaps: List<KeyCap> = emptyList(),
    ) : OverlayModel

    /** 表单：字段按 schema（开关、枚举、数字、字符串、字符串列表）；[values] 是已填值（字符串化）。 */
    data class Form(
        override val id: String,
        override val title: String,
        val fields: List<FormField> = emptyList(),
        val values: Map<String, String> = emptyMap(),
        val submitLabel: String? = null,
    ) : OverlayModel

    data class Text(
        override val id: String,
        override val title: String,
        val text: String,
    ) : OverlayModel

    data class Confirm(
        override val id: String,
        override val title: String,
        val message: String,
        val confirmLabel: String = "确定",
        val dangerous: Boolean = false,
    ) : OverlayModel

    /** 会话分支树：节点、折叠、标签、时间、筛选。 */
    data class Tree(
        override val id: String,
        override val title: String,
        val nodes: List<TreeNode> = emptyList(),
        val filter: TreeFilter = TreeFilter.DEFAULT,
        val showTime: Boolean = false,
        val selectedId: String? = null,
        val query: String = "",
    ) : OverlayModel

    /**
     * 扩展阻塞对话框（select / confirm / input / editor）。request id 与超时由 ChatController 持有；
     * 切换形态时新形态立刻画同一个对话框，计时不中断，不回 cancelled（§5.3）。
     */
    data class ExtDialog(
        override val id: String,
        override val title: String,
        val request: ExtUiRequest,
        val shownAtMillis: Long,
        /** input / editor 已输入的值、select 当前高亮项。 */
        val value: String = request.prefill ?: "",
    ) : OverlayModel

    /** 工具输出被截断时的完整输出（`full_output_path`）。 */
    data class FullOutput(
        override val id: String,
        override val title: String,
        val path: String,
    ) : OverlayModel
}

data class SelectGroup(val title: String?, val items: List<SelectItem>)

data class SelectItem(
    val key: String,
    val label: String,
    val detail: String? = null,
    /** null = 不显示星标。 */
    val starred: Boolean? = null,
    /** null = 不是开关项。 */
    val toggled: Boolean? = null,
    val enabled: Boolean = true,
    /** 终端形态右侧的键帽提示，如 "Enter"。 */
    val keyHint: String? = null,
)

/** 面板底部 / 行内的键帽（终端）或按钮（UI），点了执行 [actionId]。 */
data class KeyCap(val key: String, val label: String, val actionId: String)

data class FormField(
    val key: String,
    val label: String,
    val type: PiSettingType,
    val help: String? = null,
    val enabled: Boolean = true,
)

data class TreeNode(
    val id: String,
    val parentId: String?,
    val role: String,
    val text: String,
    val label: String? = null,
    val timestampMillis: Long? = null,
    val collapsed: Boolean = false,
    val isLeaf: Boolean = false,
)

/** 与 pi `/tree` 的五种筛选一致（清单 §5）。 */
enum class TreeFilter(val piName: String) {
    DEFAULT("default"), NO_TOOLS("no-tools"), USER_ONLY("user-only"), LABELED_ONLY("labeled-only"), ALL("all"),
}
