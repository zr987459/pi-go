package io.github.zr987459.pigo.feature.commands.data

import io.github.zr987459.pigo.contract.SessionMeta
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 宿主侧给会话加的标记（S4）：置顶、归档、未读、名字已被用户锁定。不改 pi 的会话文件。 */
class SessionMetaStore(file: File) {
    private val store = JsonFileStore(file)
    private val marks = HashMap<String, SessionMeta>().apply {
        for ((k, v) in store.read()) {
            val o = v as? JsonObject ?: continue
            fun b(n: String) = (o[n] as? JsonPrimitive)?.booleanOrNull ?: false
            put(k, SessionMeta(k, pinned = b("p"), archived = b("a"), unread = b("u"), nameLockedByUser = b("l")))
        }
    }

    @Synchronized
    fun get(path: String): SessionMeta = marks[path] ?: SessionMeta(path)

    @Synchronized
    fun all(): Map<String, SessionMeta> = HashMap(marks)

    /** 置顶与归档互斥：置顶会取消归档，归档会取消置顶。 */
    fun setPinned(path: String, v: Boolean) = edit(path) { it.copy(pinned = v, archived = if (v) false else it.archived) }
    fun setArchived(path: String, v: Boolean) = edit(path) { it.copy(archived = v, pinned = if (v) false else it.pinned) }
    fun setUnread(path: String, v: Boolean) = edit(path) { it.copy(unread = v) }
    fun lockName(path: String) = edit(path) { it.copy(nameLockedByUser = true) }

    /** 会话文件被移走（回收站）或改了路径：迁移或丢掉标记。 */
    fun move(from: String, to: String?) {
        synchronized(this) {
            val m = marks.remove(from)
            if (to != null && m != null) marks[to] = m.copy(guestPath = to)
        }
        flush()
    }

    /** 只保留 [alive] 里的路径（会话文件已不存在的标记清掉）。 */
    fun retain(alive: Set<String>) {
        val changed = synchronized(this) { marks.keys.retainAll(alive) }
        if (changed) flush()
    }

    private fun edit(path: String, f: (SessionMeta) -> SessionMeta) {
        synchronized(this) {
            val next = f(marks[path] ?: SessionMeta(path))
            if (next == SessionMeta(path)) marks.remove(path) else marks[path] = next
        }
        flush()
    }

    @Synchronized
    private fun flush() {
        store.write(JsonObject(marks.mapValues { (_, m) ->
            buildJsonObject {
                if (m.pinned) put("p", true)
                if (m.archived) put("a", true)
                if (m.unread) put("u", true)
                if (m.nameLockedByUser) put("l", true)
            }
        }))
    }
}
