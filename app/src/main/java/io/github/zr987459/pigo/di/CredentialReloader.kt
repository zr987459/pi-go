package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.actions.ActionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 凭据变了（保存 Key、OAuth 完成、退出登录、删除服务商、设置 › 模型「保存并使用」）之后，让运行中的 pi 用上新配置。
 *
 * **首选热加载**（r4/model-config，用户 2026-11-05「存好 Key 不应该重启」）：Key 已写进 pi 自己读的 auth.json，
 * [hotReload]（pigo-bridge 的 `/pigo:models-refresh` → pi 1.5.0 `ModelRegistry.refresh()`）让 pi 重读 models.json / auth.json、
 * 重算可用模型。扩展命令在 pi 里立即执行，生成中也一样：**不重启进程、不打断当前回复、不丢排队消息**（PR #29 审查 I1 由此消失）。
 *
 * **退路：重启。** 只在热加载做不到时用——桥接不可用，或这个服务商的凭据只能经进程环境注入（[canHotReload] 为假，如 Bedrock
 * 的 AK/SK）。`engine.restart()` 会直接结束 pi 进程，所以生成中（`running` 或队列非空）不立刻重启，等本轮结束
 * （`agent_settled`）再重启，并告诉用户（I1 的原修法，作为退路保留）。重启的成败经 [notify] 告诉用户（N1）。
 *
 * 同一服务商的重启请求合并：OAuth 完成时 AuthService 与 RealModelPanels 各报一次，只重启一次（[COALESCE_MS] 内）。
 */
class CredentialReloader(
    private val engine: EngineClient,
    private val scope: CoroutineScope,
    private val hotReload: suspend () -> Outcome<Unit>,
    /** 这个服务商改了凭据能否热加载（凭据只能进进程环境的返回 false）。 */
    private val canHotReload: (provider: String) -> Boolean = { true },
    private val notify: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        /** 已热加载生效（没有重启）。 */
        data object Hot : Result
        /** 已重启，新凭据已生效。 */
        data object Restarted : Result
        /** 正在回复：本轮结束后自动重启。 */
        data object AfterTurn : Result
        data class Failed(val message: String) : Result
    }

    private val mutex = Mutex()
    private var afterTurn: Job? = null
    private var inFlight: Pair<String, CompletableDeferred<Outcome<Unit>>>? = null
    private var lastDone: Pair<String, Long>? = null

    private fun busy(c: ChatState = engine.chat.value) = c.running || !c.queue.isEmpty

    /** 面板路径：能热加载就热加载；否则空闲时立刻重启，生成中排到本轮结束后重启。 */
    suspend fun request(provider: String, reason: String = "$provider 的凭据已更新"): Result {
        if (canHotReload(provider) && hotReload() is Outcome.Ok) return Result.Hot
        if (busy()) {
            scheduleAfterTurn(provider, reason)
            return Result.AfterTurn
        }
        return when (val r = restartCoalesced(provider, reason)) {
            is Outcome.Ok -> Result.Restarted
            is Outcome.Err -> Result.Failed(r.message)
        }
    }

    /**
     * 「保存并使用」（在 App 作用域里调用）：能热加载就热加载；退回重启时，生成中先 `onWaiting()`，挂起等到本轮结束再重启。
     * @return Ok(true) = 热加载；Ok(false) = 重启
     */
    suspend fun apply(provider: String, reason: String, onWaiting: () -> Unit = {}): Outcome<Boolean> {
        if (canHotReload(provider) && hotReload() is Outcome.Ok) return Outcome.Ok(true)
        if (busy()) {
            onWaiting()
            awaitIdle()
        }
        return when (val r = restartCoalesced(null, reason)) {
            is Outcome.Ok -> Outcome.Ok(false)
            is Outcome.Err -> r
        }
    }

    private suspend fun awaitIdle() {
        engine.chat.first { !busy(it) }
    }

    private fun scheduleAfterTurn(provider: String, reason: String) {
        synchronized(this) {
            if (afterTurn?.isActive == true) return
            afterTurn = scope.launch {
                awaitIdle()
                when (val r = restartCoalesced(provider, reason)) {
                    is Outcome.Ok -> notify(DONE_AFTER_TURN)
                    is Outcome.Err -> notify(failedText(r.message))
                }
            }
        }
    }

    /** [provider] 为 null 时不合并（「保存并使用」每次都要真的重启）。 */
    private suspend fun restartCoalesced(provider: String?, reason: String): Outcome<Unit> {
        val mine = CompletableDeferred<Outcome<Unit>>()
        val joined: CompletableDeferred<Outcome<Unit>>? = synchronized(this) {
            val running = inFlight?.takeIf { provider != null && it.first == provider && it.second.isActive }?.second
            when {
                running != null -> running
                provider != null && lastDone?.let { it.first == provider && clock() - it.second < COALESCE_MS } == true ->
                    CompletableDeferred(Outcome.Ok(Unit))
                else -> { if (provider != null) inFlight = provider to mine; null }
            }
        }
        if (joined != null) return joined.await()
        val r = try {
            mutex.withLock { engine.restart(reason) }
        } catch (e: Exception) {
            mine.complete(Outcome.Err(ErrCode.ENGINE_START, e.message ?: "重启失败"))
            throw e
        }
        if (provider != null && r is Outcome.Ok) synchronized(this) { lastDone = provider to clock() }
        mine.complete(r)
        return r
    }

    companion object {
        const val COALESCE_MS = 3_000L
        const val AFTER_TURN = "正在回复中：这一轮结束后自动重启 pi，新凭据随后生效（不会打断当前回复）"
        const val DONE_AFTER_TURN = "pi 已重启，新的模型凭据已生效"
        fun failedText(msg: String) = "pi 没能重启，新凭据暂未生效：$msg"

        /** 面板里用的提示：热加载 / 立刻重启成功不额外提示；排到本轮结束后 / 失败都要说（N1）。 */
        fun toToast(r: Result): ActionResult.Toast? = when (r) {
            Result.Hot, Result.Restarted -> null
            Result.AfterTurn -> ActionResult.Toast(AFTER_TURN)
            is Result.Failed -> ActionResult.Toast(failedText(r.message), failed = true)
        }
    }
}
