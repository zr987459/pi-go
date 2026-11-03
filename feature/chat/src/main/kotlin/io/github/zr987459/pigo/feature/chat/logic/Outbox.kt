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

    /** 引擎（ChatReducer）把 user 消息里的图片块渲染成的占位文字。 */
    const val IMAGE_PLACEHOLDER = "[图片]"

    /**
     * 对账用的文本：去掉图片占位再 trim。引擎把带图的 user 消息拼成「原文[图片]」、只发图时是「[图片]」，
     * 而本地气泡记的是原文（只发图时为 ""）；两边都先归一化，带图发送才能在引擎回放后立即被吸收，不会显示两次。
     */
    fun matchKey(text: String): String = text.replace(IMAGE_PLACEHOLDER, "").trim()

    fun userCount(chat: ChatState, text: String): Int {
        val key = matchKey(text)
        return chat.items.count { it is ChatItem.User && matchKey(it.text) == key }
    }

    fun inQueue(chat: ChatState, text: String): Boolean {
        val key = matchKey(text)
        return chat.queue.followUp.any { matchKey(it) == key } || chat.queue.steering.any { matchKey(it) == key }
    }

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
