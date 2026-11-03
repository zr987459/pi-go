package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SendMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 冷启动预热（L5：回到上次会话）完成之前，挡住所有可能让引擎「按需启动」的用户操作（审查第 2 轮 Important）。
 *
 * 背景：预热要先读运行标记、扫会话目录，再 `openSession`。这期间输入框已可用，若用户抢先发消息，引擎会经
 * `ensureStarted` 新开会话；随后预热再 `openSession` 就会走 `switch_session`，在生成中途切到旧会话，刚发的消息和输出
 * 从界面消失。引擎的 `lifecycle` 锁在 `:core:engine` 内部（不在本工作流范围），所以在 app 层串行化：
 * 预热期间用户操作先等闸门打开，预热结束（成功、失败或放弃）一定打开闸门。
 *
 * @param bypass 返回 true 时不等（例如运行环境还没装好：预热根本不会开始，命令应直接失败而不是一直挂起）。
 */
class WarmUpGate(private val bypass: () -> Boolean = { false }) {
    private val done = CompletableDeferred<Unit>()
    private val _open = MutableStateFlow(false)

    /** 闸门是否已打开（预热已结束）。 */
    val open: StateFlow<Boolean> = _open.asStateFlow()

    /** 预热结束时调用；可重复调用。 */
    fun release() {
        _open.value = true
        done.complete(Unit)
    }

    /** 预热结束前挂起（[bypass] 为真时立即返回）。 */
    suspend fun await() {
        if (done.isCompleted || bypass()) return
        done.await()
    }
}

/**
 * 给真实引擎套上 [WarmUpGate]：会启动引擎或改变当前会话的命令先等预热结束。
 * 预热期间若引擎还没动（[EngineStatus.Stopped]），对界面报告 [EngineStatus.Starting]（「pi 正在启动…」），
 * 不让人以为什么都没发生。只读或只作用于已运行进程的调用（`stats`、`stop`、`answerUi`）直接放行。
 *
 * @param runtimeReady 运行环境是否就绪；未就绪时闸门放行、状态也不改写（预热要等运行环境就绪才开始）。
 */
class GatedEngineClient(
    val inner: EngineClient,
    private val gate: WarmUpGate,
    runtimeReady: StateFlow<Boolean>,
    scope: CoroutineScope,
) : EngineClient by inner {

    override val status: StateFlow<EngineStatus> =
        combine(inner.status, gate.open, runtimeReady, ::presented)
            .stateIn(scope, SharingStarted.Eagerly, presented(inner.status.value, gate.open.value, runtimeReady.value))

    override suspend fun send(text: String, mode: SendMode): Outcome<Disposition> {
        gate.await(); return inner.send(text, mode)
    }

    override suspend fun newSession(cwd: String?): Outcome<Unit> {
        gate.await(); return inner.newSession(cwd)
    }

    override suspend fun openSession(guestPath: String, cwd: String): Outcome<Unit> {
        gate.await(); return inner.openSession(guestPath, cwd)
    }

    override suspend fun rename(name: String): Outcome<Unit> {
        gate.await(); return inner.rename(name)
    }

    override suspend fun models(): Outcome<List<ModelInfo>> {
        gate.await(); return inner.models()
    }

    override suspend fun setModel(provider: String, modelId: String): Outcome<ModelInfo> {
        gate.await(); return inner.setModel(provider, modelId)
    }

    override suspend fun thinkingLevels(): Outcome<List<String>> {
        gate.await(); return inner.thinkingLevels()
    }

    override suspend fun setThinking(level: String): Outcome<Unit> {
        gate.await(); return inner.setThinking(level)
    }

    override suspend fun restart(reason: String): Outcome<Unit> {
        gate.await(); return inner.restart(reason)
    }

    companion object {
        /** 预热未结束、运行环境已就绪、引擎还没动：显示为启动中。其余情况原样透传。 */
        fun presented(st: EngineStatus, gateOpen: Boolean, runtimeReady: Boolean): EngineStatus =
            if (!gateOpen && runtimeReady && st is EngineStatus.Stopped) EngineStatus.Starting else st
    }
}
