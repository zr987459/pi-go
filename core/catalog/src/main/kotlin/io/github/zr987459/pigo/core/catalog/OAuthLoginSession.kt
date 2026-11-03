package io.github.zr987459.pigo.core.catalog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 一个跑在容器里的登录脚本进程的最小抽象（便于用假进程做单测）。 */
interface LineProcess {
    /** stdout 的每一行；进程结束时完成。 */
    val lines: Flow<String>
    suspend fun send(line: String)
    suspend fun stop()
}

/**
 * 一次 OAuth 登录：把 `pigo-auth.mjs` 的事件变成界面状态，把用户的输入写回去。
 *
 * 状态机：Starting → (AwaitingBrowser | AwaitingDeviceCode | AwaitingInput)* → Succeeded | Failed。
 * 任何令牌都不会出现在这里——脚本只回报 `done`，凭据由 pi 写进 auth.json。
 */
class OAuthLoginSession(
    val provider: String,
    private val process: LineProcess,
    scope: CoroutineScope,
    private val onFinished: suspend (success: Boolean) -> Unit = {},
) {
    sealed interface State {
        data object Starting : State
        data class AwaitingBrowser(val url: String, val instructions: String?, val pendingPrompt: LoginEvent.Prompt?) : State
        data class AwaitingDeviceCode(val userCode: String, val verificationUri: String, val expiresInSeconds: Long?) : State
        data class AwaitingInput(val prompt: LoginEvent.Prompt) : State
        data class Working(val message: String) : State
        data object Succeeded : State
        data class Failed(val message: String, val cancelled: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Starting)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<LoginEvent>(replay = 64, extraBufferCapacity = 64)
    val events: SharedFlow<LoginEvent> = _events.asSharedFlow()

    /** 最近一次授权地址（用来校验粘贴内容的 state）。 */
    @Volatile var lastAuthUrl: String? = null
        private set

    private val job: Job = scope.launch {
        var finished = false
        try {
            process.lines.collect { line ->
                val e = OAuthProtocol.parse(line) ?: return@collect
                _events.emit(e)
                when (e) {
                    is LoginEvent.AuthUrl -> {
                        lastAuthUrl = e.url
                        _state.value = State.AwaitingBrowser(e.url, e.instructions, null)
                    }
                    is LoginEvent.DeviceCode -> _state.value = State.AwaitingDeviceCode(e.userCode, e.verificationUri, e.expiresInSeconds)
                    is LoginEvent.Prompt -> {
                        val cur = _state.value
                        // 浏览器授权期间的「粘贴授权码」提示：保持浏览器页，同时允许粘贴
                        _state.value = if (cur is State.AwaitingBrowser && e.kind == "manual_code") cur.copy(pendingPrompt = e)
                        else State.AwaitingInput(e)
                    }
                    is LoginEvent.Progress -> if (_state.value !is State.AwaitingDeviceCode) _state.value = State.Working(e.message)
                    is LoginEvent.Info -> Unit
                    is LoginEvent.Done -> { finished = true; _state.value = State.Succeeded; onFinished(true) }
                    is LoginEvent.Failed -> { finished = true; _state.value = State.Failed(e.message, e.cancelled); onFinished(false) }
                }
            }
        } finally {
            if (!finished && _state.value !is State.Succeeded) {
                _state.value = State.Failed("登录进程意外退出", cancelled = false)
            }
        }
    }

    /** 回答脚本的提示（企业域名、粘贴的授权码等）。粘贴授权码时先校验 state。 */
    suspend fun answer(promptId: String, value: String) {
        val cur = _state.value
        val prompt = when (cur) {
            is State.AwaitingInput -> cur.prompt
            is State.AwaitingBrowser -> cur.pendingPrompt
            else -> null
        }
        if (prompt?.kind == "manual_code") {
            lastAuthUrl?.let { Pkce.validate(it, value) }
        }
        process.send(OAuthProtocol.answer(promptId, value))
        if (cur is State.AwaitingInput) _state.value = State.Working("正在验证…")
    }

    /** Custom Tab 拦截到回调地址时调用：等价于把回调地址粘贴给当前的 manual_code 提示。 */
    suspend fun deliverRedirect(url: String): Boolean {
        val p = (state.value as? State.AwaitingBrowser)?.pendingPrompt ?: return false
        answer(p.id, url)
        return true
    }

    suspend fun cancel() {
        runCatching { process.send(OAuthProtocol.cancel()) }
        process.stop()
        job.cancel()
        if (_state.value !is State.Succeeded) _state.value = State.Failed("已取消", cancelled = true)
    }
}
