package io.github.zr987459.pigo.feature.usage.logic

import io.github.zr987459.pigo.contract.AppSettingsStore
import io.github.zr987459.pigo.contract.CompactResult
import io.github.zr987459.pigo.contract.CostDisplaySettings
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ForkResult
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SessionTree
import io.github.zr987459.pigo.contract.UsageStats
import io.github.zr987459.pigo.contract.fake.FakeAppSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 人民币换算（决策 3）走 W0 契约的 [AppSettingsStore.costDisplay] / [AppSettingsStore.setCostDisplay]，
 * 由设置存储持久化，重启不丢（审查第 1 轮 Nit）。界面上的「汇率」= 打开人民币显示时的 `usdToCny`，关闭时为 null。
 */
internal fun CostDisplaySettings.cnyRateOrNull(): Double? = if (showCny) usdToCny else null

/** 用量弹层的状态（U1–U3）。 */
data class UsageUiState(
    val stats: UsageStats? = null,
    val bar: ContextBar = ContextBar.Unknown,
    val costUsd: String = "—",
    val costCny: String? = null,
    val cnyRate: Double? = null,
    val loading: Boolean = false,
    val compacting: Boolean = false,
    val lastCompaction: CompactResult? = null,
    val error: String? = null,
)

/**
 * 用量弹层的状态持有者：读 `get_session_stats`、执行手动压缩。
 * 每轮结束后引擎会刷新 [EngineExtras.usageState]，这里跟着更新；打开弹层时再主动 [refresh] 一次。
 */
class UsageController(
    private val extras: EngineExtras,
    private val scope: CoroutineScope,
    /** 真实实现由 W5 接线时传入（app 的设置存储）；默认是内存实现，仅供界面开发与测试。 */
    private val settings: AppSettingsStore = FakeAppSettingsStore(),
) {
    private val _state = MutableStateFlow(UsageUiState(cnyRate = settings.costDisplay.value.cnyRateOrNull()))
    val state: StateFlow<UsageUiState> = _state.asStateFlow()

    /** 本会话是否出现过上下文统计：压缩后 percent 为 null 时据此显示「压缩后待更新」。 */
    private var hadContext = false
    private var lastSession: String? = null

    init {
        scope.launch { extras.usageState.collect { s -> if (s != null) apply(s) } }
        // 设置在别处（设置页）改了汇率或开关，这里跟着变。
        scope.launch {
            settings.costDisplay.collect { cd ->
                val rate = cd.cnyRateOrNull()
                _state.update { s -> s.copy(cnyRate = rate, costCny = rate?.let { UsageFormat.cny(s.stats?.costUsd, it) }) }
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            when (val r = safe { extras.usage() }) {
                is Outcome.Ok -> apply(r.value)
                is Outcome.Err -> _state.update { it.copy(loading = false, error = "读取用量失败：" + r.message.ifBlank { "未知错误" }) }
            }
        }
    }

    /** 手动压缩；[instructions] 为空白时不带指示。 */
    fun compact(instructions: String?) {
        if (_state.value.compacting) return
        val instr = instructions?.trim()?.takeIf { it.isNotEmpty() }
        _state.update { it.copy(compacting = true, error = null) }
        scope.launch {
            when (val r = safe { extras.compact(instr) }) {
                is Outcome.Ok -> {
                    _state.update { it.copy(compacting = false, lastCompaction = r.value) }
                    safe { extras.usage() }.let { u -> if (u is Outcome.Ok) apply(u.value) }
                }
                is Outcome.Err -> _state.update { it.copy(compacting = false, error = "压缩失败：" + r.message.ifBlank { "未知错误" }) }
            }
        }
    }

    fun setCnyRate(rate: Double?) {
        val cur = settings.costDisplay.value
        val next = if (rate == null) cur.copy(showCny = false) else cur.copy(showCny = true, usdToCny = rate)
        scope.launch {
            try {
                settings.setCostDisplay(next)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update { it.copy(error = "保存汇率失败：" + (t.message ?: "未知错误")) }
            }
        }
        _state.update { s -> s.copy(cnyRate = rate, costCny = rate?.let { UsageFormat.cny(s.stats?.costUsd, it) }) }
    }

    private fun apply(s: UsageStats) {
        if (s.sessionFile != lastSession) {
            lastSession = s.sessionFile
            hadContext = false
        }
        if (s.context != null) hadContext = true
        val c = s.context
        val rate = settings.costDisplay.value.cnyRateOrNull()
        _state.update {
            it.copy(
                stats = s,
                bar = ContextBar.of(c?.percent, c?.tokens, c?.contextWindow, hadContext),
                costUsd = UsageFormat.usd(s.costUsd),
                costCny = rate?.let { r -> UsageFormat.cny(s.costUsd, r) },
                cnyRate = rate,
                loading = false,
            )
        }
    }

    private suspend fun <T> safe(block: suspend () -> Outcome<T>): Outcome<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Outcome.Err(ErrCode.UNKNOWN, t.message ?: "", t)
    }
}

/** 会话树页的状态（S1）。 */
data class TreeUiState(
    val tree: SessionTree? = null,
    val rows: List<TreeRow> = emptyList(),
    val collapsed: Set<String> = emptySet(),
    val loading: Boolean = false,
    val forking: String? = null,
    val error: String? = null,
)

/**
 * 会话树页：`get_tree` 渲染缩进树；「从这里分叉」交给 [fork]（W5 接到 ChatController.forkFrom，
 * 它负责把返回的原文写回新会话的输入框）。「复制会话」走 `clone`。
 */
class SessionTreeController(
    private val extras: EngineExtras,
    private val scope: CoroutineScope,
    private val fork: suspend (String) -> Outcome<ForkResult> = { extras.fork(it) },
) {
    private val _state = MutableStateFlow(TreeUiState())
    val state: StateFlow<TreeUiState> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            when (val r = extras.tree()) {
                is Outcome.Ok -> _state.update { it.copy(tree = r.value, rows = SessionTreeRows.build(r.value, it.collapsed), loading = false) }
                is Outcome.Err -> _state.update { it.copy(loading = false, error = "读取会话树失败：" + r.message.ifBlank { "未知错误" }) }
            }
        }
    }

    fun toggle(id: String) = _state.update { s ->
        val c = if (id in s.collapsed) s.collapsed - id else s.collapsed + id
        s.copy(collapsed = c, rows = s.tree?.let { SessionTreeRows.build(it, c) } ?: s.rows)
    }

    /** 返回分叉结果，供界面在成功后关闭树页、回到聊天。 */
    suspend fun forkFrom(entryId: String): Outcome<ForkResult> {
        _state.update { it.copy(forking = entryId, error = null) }
        val r = fork(entryId)
        _state.update {
            it.copy(forking = null, error = (r as? Outcome.Err)?.let { e -> "分叉失败：" + e.message.ifBlank { "未知错误" } })
        }
        return r
    }

    suspend fun cloneSession(): Outcome<Boolean> = extras.clone()
}
