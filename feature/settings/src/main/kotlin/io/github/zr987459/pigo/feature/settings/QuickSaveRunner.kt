package io.github.zr987459.pigo.feature.settings

import io.github.zr987459.pigo.contract.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 一次「保存并使用」要的全部输入（Key 只在内存里，保存结束即丢）。 */
data class QuickSaveRequest(val presetId: String, val key: String, val model: String, val models: List<String>, val baseUrl: String)

/** 「保存并使用」的进度：页面只观察它。 */
sealed interface QuickSaveState {
    data object Idle : QuickSaveState
    data class Running(val message: String) : QuickSaveState
    data class Done(val message: String) : QuickSaveState
    data class Failed(val message: String) : QuickSaveState
}

/**
 * 把「保存并使用」放到 App 级作用域里跑（PR #29 审查 I2）：写配置 → 重启 pi → set_model 要几秒到几十秒，
 * 期间返回、旋转屏幕、Activity 重建都不会把它取消在半路；页面重新出现时从 [state] 接着显示进度或结果。
 *
 * @param scope App 级作用域（`AppGraph.appScope`），不是页面的 rememberCoroutineScope
 * @param save 真正的保存；第二个参数用来汇报进度
 * @param onFinished 结束时（成功或失败）回调一句话，装配层用它弹全局提示，用户已离开页面也能看到
 */
class QuickSaveRunner(
    private val scope: CoroutineScope,
    private val save: suspend (QuickSaveRequest, (String) -> Unit) -> Outcome<String>,
    private val onFinished: (QuickCheck) -> Unit = {},
) {
    private val _state = MutableStateFlow<QuickSaveState>(QuickSaveState.Idle)
    val state: StateFlow<QuickSaveState> = _state.asStateFlow()

    /** 开始保存；已在保存中时不重复开始，返回 false。 */
    fun start(request: QuickSaveRequest): Boolean {
        synchronized(this) {
            if (_state.value is QuickSaveState.Running) return false
            _state.value = QuickSaveState.Running(STARTING)
        }
        scope.launch {
            val result = try {
                when (val r = save(request) { msg -> _state.value = QuickSaveState.Running(msg) }) {
                    is Outcome.Ok -> QuickCheck(true, r.value)
                    is Outcome.Err -> QuickCheck(false, r.message)
                }
            } catch (e: CancellationException) {
                _state.value = QuickSaveState.Failed("保存被中断，请重新保存")
                throw e
            } catch (e: Exception) {
                QuickCheck(false, "保存失败：${e.message ?: e.javaClass.simpleName}")
            }
            _state.value = if (result.ok) QuickSaveState.Done(result.message) else QuickSaveState.Failed(result.message)
            onFinished(result)
        }
        return true
    }

    /** 用户开始做别的（换服务商、测试连接）：清掉上一次的结果；保存中不动。 */
    fun acknowledge() {
        _state.value.let { if (it is QuickSaveState.Done || it is QuickSaveState.Failed) _state.compareAndSet(it, QuickSaveState.Idle) }
    }

    companion object {
        const val STARTING = "正在保存…"
    }
}
