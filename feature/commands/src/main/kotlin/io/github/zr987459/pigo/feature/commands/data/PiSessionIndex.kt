package io.github.zr987459.pigo.feature.commands.data

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SessionGroup
import io.github.zr987459.pigo.contract.SessionHit
import io.github.zr987459.pigo.contract.SessionIndex
import io.github.zr987459.pigo.contract.SessionMeta
import io.github.zr987459.pigo.contract.SessionSummary
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [SessionIndex] 的宿主实现（S3、S4、S5）：直接读 rootfs 里的 `~/.pi/agent/sessions/`，不经 pi。
 *
 * - 扫描用 [SessionScanner]（增量续读）；索引缓存写到 [cacheFile]，冷启动先载入再增量扫。
 * - 置顶、归档、未读、名字锁定存 [SessionMetaStore]（宿主文件）。
 * - [suggestTitle] 读会话文件当前分支的文字，交给 [TitleGenerator]（容器里 `pi --print`）。
 */
class PiSessionIndex(
    /** 宿主上的 `~/.pi/agent/sessions` 目录（`runtime.hostFile("/root/.pi/agent/sessions")`）。 */
    private val sessionsHostDir: File,
    private val sessionsGuestDir: String = "/root/.pi/agent/sessions",
    private val metaStore: SessionMetaStore,
    private val cacheFile: File?,
    private val titles: TitleGenerator?,
    /** 生成标题用的模型（一般是当前会话的模型）；null 时用 pi 默认模型。 */
    private val titleModel: () -> ModelInfo? = { null },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SessionIndex {
    private val scanner = SessionScanner()
    private val mutex = Mutex()
    private val sessions = MutableStateFlow<List<IndexedSession>>(emptyList())
    private val metas = MutableStateFlow<Map<String, SessionMeta>>(metaStore.all())
    @Volatile private var loaded = false

    override fun observeGroups(includeArchived: Boolean): Flow<List<SessionGroup>> =
        combine(sessions, metas) { list, ms -> group(list, ms, includeArchived) }
            .onStart { if (!loaded) rescan() }

    override suspend fun search(query: String, limit: Int): List<SessionHit> = withContext(io) {
        if (!loaded) rescan()
        val ms = metas.value
        val byPath = sessions.value.associateBy { it.path }
        scanner.search(query, limit).mapNotNull { h ->
            val s = byPath[h.path] ?: return@mapNotNull null
            val guest = guestOf(s.path) ?: return@mapNotNull null
            SessionHit(summary(s, guest), ms[guest] ?: SessionMeta(guest), h.snippet, h.userMessageIndex ?: -1)
        }
    }

    override suspend fun rescan(): Outcome<Unit> = withContext(io) {
        mutex.withLock {
            try {
                if (!loaded && cacheFile != null) scanner.load(cacheFile)
                val files = sessionsHostDir.walkTopDown().maxDepth(3)
                    .filter { it.isFile && it.name.endsWith(".jsonl") }.toList()
                val list = scanner.scanAll(files)
                sessions.value = list
                metaStore.retain(list.mapNotNull { guestOf(it.path) }.toSet())
                metas.value = metaStore.all()
                cacheFile?.let { runCatching { scanner.save(it) } }
                loaded = true
                Outcome.Ok(Unit)
            } catch (e: Exception) {
                Outcome.Err(ErrCode.UNKNOWN, "读取会话目录失败：${e.message}", e)
            }
        }
    }

    override suspend fun meta(guestPath: String): SessionMeta = metaStore.get(guestPath)

    override suspend fun setPinned(guestPath: String, pinned: Boolean) = edit { metaStore.setPinned(guestPath, pinned) }
    override suspend fun setArchived(guestPath: String, archived: Boolean) = edit { metaStore.setArchived(guestPath, archived) }
    override suspend fun setUnread(guestPath: String, unread: Boolean) = edit { metaStore.setUnread(guestPath, unread) }
    override suspend fun markNameLocked(guestPath: String) = edit { metaStore.lockName(guestPath) }

    override suspend fun suggestTitle(guestPath: String): Outcome<String> {
        val gen = titles ?: return Outcome.Err(ErrCode.UNKNOWN, "自动命名不可用")
        val host = hostOf(guestPath) ?: return Outcome.Err(ErrCode.UNKNOWN, "不是会话目录里的文件")
        val items = withContext(io) { SessionTranscriptReader.read(host) }
        if (items.isEmpty()) return Outcome.Err(ErrCode.UNKNOWN, "会话没有用户消息")
        return gen.generate(items, titleModel())
    }

    private suspend fun edit(f: () -> Unit) = withContext(io) {
        f()
        metas.value = metaStore.all()
    }

    // ---------------- 映射 ----------------

    internal fun guestOf(hostPath: String): String? {
        val root = sessionsHostDir.canonicalFile.path
        val p = File(hostPath).canonicalFile.path
        if (p != root && !p.startsWith("$root/")) return null
        return sessionsGuestDir.trimEnd('/') + p.removePrefix(root)
    }

    internal fun hostOf(guestPath: String): File? {
        val g = sessionsGuestDir.trimEnd('/')
        if (!guestPath.startsWith("$g/") || guestPath.contains("/../") || !guestPath.endsWith(".jsonl")) return null
        return File(sessionsHostDir, guestPath.removePrefix("$g/"))
    }

    private fun summary(s: IndexedSession, guest: String) = SessionSummary(
        guestPath = guest,
        id = s.id ?: File(guest).nameWithoutExtension.substringAfterLast('_'),
        cwd = s.cwd.orEmpty(),
        name = s.name,
        preview = s.firstMessage,
        createdAt = s.createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.ofEpochMilli(s.lastModified),
        updatedAt = Instant.ofEpochMilli(s.lastModified),
        sizeBytes = s.sizeBytes,
    )

    /** 按 cwd 分组，组内置顶在前、再按更新时间倒序；组按组内最新的更新时间排序；归档默认不含。 */
    private fun group(list: List<IndexedSession>, ms: Map<String, SessionMeta>, includeArchived: Boolean): List<SessionGroup> =
        list.mapNotNull { s -> guestOf(s.path)?.let { g -> SessionHit(summary(s, g), ms[g] ?: SessionMeta(g)) } }
            .filter { includeArchived || !it.meta.archived }
            .groupBy { it.summary.cwd }
            .map { (cwd, hits) ->
                SessionGroup(cwd, hits.sortedWith(compareByDescending<SessionHit> { it.meta.pinned }.thenByDescending { it.summary.updatedAt }))
            }
            .sortedWith(
                compareByDescending<SessionGroup> { g -> g.sessions.any { it.meta.pinned } }
                    .thenByDescending { g -> g.sessions.maxOf { it.summary.updatedAt } },
            )
}
