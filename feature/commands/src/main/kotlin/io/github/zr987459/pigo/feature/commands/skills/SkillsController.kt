package io.github.zr987459.pigo.feature.commands.skills

import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.Outcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SkillsUiState(
    val items: List<ResourceItem> = emptyList(),
    val loading: Boolean = false,
    /** 正在查看的文件。 */
    val viewing: ResourceItem? = null,
    val viewText: String? = null,
    /** 改过东西之后显示「新会话或重启引擎后生效」。 */
    val needsReload: Boolean = false,
    val message: String? = null,
)

/** 技能与提示模板管理页（C4）的状态持有者。 */
class SkillsController(
    private val extras: EngineExtras,
    private val manager: SkillTemplateManager,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(SkillsUiState())
    val state: StateFlow<SkillsUiState> = _state.asStateFlow()

    fun load(refresh: Boolean = false) {
        _state.update { it.copy(loading = true, message = null) }
        scope.launch {
            when (val r = extras.commands(refresh)) {
                is Outcome.Ok -> {
                    val items = withContext(io) { manager.list(r.value) }
                    _state.update { it.copy(items = items, loading = false) }
                }
                is Outcome.Err -> _state.update { it.copy(loading = false, message = "读取失败：" + r.message) }
            }
        }
    }

    fun view(item: ResourceItem) {
        val path = item.path ?: return
        scope.launch {
            val r = withContext(io) { manager.read(path) }
            _state.update {
                when (r) {
                    is Outcome.Ok -> it.copy(viewing = item, viewText = r.value)
                    is Outcome.Err -> it.copy(message = r.message)
                }
            }
        }
    }

    fun closeView() = _state.update { it.copy(viewing = null, viewText = null) }

    fun setEnabled(item: ResourceItem, enabled: Boolean) {
        scope.launch {
            val r = withContext(io) { manager.setEnabled(item, enabled) }
            when (r) {
                is Outcome.Ok -> _state.update { s ->
                    s.copy(items = s.items.map { if (it.path == item.path && it.kind == item.kind) it.copy(enabled = enabled) else it }, needsReload = true)
                }
                is Outcome.Err -> _state.update { it.copy(message = r.message) }
            }
        }
    }

    /** 新建提示模板；成功返回 true。 */
    suspend fun createTemplate(name: String, description: String, body: String): Boolean {
        val r = withContext(io) { manager.createTemplate(name, description, body) }
        return when (r) {
            is Outcome.Ok -> {
                _state.update { it.copy(needsReload = true, message = "已创建 /${name.trim().removePrefix("/")}，" + SkillTemplateManager.RELOAD_HINT) }
                load(refresh = true)
                true
            }
            is Outcome.Err -> {
                _state.update { it.copy(message = r.message) }
                false
            }
        }
    }
}
