package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.session.DraftState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/**
 * 切换会话或引擎重启时，排队中的文字回到草稿（DESIGN-r3 §3、§7.3 QueueBackToDraftTest）。
 *
 * pi 在 `switch_session` / `new_session` / 进程重启后会丢掉自己的排队消息；这里记住最近一次非空的
 * `queue_update` 快照，一旦会话文件变了或引擎进入 Starting / Crashed / Stopped，就把这些文字交给 [onRecall]。
 */
object QueueRecall {
    suspend fun watch(chat: StateFlow<ChatState>, status: StateFlow<EngineStatus>, onRecall: (List<String>) -> Unit) {
        var lastQueue = QueueSnapshot.EMPTY
        var lastFile: String? = chat.value.sessionFile
        var lastStatus: EngineStatus = status.value
        combine(chat, status) { c, s -> c to s }.collect { (c, s) ->
            val fileChanged = lastFile != null && c.sessionFile != lastFile
            val engineLost = (s is EngineStatus.Starting || s is EngineStatus.Crashed || s is EngineStatus.Stopped) &&
                (lastStatus is EngineStatus.Running || lastStatus is EngineStatus.Idle)
            if ((fileChanged || engineLost) && !lastQueue.isEmpty) {
                onRecall(lastQueue.steering + lastQueue.followUp)
                lastQueue = QueueSnapshot.EMPTY
            } else if (!fileChanged && !engineLost) {
                lastQueue = c.queue
            }
            lastFile = c.sessionFile ?: lastFile
            lastStatus = s
        }
    }

    /** 草稿为空：文字直接放回输入框（多条按换行拼接）；草稿非空：进「已撤回」芯片，不覆盖。 */
    fun merge(draft: DraftState, texts: List<String>): DraftState {
        val t = texts.filter { it.isNotBlank() }
        if (t.isEmpty()) return draft
        return if (draft.text.isBlank()) draft.withText(t.joinToString("\n")) else draft.copy(recalled = draft.recalled + t)
    }
}
