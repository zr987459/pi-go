package io.github.zr987459.pigo.feature.chat.logic

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.Delivery

/**
 * 本地乐观气泡与引擎状态的对账（DESIGN.md §3.7）。纯函数，可在 JVM 上单测。
 *
 * 队列以 `queue_update` 为唯一真相，客户端不自建队列：本地气泡只负责「发出到收到响应」这段空档，
 * 以及失败后的红色「重试」。被引擎接管后（出现在对话或队列里）就撤掉，避免同一条显示两次。
 * 失败的气泡**不会自动重发**，只有用户点「重试」才会再发。
 */
object Outbox {

    fun userCount(chat: ChatState, text: String): Int =
        chat.items.count { it is ChatItem.User && it.text.trim() == text.trim() }

    fun inQueue(chat: ChatState, text: String): Boolean =
        chat.queue.followUp.any { it.trim() == text.trim() } || chat.queue.steering.any { it.trim() == text.trim() }

    /** 引擎是否已经接管了这条本地气泡（接管后不再显示，并在下一次对账时移除）。 */
    fun isAbsorbed(l: LocalSend, chat: ChatState): Boolean = when (l.status) {
        Delivery.FAILED -> false
        Delivery.SENDING -> userCount(chat, l.text) > l.baselineUserCount
        Delivery.SENT -> userCount(chat, l.text) > l.baselineUserCount || (l.sawRunning && !chat.running)
        Delivery.QUEUED -> inQueue(chat, l.text) || userCount(chat, l.text) > l.baselineUserCount ||
            (l.sawRunning && !chat.running && chat.queue.isEmpty)
    }

    /** 每次引擎状态变化时调用：记录是否见过 running，移除已被接管的气泡。 */
    fun reconcile(sends: List<LocalSend>, chat: ChatState): List<LocalSend> {
        if (sends.isEmpty()) return sends
        val updated = sends.map { l ->
            when {
                l.status == Delivery.FAILED -> l
                chat.running && !l.sawRunning && l.status != Delivery.SENDING -> l.copy(sawRunning = true)
                else -> l
            }
        }.filterNot { isAbsorbed(it, chat) }
        return if (updated == sends) sends else updated
    }

    /** 当前应显示的本地气泡。 */
    fun visible(sends: List<LocalSend>, chat: ChatState): List<LocalSend> = sends.filterNot { isAbsorbed(it, chat) }
}
