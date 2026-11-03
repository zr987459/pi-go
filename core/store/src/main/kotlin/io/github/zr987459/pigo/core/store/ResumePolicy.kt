package io.github.zr987459.pigo.core.store

import io.github.zr987459.pigo.contract.SessionSummary

/**
 * 冷启动回到哪个会话（DESIGN.md §5 L5）。纯函数。
 *
 * - 上一轮被系统中断、且运行标记指向的会话文件还在：交给引擎自己恢复那个会话并提示「上一轮被系统中断」，这里返回 null；
 * - 否则回到最近更新的会话（按 `updatedAt` 降序，同一时刻按路径排序，保证结果稳定）；
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
}
