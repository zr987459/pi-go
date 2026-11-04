package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.SessionTree
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.TreeFilter
import io.github.zr987459.pigo.contract.session.TreeNode
import java.time.Instant

/**
 * `/tree` 的视图（清单 §5：筛选、折叠、标签、时间）。纯函数，两种形态共用。
 *
 * 筛选语义按 pi 1.5.0 `treeFilterMode`：
 * - default：对话消息（用户、助手）与压缩 / 分支摘要，隐藏工具结果和设置类记录（模型切换、思考档、标签、会话信息）；
 * - no-tools：在 default 基础上再隐藏 `!` 命令（bashExecution）记录；
 * - user-only：只看用户消息；labeled-only：只看有标签的记录；all：全部记录。
 * 被筛掉的节点不画，其子节点挂到最近的可见祖先下（[TreeNode.parentId] 改写）。
 */
object TreeShape {
    const val ALL = "*"

    private val SETTING_TYPES = setOf("model_change", "thinking_level_change", "label", "session_info", "usage", "custom")

    fun visible(n: io.github.zr987459.pigo.contract.TreeNode, f: TreeFilter): Boolean {
        val e = n.entry
        return when (f) {
            TreeFilter.ALL -> true
            TreeFilter.USER_ONLY -> e.role == "user"
            TreeFilter.LABELED_ONLY -> !n.label.isNullOrBlank()
            TreeFilter.DEFAULT -> e.type !in SETTING_TYPES && e.role != "toolResult"
            TreeFilter.NO_TOOLS -> e.type !in SETTING_TYPES && e.role != "toolResult" && e.role != "bashExecution"
        }
    }

    fun allParents(t: SessionTree): Set<String> {
        val out = HashSet<String>()
        fun walk(n: io.github.zr987459.pigo.contract.TreeNode) { if (n.children.isNotEmpty()) out += n.entry.id; n.children.forEach(::walk) }
        t.roots.forEach(::walk)
        return out
    }

    fun overlay(t: SessionTree, filter: TreeFilter, collapsed: Set<String>, showTime: Boolean): OverlayModel.Tree {
        val nodes = ArrayList<TreeNode>()
        fun walk(n: io.github.zr987459.pigo.contract.TreeNode, visibleParent: String?, hidden: Boolean) {
            val show = !hidden && (visible(n, filter) || n.entry.id == t.leafId)
            val id = n.entry.id
            if (show) nodes += TreeNode(
                id = id, parentId = visibleParent, role = n.entry.role ?: n.entry.type, text = n.entry.preview, label = n.label,
                timestampMillis = n.entry.timestamp?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                collapsed = id in collapsed && n.children.isNotEmpty(), isLeaf = id == t.leafId,
            )
            val childHidden = hidden || (show && id in collapsed)
            n.children.forEach { walk(it, if (show) id else visibleParent, childHidden) }
        }
        t.roots.forEach { walk(it, null, false) }
        return OverlayModel.Tree("overlay:session.tree", "分支树", nodes, filter = filter, showTime = showTime, selectedId = t.leafId)
    }
}
