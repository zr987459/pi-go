package io.github.zr987459.pigo.contract.fake

import io.github.zr987459.pigo.contract.DraftStore
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SessionGroup
import io.github.zr987459.pigo.contract.SessionHit
import io.github.zr987459.pigo.contract.SessionIndex
import io.github.zr987459.pigo.contract.SessionMeta
import io.github.zr987459.pigo.contract.SessionSummary
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

/**
 * 假会话索引：会话列表 + 每个会话的用户消息全文（[texts]）。元数据存内存。
 */
class FakeSessionIndex(
    sessions: List<SessionSummary> = FIXTURE_SESSIONS,
    private val texts: Map<String, List<String>> = FIXTURE_TEXTS,
) : SessionIndex {
    private val list = MutableStateFlow(sessions)
    private val metas = MutableStateFlow<Map<String, SessionMeta>>(emptyMap())
    var rescans = 0
        private set

    private fun metaOf(m: Map<String, SessionMeta>, path: String) = m[path] ?: SessionMeta(path)

    override fun observeGroups(includeArchived: Boolean): Flow<List<SessionGroup>> =
        combine(list, metas) { ss, ms ->
            ss.map { SessionHit(it, metaOf(ms, it.guestPath)) }
                .filter { includeArchived || !it.meta.archived }
                .groupBy { it.summary.cwd }
                .map { (cwd, hits) ->
                    SessionGroup(cwd, hits.sortedWith(compareByDescending<SessionHit> { it.meta.pinned }.thenByDescending { it.summary.updatedAt }))
                }
                .sortedByDescending { g -> g.sessions.maxOf { it.summary.updatedAt } }
        }

    override suspend fun search(query: String, limit: Int): List<SessionHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<SessionHit>()
        for (s in list.value) {
            val meta = metaOf(metas.value, s.guestPath)
            if (s.name?.contains(q, ignoreCase = true) == true) {
                out += SessionHit(s, meta, s.name, -1)
                continue
            }
            val msgs = texts[s.guestPath].orEmpty()
            val i = msgs.indexOfFirst { it.contains(q, ignoreCase = true) }
            if (i >= 0) out += SessionHit(s, meta, snippet(msgs[i], q), i)
        }
        return out.take(limit)
    }

    private fun snippet(text: String, q: String): String {
        val i = text.indexOf(q, ignoreCase = true).coerceAtLeast(0)
        val start = (i - 20).coerceAtLeast(0)
        val end = (i + q.length + 20).coerceAtMost(text.length)
        return (if (start > 0) "…" else "") + text.substring(start, end) + (if (end < text.length) "…" else "")
    }

    override suspend fun rescan(): Outcome<Unit> { rescans++; return Outcome.Ok(Unit) }

    override suspend fun meta(guestPath: String): SessionMeta = metaOf(metas.value, guestPath)

    private fun edit(path: String, f: (SessionMeta) -> SessionMeta) = metas.update { it + (path to f(metaOf(it, path))) }

    override suspend fun setPinned(guestPath: String, pinned: Boolean) = edit(guestPath) { it.copy(pinned = pinned) }
    override suspend fun setArchived(guestPath: String, archived: Boolean) = edit(guestPath) { it.copy(archived = archived) }
    override suspend fun setUnread(guestPath: String, unread: Boolean) = edit(guestPath) { it.copy(unread = unread) }
    override suspend fun markNameLocked(guestPath: String) = edit(guestPath) { it.copy(nameLockedByUser = true) }

    override suspend fun suggestTitle(guestPath: String): Outcome<String> {
        val first = texts[guestPath]?.firstOrNull() ?: return Outcome.Err(ErrCode.UNKNOWN, "会话没有用户消息")
        return Outcome.Ok(first.take(16))
    }

    companion object {
        private fun s(id: String, cwd: String, name: String?, preview: String, minute: Int) = SessionSummary(
            guestPath = "/root/.pi/agent/sessions/--root-${cwd.substringAfterLast('/')}--/2026-11-03T06-${"%02d".format(minute)}-00-000Z_$id.jsonl",
            id = id, cwd = cwd, name = name, preview = preview,
            createdAt = Instant.parse("2026-11-03T06:00:00Z"),
            updatedAt = Instant.parse("2026-11-03T06:00:00Z").plusSeconds(minute * 60L),
            sizeBytes = 1024L * minute,
        )

        val FIXTURE_SESSIONS = listOf(
            s("a1", "/root/pi-cwd", "修登录 bug", "登录页点了没反应", 10),
            s("a2", "/root/pi-cwd", null, "写一个 README", 20),
            s("b1", "/root/blog", "博客主题", "把博客换成深色主题", 5),
        )

        val FIXTURE_TEXTS: Map<String, List<String>> = mapOf(
            FIXTURE_SESSIONS[0].guestPath to listOf("登录页点了没反应", "看下控制台报错", "改完跑一遍测试"),
            FIXTURE_SESSIONS[1].guestPath to listOf("写一个 README", "加上安装步骤"),
            FIXTURE_SESSIONS[2].guestPath to listOf("把博客换成深色主题"),
        )
    }
}

class FakeDraftStore : DraftStore {
    private val map = mutableMapOf<String, String>()
    override suspend fun get(key: String): String? = map[key]
    override suspend fun put(key: String, draft: String) { if (draft.isEmpty()) map.remove(key) else map[key] = draft }
    override suspend fun remove(key: String) { map.remove(key) }
}
