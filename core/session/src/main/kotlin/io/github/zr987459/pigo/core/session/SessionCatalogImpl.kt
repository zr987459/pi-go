package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SessionRepository
import io.github.zr987459.pigo.contract.SessionSummary
import io.github.zr987459.pigo.contract.session.PendingDelete
import io.github.zr987459.pigo.contract.session.SessionCatalog
import io.github.zr987459.pigo.contract.session.SessionListOptions
import io.github.zr987459.pigo.contract.session.SessionSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

/**
 * 会话目录（DESIGN-r3 §5.1，`/resume` 选择器与 ☰ 会话列表共用）。
 * 删除只调 [SessionRepository.moveToTrash]（回收目录，D1），撤销调 [SessionRepository.restore]（r3/models 的 FileSessionRepository）。
 * 临时会话（`--no-session` 近似，§5.7）：离开时把会话文件移进回收目录，不出现在列表。
 */
class SessionCatalogImpl(
    private val repo: SessionRepository,
    private val engine: EngineClient,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 导入：把外部 JSONL 复制进会话目录并返回容器内路径（保留会话 ID 与工作目录）。 */
    private val importer: suspend (String) -> Outcome<String> = { Outcome.Err(ErrCode.UNKNOWN, "导入尚未接入") },
    private val onEphemeralChanged: (Boolean) -> Unit = {},
) : SessionCatalog {

    override val options = MutableStateFlow(SessionListOptions())

    override val sessions: StateFlow<List<SessionSummary>> =
        combine(repo.observe(), options) { list, o -> filterSort(list, o) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 当前临时会话的文件（离开时移进回收目录）。 */
    private var ephemeralFile: String? = null

    override suspend fun refresh(): Outcome<Unit> = runCatching { repo.refresh() }
        .fold({ Outcome.Ok(Unit) }, { Outcome.Err(ErrCode.UNKNOWN, it.message ?: "刷新失败") })

    override suspend fun open(guestPath: String): Outcome<Unit> {
        val s = sessions.value.firstOrNull { it.guestPath == guestPath }
        leaveEphemeral()
        return engine.openSession(guestPath, s?.cwd ?: "")
    }

    override suspend fun rename(guestPath: String, name: String): Outcome<Unit> =
        if (engine.chat.value.sessionFile == guestPath) engine.rename(name)
        else Outcome.Err(ErrCode.RPC_REJECTED, "只能重命名当前会话：先打开它")

    override suspend fun import(sourcePath: String): Outcome<SessionSummary> {
        val path = when (val r = importer(sourcePath)) { is Outcome.Ok -> r.value; is Outcome.Err -> return r }
        refresh()
        when (val r = open(path)) { is Outcome.Err -> return r; is Outcome.Ok -> Unit }
        val s = repo.observeOnce().firstOrNull { it.guestPath == path } ?: return Outcome.Err(ErrCode.UNKNOWN, "导入后找不到会话：$path")
        return Outcome.Ok(s)
    }

    override suspend fun delete(guestPath: String): Outcome<PendingDelete> {
        if (engine.chat.value.sessionFile == guestPath) {
            when (val r = engine.newSession()) { is Outcome.Err -> return r; is Outcome.Ok -> Unit }
        }
        return when (val r = repo.moveToTrash(guestPath)) {
            is Outcome.Ok -> Outcome.Ok(PendingDelete(guestPath, r.value, clock() + UNDO_MS))
            is Outcome.Err -> r
        }
    }

    override suspend fun undoDelete(pending: PendingDelete): Outcome<Unit> {
        if (clock() > pending.undoUntilMillis) return Outcome.Err(ErrCode.RPC_REJECTED, "已超过 5 秒，无法撤销（可在回收目录找回）")
        return when (val r = repo.restore(pending.trashPath)) {
            is Outcome.Ok -> { refresh(); Outcome.Ok(Unit) }
            is Outcome.Err -> r
        }
    }

    override suspend fun newSession(ephemeral: Boolean): Outcome<Unit> {
        leaveEphemeral()
        val r = engine.newSession()
        if (r is Outcome.Ok && ephemeral) {
            ephemeralFile = engine.chat.value.sessionFile
            onEphemeralChanged(true)
        }
        return r
    }

    /** App 退出时也调用。 */
    suspend fun leaveEphemeral() {
        val f = ephemeralFile ?: return
        ephemeralFile = null
        onEphemeralChanged(false)
        repo.moveToTrash(f)
    }

    private suspend fun SessionRepository.observeOnce(): List<SessionSummary> = observe().first()

    companion object {
        const val UNDO_MS = 5_000L

        fun filterSort(list: List<SessionSummary>, o: SessionListOptions): List<SessionSummary> {
            val q = o.query.trim().lowercase()
            return list.asSequence()
                .filter { !o.namedOnly || !it.name.isNullOrBlank() }
                .filter { q.isEmpty() || listOfNotNull(it.name, it.preview, it.cwd, it.id).any { f -> f.lowercase().contains(q) } }
                .let { s -> if (o.sort == SessionSort.NAME) s.sortedBy { (it.name ?: it.preview).lowercase() } else s.sortedByDescending { it.updatedAt } }
                .toList()
        }
    }
}
