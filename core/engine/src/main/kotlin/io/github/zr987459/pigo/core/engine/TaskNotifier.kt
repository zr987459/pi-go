package io.github.zr987459.pigo.core.engine

import io.github.zr987459.pigo.contract.RunOutcome
import io.github.zr987459.pigo.contract.RunOutcomeKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** App 是否有界面在前台（Activity 处于 started）。 */
fun interface AppVisibility {
    fun isForeground(): Boolean
}

/** 一条任务通知的内容（与平台无关，便于 JVM 单测）。 */
data class TaskNotice(
    /** 同一会话复用同一个通知 id（新结果覆盖旧的）。 */
    val id: Int,
    val title: String,
    val text: String,
    val outcome: RunOutcome,
)

/** 平台通知端口：Android 实现见 [AndroidTaskNotifications]。 */
interface TaskNotificationPort {
    fun post(notice: TaskNotice)
}

/**
 * N1 任务通知：订阅 `EngineExtras.runOutcomes`，App 在后台时发通知（通道 `pigo-tasks`）。
 * - COMPLETED → 「pi 已完成」+ 最后一条回复开头；ERROR → 「pi 出错了」+ 原因；NEEDS_INPUT → 「pi 需要你回答」+ 对话框标题；
 * - ABORTED（用户点了停止）不发；App 在前台时不发（界面上已经能看到）。
 * 文字已由引擎脱敏（RunResults）。
 */
class TaskNotifier(
    private val visibility: AppVisibility,
    private val port: TaskNotificationPort,
) {
    fun start(outcomes: Flow<RunOutcome>, scope: CoroutineScope): Job = scope.launch {
        outcomes.collect { onOutcome(it) }
    }

    /** 返回实际发出的通知；不发时为 null。 */
    fun onOutcome(o: RunOutcome): TaskNotice? {
        if (o.kind == RunOutcomeKind.ABORTED) return null
        if (visibility.isForeground()) return null
        val n = toNotice(o) ?: return null
        port.post(n)
        return n
    }

    companion object {
        const val CHANNEL_ID = "pigo-tasks"
        const val CHANNEL_NAME = "任务完成提醒"
        private const val BASE_ID = 2000

        fun toNotice(o: RunOutcome): TaskNotice? {
            val who = o.sessionName?.takeIf { it.isNotBlank() }
            val (title, text) = when (o.kind) {
                RunOutcomeKind.COMPLETED -> "pi 已完成" to (o.lastTextPreview ?: "本轮已完成")
                RunOutcomeKind.ERROR -> "pi 出错了" to (o.message ?: "本轮以错误结束")
                RunOutcomeKind.NEEDS_INPUT -> "pi 需要你回答" to (o.message ?: "有一个问题等你确认")
                RunOutcomeKind.ABORTED -> return null
            }
            val id = BASE_ID + ((o.sessionFile ?: "").hashCode() and 0x3fff)
            return TaskNotice(id, if (who != null) "$title · $who" else title, text, o)
        }
    }
}
