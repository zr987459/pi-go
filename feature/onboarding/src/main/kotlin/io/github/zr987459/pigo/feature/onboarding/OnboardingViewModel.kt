package io.github.zr987459.pigo.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zr987459.pigo.contract.InstallProgress
import io.github.zr987459.pigo.contract.Phase
import io.github.zr987459.pigo.contract.RuntimeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 安装向导状态。安装协程挂在 ViewModel 上：离开向导页（导航返回栈保存状态）或切后台都不会中止，
 * 只有用户在确认框里选择「中止」才取消；中止后已完成的步骤保留，可继续安装（§5.7 N4）。
 */
class OnboardingViewModel(private val runtime: RuntimeManager) : ViewModel() {

    enum class Mode { IDLE, RUNNING, FAILED, ABORTED, DONE }

    data class UiState(
        val mode: Mode = Mode.IDLE,
        val progress: InstallProgress? = null,
        val error: String? = null,
        val log: List<String> = emptyList(),
        val confirmingAbort: Boolean = false,
        val showLog: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    val isRunning get() = job?.isActive == true

    fun start() {
        if (isRunning) return
        _state.update { it.copy(mode = Mode.RUNNING, error = null, confirmingAbort = false) }
        job = viewModelScope.launch {
            try {
                runtime.install().collect { p ->
                    _state.update { s ->
                        val log = if (s.log.lastOrNull() == p.message) s.log else (s.log + p.message).takeLast(MAX_LOG)
                        s.copy(progress = p, log = log)
                    }
                }
                _state.update { it.copy(mode = if (runtime.status.value.phase == Phase.READY) Mode.DONE else Mode.FAILED, confirmingAbort = false) }
            } catch (e: CancellationException) {
                _state.update { it.copy(mode = Mode.ABORTED, confirmingAbort = false, log = (it.log + "已中止，已完成的步骤会保留").takeLast(MAX_LOG)) }
                throw e
            } catch (e: Exception) {
                val msg = e.message?.takeIf { it.isNotBlank() } ?: "安装失败，请稍后重试"
                _state.update { it.copy(mode = Mode.FAILED, error = msg, confirmingAbort = false, log = (it.log + msg).takeLast(MAX_LOG)) }
            }
        }
    }

    /** 返回键：安装中弹确认；否则交给系统处理（返回 false）。 */
    fun onBack(): Boolean {
        if (!isRunning) return false
        _state.update { it.copy(confirmingAbort = true) }
        return true
    }

    fun dismissAbort() = _state.update { it.copy(confirmingAbort = false) }

    fun confirmAbort() {
        _state.update { it.copy(confirmingAbort = false) }
        job?.cancel()
    }

    fun toggleLog() = _state.update { it.copy(showLog = !it.showLog) }

    companion object {
        private const val MAX_LOG = 200
    }
}
