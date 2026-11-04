package io.github.zr987459.pigo.core.session.text

import io.github.zr987459.pigo.contract.SessionTree
import io.github.zr987459.pigo.contract.TreeNode

/** 会话树页的一行（S1）：按缩进画树，标出当前分支与叶子。 */
data class TreeRow(
    val id: String,
    val depth: Int,
    val role: String?,
    val type: String,
    val text: String,
    val label: String?,
    val isLeaf: Boolean,
    /** 在当前分支上（根 → [SessionTree.leafId] 的路径）。 */
    val onCurrentPath: Boolean,
    val isCurrentLeaf: Boolean,
    /** 用户消息才能「从这里分叉」（pi `fork{entryId}` 只接受用户消息）。 */
    val canFork: Boolean,
    /** 这个节点下有多个分支。 */
    val branches: Int,
)

object SessionTreeRows {
    /**
     * 先序遍历；单链（只有一个子节点）不加缩进，只有分叉处才缩进一级，长会话也不会越缩越深。
     * 同一层的分支里，当前分支排在最前。
     */
    fun build(tree: SessionTree, collapsed: Set<String> = emptySet()): List<TreeRow> {
        val path = pathTo(tree, tree.leafId)
        val out = ArrayList<TreeRow>()
        fun walk(n: TreeNode, depth: Int) {
            val e = n.entry
            out += TreeRow(
                id = e.id, depth = depth, role = e.role, type = e.type, text = e.preview.lineSequence().firstOrNull().orEmpty().take(200),
                label = n.label, isLeaf = n.children.isEmpty(), onCurrentPath = e.id in path,
                isCurrentLeaf = e.id == tree.leafId, canFork = e.type == "message" && e.role == "user",
                branches = n.children.size,
            )
            if (e.id in collapsed) return
            val kids = n.children.sortedByDescending { it.entry.id in path }
            val nextDepth = if (kids.size > 1) depth + 1 else depth
            kids.forEach { walk(it, nextDepth) }
        }
        val roots = tree.roots.sortedByDescending { it.entry.id in path }
        roots.forEach { walk(it, if (roots.size > 1) 1 else 0) }
        return out
    }

    /** 根到 [leafId] 的节点 id 集合；找不到时为空。 */
    fun pathTo(tree: SessionTree, leafId: String?): Set<String> {
        if (leafId == null) return emptySet()
        val parent = HashMap<String, String?>()
        val stack = ArrayDeque<Pair<TreeNode, String?>>()
        tree.roots.forEach { stack.addLast(it to null) }
        while (stack.isNotEmpty()) {
            val (n, p) = stack.removeLast()
            parent[n.entry.id] = p
            n.children.forEach { stack.addLast(it to n.entry.id) }
        }
        if (leafId !in parent) return emptySet()
        val out = LinkedHashSet<String>()
        var cur: String? = leafId
        while (cur != null && out.add(cur)) cur = parent[cur]
        return out
    }

    fun leafCount(tree: SessionTree): Int = tree.leaves().size
}
