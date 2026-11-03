package io.github.zr987459.pigo.core.store

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SessionRepository
import io.github.zr987459.pigo.contract.SessionSummary
import io.github.zr987459.pigo.contract.session.PendingDelete
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 宿主直接读 `~/.pi/agent/sessions/` 下的全部会话文件（DESIGN.md §3.8；RPC 没有列会话命令）。
 *
 * - 列表按更新时间（文件 `lastModified`）倒序；以 `(path,size,mtime)` 缓存解析结果。
 * - 删除 = 同一文件系统内 rename 到 `~/.pi/agent/sessions-trash/<原子目录>/<文件名>`，可恢复；
 *   回收站重名时文件名追加 `~N`，恢复时去掉。本类**从不真正删除**会话文件（清空回收站见 [purgeTrash]）。
 * - 所有文件操作在 [io] 上执行，不占主线程。
 */
class FileSessionRepository(
    private val paths: GuestPaths,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : SessionRepository {

    /** 最近一次 [deleteWithUndo] 的记录（DESIGN-r3 §8.1：撤销只由本类实现，`SessionCatalog` 只调用）。 */
    @Volatile private var lastDelete: PendingDelete? = null

    private data class CacheKey(val path: String, val size: Long, val mtime: Long)

    private val cache = ConcurrentHashMap<String, Pair<CacheKey, SessionSummary>>()
    private val sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    private val trash = MutableStateFlow<List<SessionSummary>>(emptyList())
    private val mutex = Mutex()

    @Volatile private var loaded = false

    override fun observe(): Flow<List<SessionSummary>> = sessions.onStart { if (!loaded) refresh() }

    override fun observeTrash(): Flow<List<SessionSummary>> = trash.onStart { if (!loaded) refresh() }

    override suspend fun refresh() {
        withContext(io) {
            mutex.withLock { rescanLocked() }
        }
    }

    override suspend fun moveToTrash(guestPath: String): Outcome<String> = withContext(io) {
        mutex.withLock {
            val src = try {
                paths.hostFile(guestPath)
            } catch (e: IllegalArgumentException) {
                return@withLock Outcome.Err(ErrCode.UNKNOWN, e.message ?: "路径无效")
            }
            if (!src.name.endsWith(".jsonl") || !paths.isInside(src, paths.sessionsDir)) {
                return@withLock Outcome.Err(ErrCode.UNKNOWN, "只能删除会话目录里的会话文件")
            }
            if (!src.isFile) return@withLock Outcome.Err(ErrCode.UNKNOWN, "会话文件不存在，可能已被删除")
            val subdir = src.parentFile!!.name
            val destDir = File(paths.trashDir, subdir)
            val dest = uniqueTarget(destDir, src.name)
            val moved = move(src, dest)
            if (moved != null) return@withLock Outcome.Err(ErrCode.UNKNOWN, "移到回收站失败：$moved")
            rescanLocked()
            Outcome.Ok(paths.guestPath(dest) ?: dest.path)
        }
    }

    override suspend fun restore(trashPath: String): Outcome<String> = withContext(io) {
        mutex.withLock {
            val src = try {
                paths.hostFile(trashPath)
            } catch (e: IllegalArgumentException) {
                return@withLock Outcome.Err(ErrCode.UNKNOWN, e.message ?: "路径无效")
            }
            if (!src.name.endsWith(".jsonl") || !paths.isInside(src, paths.trashDir)) {
                return@withLock Outcome.Err(ErrCode.UNKNOWN, "只能恢复回收站里的会话文件")
            }
            if (!src.isFile) return@withLock Outcome.Err(ErrCode.UNKNOWN, "回收站里没有这个会话")
            val subdir = src.parentFile!!.name
            val destDir = File(paths.sessionsDir, subdir)
            val originalName = stripTrashSuffix(src.name)
            val dest = uniqueTarget(destDir, originalName)
            val moved = move(src, dest)
            if (moved != null) return@withLock Outcome.Err(ErrCode.UNKNOWN, "恢复失败：$moved")
            src.parentFile?.let { if (it.list()?.isEmpty() == true) it.delete() }
            rescanLocked()
            Outcome.Ok(paths.guestPath(dest) ?: dest.path)
        }
    }

    /**
     * 删除并允许撤销（DESIGN-r3 ③）：仍是移进回收目录（D1），返回的 [PendingDelete] 在
     * [PendingDelete.undoUntilMillis]（[UNDO_WINDOW_MILLIS] = 5 秒）之前可用 [undoDelete] / [undoLastDelete] 撤销。
     * 只记最近一次；新的删除会替换旧记录（旧的仍在回收目录，不丢）。
     */
    suspend fun deleteWithUndo(guestPath: String): Outcome<PendingDelete> =
        when (val r = moveToTrash(guestPath)) {
            is Outcome.Ok -> {
                val p = PendingDelete(originalPath = guestPath, trashPath = r.value, undoUntilMillis = clock() + UNDO_WINDOW_MILLIS)
                lastDelete = p
                Outcome.Ok(p)
            }
            is Outcome.Err -> r
        }

    /** 撤销最近一次删除；超过 5 秒或没有可撤销的删除时返回错误。成功后文件回到原路径（原文件名，不加后缀）。 */
    suspend fun undoLastDelete(): Outcome<String> {
        val p = lastDelete ?: return Outcome.Err(ErrCode.UNKNOWN, "没有可撤销的删除")
        return undoDelete(p)
    }

    /** 撤销指定的删除，要求仍在撤销期内、原路径未被占用。 */
    suspend fun undoDelete(pending: PendingDelete): Outcome<String> = withContext(io) {
        mutex.withLock {
            if (clock() > pending.undoUntilMillis) return@withLock Outcome.Err(ErrCode.UNKNOWN, "已超过撤销时间")
            val src = try {
                paths.hostFile(pending.trashPath)
            } catch (e: IllegalArgumentException) {
                return@withLock Outcome.Err(ErrCode.UNKNOWN, e.message ?: "路径无效")
            }
            val dest = try {
                paths.hostFile(pending.originalPath)
            } catch (e: IllegalArgumentException) {
                return@withLock Outcome.Err(ErrCode.UNKNOWN, e.message ?: "路径无效")
            }
            if (!paths.isInside(src, paths.trashDir) || !src.isFile) {
                return@withLock Outcome.Err(ErrCode.UNKNOWN, "回收目录里没有这个会话")
            }
            if (!paths.isInside(dest, paths.sessionsDir)) return@withLock Outcome.Err(ErrCode.UNKNOWN, "原路径不在会话目录")
            val moved = move(src, dest)
            if (moved != null) return@withLock Outcome.Err(ErrCode.UNKNOWN, "撤销失败：$moved")
            src.parentFile?.let { if (it.list()?.isEmpty() == true) it.delete() }
            if (lastDelete == pending) lastDelete = null
            rescanLocked()
            Outcome.Ok(pending.originalPath)
        }
    }

    /**
     * 清空回收站（DESIGN.md §3.8：界面上需二次确认）。不在 `SessionRepository` 接口里，
     * 需要 scaffold 在 `:core:contract` 增加对应方法后，设置/会话页才能通过接口调用。
     * 只删除 `sessions-trash/` 之下的文件，返回删除的文件数。
     */
    suspend fun purgeTrash(): Outcome<Int> = withContext(io) {
        mutex.withLock {
            var n = 0
            val root = paths.trashDir
            if (root.isDirectory) {
                root.walkBottomUp().forEach { f ->
                    if (f == root) return@forEach
                    if (!paths.isInside(f, root)) return@forEach
                    if (f.isFile && f.delete()) n++ else if (f.isDirectory) f.delete()
                }
            }
            rescanLocked()
            Outcome.Ok(n)
        }
    }

    // ---------------- 内部 ----------------

    private fun rescanLocked() {
        sessions.value = scan(paths.sessionsDir)
        trash.value = scan(paths.trashDir)
        loaded = true
    }

    private fun scan(root: File): List<SessionSummary> {
        val subdirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<SessionSummary>()
        for (dir in subdirs) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: continue
            for (f in files) {
                val s = summarize(f) ?: continue
                seen += f.path
                out += s
            }
        }
        // 只清掉本根目录下已经不存在的缓存项。
        val prefix = root.path + File.separator
        cache.keys.removeAll { it.startsWith(prefix) && it !in seen }
        return out.sortedWith(compareByDescending<SessionSummary> { it.updatedAt }.thenBy { it.guestPath })
    }

    private fun summarize(f: File): SessionSummary? {
        val key = CacheKey(f.path, f.length(), f.lastModified())
        cache[f.path]?.let { (k, v) -> if (k == key) return v }
        val guest = paths.guestPath(f) ?: return null
        val s = try {
            val p = SessionFileParser.parse(f)
            SessionSummary(
                guestPath = guest,
                id = p.id,
                cwd = p.cwd,
                name = p.name,
                preview = p.preview,
                createdAt = p.createdAt,
                updatedAt = Instant.ofEpochMilli(key.mtime),
                sizeBytes = key.size,
            )
        } catch (_: IOException) {
            return null // 读不到（正在被替换等）：本次跳过，下次刷新再试
        }
        cache[f.path] = key to s
        return s
    }

    /** 返回 null 表示成功，否则是中文失败原因。只用 rename，不复制后删除，避免中途失败丢数据。 */
    private fun move(src: File, dest: File): String? {
        dest.parentFile?.mkdirs()
        if (dest.exists()) return "目标已存在"
        return try {
            try {
                Files.move(src.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                if (!src.renameTo(dest)) return "无法移动文件"
            }
            null
        } catch (e: IOException) {
            e.message ?: e::class.java.simpleName
        }
    }

    companion object {
        /** 删除后可撤销的时长（DESIGN-r3 §7.3 `UndoDeleteTest`）。 */
        const val UNDO_WINDOW_MILLIS = 5_000L

        private val TRASH_SUFFIX = Regex("~\\d+$")

        internal fun uniqueTarget(dir: File, name: String): File {
            var f = File(dir, name)
            if (!f.exists()) return f
            val stem = name.removeSuffix(".jsonl")
            var i = 1
            while (true) {
                f = File(dir, "$stem~$i.jsonl")
                if (!f.exists()) return f
                i++
            }
        }

        internal fun stripTrashSuffix(name: String): String =
            name.removeSuffix(".jsonl").replace(TRASH_SUFFIX, "") + ".jsonl"
    }
}
