package io.github.zr987459.pigo.contract.fake

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SessionSummary
import io.github.zr987459.pigo.contract.session.PendingDelete
import io.github.zr987459.pigo.contract.session.SessionCatalog
import io.github.zr987459.pigo.contract.session.SessionListOptions
import io.github.zr987459.pigo.contract.session.SessionSort
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 内存版 [SessionCatalog]：打开 / 新建会话转给 [FakeScriptedEngine]（记 `switch_session` / `new_session`），
 * 其余只改内存列表；删除进回收区，[undoWindowMillis] 内可撤销（时间由 [clock] 给，测试可控）。
 */
class FakeSessionCatalog(
    private val engine: FakeScriptedEngine,
    initial: List<SessionSummary> = FIXTURE_SESSIONS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val undoWindowMillis: Long = 5_000,
) : SessionCatalog {
    private val all = MutableStateFlow(initial)
    private val trash = mutableMapOf<String, SessionSummary>()

    override val options: MutableStateFlow<SessionListOptions> = MutableStateFlow(SessionListOptions())

    private val _sessions = MutableStateFlow(view())
    override val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private fun view(): List<SessionSummary> {
        val o = options.value
        return all.value
            .filter { !o.namedOnly || !it.name.isNullOrBlank() }
            .filter { o.query.isBlank() || (it.name ?: it.preview).contains(o.query, ignoreCase = true) || it.guestPath.contains(o.query, ignoreCase = true) }
            .let { l -> if (o.sort == SessionSort.NAME) l.sortedBy { (it.name ?: it.preview).lowercase() } else l.sortedByDescending { it.updatedAt } }
    }

    /** 选项变化后调用（Fake 不自动订阅 [options]，避免需要 scope）；[refresh] 也会重算。 */
    fun recompute() { _sessions.value = view() }

    override suspend fun refresh(): Outcome<Unit> { recompute(); return Outcome.Ok(Unit) }

    override suspend fun open(guestPath: String): Outcome<Unit> {
        val s = all.value.firstOrNull { it.guestPath == guestPath } ?: return Outcome.Err(ErrCode.RPC_REJECTED, "没有这个会话")
        return engine.openSession(s.guestPath, s.cwd)
    }

    override suspend fun rename(guestPath: String, name: String): Outcome<Unit> {
        if (all.value.none { it.guestPath == guestPath }) return Outcome.Err(ErrCode.RPC_REJECTED, "没有这个会话")
        all.value = all.value.map { if (it.guestPath == guestPath) it.copy(name = name) else it }
        recompute()
        return Outcome.Ok(Unit)
    }

    override suspend fun import(sourcePath: String): Outcome<SessionSummary> {
        val now = Instant.ofEpochMilli(clock())
        val s = SessionSummary("/fake/imported-${all.value.size + 1}.jsonl", "imported-${all.value.size + 1}", "/root", null, "导入：$sourcePath", now, now, 0)
        all.value = all.value + s
        recompute()
        engine.openSession(s.guestPath, s.cwd)
        return Outcome.Ok(s)
    }

    override suspend fun delete(guestPath: String): Outcome<PendingDelete> {
        val s = all.value.firstOrNull { it.guestPath == guestPath } ?: return Outcome.Err(ErrCode.RPC_REJECTED, "没有这个会话")
        val trashPath = "/fake/trash/${s.id}.jsonl"
        trash[trashPath] = s
        all.value = all.value - s
        recompute()
        return Outcome.Ok(PendingDelete(guestPath, trashPath, clock() + undoWindowMillis))
    }

    override suspend fun undoDelete(pending: PendingDelete): Outcome<Unit> {
        if (clock() > pending.undoUntilMillis) return Outcome.Err(ErrCode.RPC_REJECTED, "已超过撤销时间")
        val s = trash.remove(pending.trashPath) ?: return Outcome.Err(ErrCode.RPC_REJECTED, "回收区里没有")
        all.value = all.value + s
        recompute()
        return Outcome.Ok(Unit)
    }

    val ephemeralRequests = mutableListOf<Boolean>()

    override suspend fun newSession(ephemeral: Boolean): Outcome<Unit> {
        ephemeralRequests += ephemeral
        return engine.newSession(null)
    }

    companion object {
        val FIXTURE_SESSIONS: List<SessionSummary> = listOf(
            SessionSummary("/fake/session-1.jsonl", "s1", "/root", "重构登录", "把登录拆成…", Instant.parse("2026-11-01T10:00:00Z"), Instant.parse("2026-11-03T10:00:00Z"), 2048),
            SessionSummary("/fake/session-2.jsonl", "s2", "/root/app", null, "为什么构建失败", Instant.parse("2026-11-02T10:00:00Z"), Instant.parse("2026-11-02T12:00:00Z"), 1024),
            SessionSummary("/fake/session-3.jsonl", "s3", "/root", "Agenda", "写周报", Instant.parse("2026-10-30T10:00:00Z"), Instant.parse("2026-10-31T10:00:00Z"), 512),
        )
    }
}
