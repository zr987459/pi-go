package io.github.zr987459.pigo.contract.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 面板模型栈（DESIGN-r3 §5.1）。属于 [SessionHost]，与进程同寿；两种形态都画栈顶。
 * 更新搜索词、页签、已填值时用 [replaceTop]，栈本身不随形态变化。
 */
class OverlayStack {
    private val _items = MutableStateFlow<List<OverlayModel>>(emptyList())
    val items: StateFlow<List<OverlayModel>> = _items.asStateFlow()

    val top: OverlayModel? get() = _items.value.lastOrNull()

    fun push(model: OverlayModel) = _items.update { it + model }

    fun pop(): OverlayModel? {
        var popped: OverlayModel? = null
        _items.update { if (it.isEmpty()) it else { popped = it.last(); it.dropLast(1) } }
        return popped
    }

    /** 替换栈顶（栈空时等同 push）。 */
    fun replaceTop(model: OverlayModel) = _items.update { if (it.isEmpty()) listOf(model) else it.dropLast(1) + model }

    /** 按 id 移除（例如扩展对话框超时或被应答）。 */
    fun remove(id: String) = _items.update { list -> list.filterNot { it.id == id } }

    fun clear() { _items.value = emptyList() }

    /**
     * 一次原子地改写整个栈（单次 `update`，可能重试 [transform]，所以它必须是纯函数）。
     * 供宿主做「回到同 id 面板」「就地替换」「同步扩展对话框」这类多步调整，避免与其他协程的 push / remove 交错。
     */
    fun update(transform: (List<OverlayModel>) -> List<OverlayModel>) = _items.update(transform)
}
