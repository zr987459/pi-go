package io.github.zr987459.pigo.core.store

import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.SessionSummary

/**
 * 冷启动回到哪个会话（DESIGN.md §5 L5）。纯函数。
 *
 * - 上一轮被系统中断、且运行标记指向的会话文件还在：交给引擎自己恢复那个会话并提示「上一轮被系统中断」，这里返回 null；
 * - 否则回到最近更新的会话（按 `updatedAt` 降序，同一时刻按路径排序，保证结果稳定）；
 * - 上一轮被中断时还没有会话文件（运行标记为空串）：也返回 null，按普通流程新开会话——引擎会把
 *   「上一轮被系统中断」提示放在这个新会话上；若此时去打开最近的旧会话，提示会错挂到并未中断的会话上；
 * - 一个会话都没有：返回 null，按普通流程新开会话。
 */
object ResumePolicy {
    /**
     * 引擎会不会自己回到被中断的会话：运行标记非空，且标记里的会话文件仍存在（与 PiEngine 的 resumeTarget 一致）。
     * @param runMarker `RunMarkerStore.get()`：null = 上一轮正常结束；空串 = 被中断但当时还没有会话文件。
     */
    fun engineWillResume(runMarker: String?, fileExists: (String) -> Boolean): Boolean =
        !runMarker.isNullOrEmpty() && runCatching { fileExists(runMarker) }.getOrDefault(false)

    /**
     * @param engineWillResume 见 [engineWillResume]。
     * @param sessions 会话列表（顺序不限）。
     */
    fun coldStartTarget(engineWillResume: Boolean, sessions: List<SessionSummary>): SessionSummary? {
        if (engineWillResume) return null
        return sessions.minWithOrNull(compareByDescending<SessionSummary> { it.updatedAt }.thenBy { it.guestPath })
    }

    /**
     * 冷启动要打开的会话（L5），综合运行标记：
     * @param runMarker `RunMarkerStore.get()`：null = 上一轮正常结束；空串 = 被中断但当时还没有会话文件；否则为会话文件。
     * @return null 表示不打开任何会话，直接普通启动（引擎自行恢复被中断会话 / 新开会话）。
     */
    fun coldStartTarget(runMarker: String?, fileExists: (String) -> Boolean, sessions: List<SessionSummary>): SessionSummary? {
        if (runMarker != null && runMarker.isEmpty()) return null
        return coldStartTarget(engineWillResume(runMarker, fileExists), sessions)
    }

    /**
     * 预热时还能不能自动打开旧会话：引擎从未启动（[EngineStatus.Stopped]）、没有当前会话文件、
     * 不在生成中、界面上也没有任何消息。任何一条不满足都说明用户已经开始用了（例如抢先发了消息），
     * 这时再 `openSession` 会把正在进行的对话切走，必须放弃。
     */
    fun mayAutoOpen(currentSessionFile: String?, status: EngineStatus, chat: ChatState): Boolean =
        currentSessionFile == null && status is EngineStatus.Stopped && !chat.running && chat.items.isEmpty()
}
