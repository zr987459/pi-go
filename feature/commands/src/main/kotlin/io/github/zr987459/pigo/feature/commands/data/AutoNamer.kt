package io.github.zr987459.pigo.feature.commands.data

import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.RunOutcomeKind
import io.github.zr987459.pigo.contract.SessionIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 自动命名（S5）：每轮正常结束（[RunOutcomeKind.COMPLETED]）后，会话还没有名字、用户没锁定名字时，
 * 在后台生成标题并 `set_session_name`。每个会话只试一次；出错、中止、等待回答的回合不触发。
 * 用户手动改过名（[SessionIndex.markNameLocked]）的不覆盖；生成期间用户改了名也不覆盖。
 */
class AutoNamer(
    private val engine: EngineClient,
    private val extras: EngineExtras,
    private val index: SessionIndex,
    private val titles: TitleGenerator,
    private val scope: CoroutineScope,
    private val policy: AutoTitlePolicy = AutoTitlePolicy(),
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            extras.runOutcomes.collect { o ->
                if (o.kind != RunOutcomeKind.COMPLETED) return@collect
                maybeName()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** 返回写入的标题；没有命名时为 null。 */
    suspend fun maybeName(): String? {
        val chat = engine.chat.value
        val file = chat.sessionFile ?: return null
        val locked = index.meta(file).nameLockedByUser
        if (!policy.claim(file, chat.sessionName, locked, chat.items)) return null
        val title = (titles.generate(chat.items, chat.model) as? Outcome.Ok)?.value ?: return null
        val now = engine.chat.value
        // 生成期间切了会话、或用户（或扩展）已经起了名字：放弃。
        if (now.sessionFile != file || !now.sessionName.isNullOrBlank() || index.meta(file).nameLockedByUser) return null
        return if (engine.rename(title) is Outcome.Ok) title else null
    }
}
