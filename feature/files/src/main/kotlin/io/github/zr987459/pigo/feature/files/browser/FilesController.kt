package io.github.zr987459.pigo.feature.files.browser

import io.github.zr987459.pigo.contract.FileNode
import io.github.zr987459.pigo.contract.GitFileChange
import io.github.zr987459.pigo.contract.GitStatus
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.WorkspaceFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream

/** 文件页状态。 */
data class BrowserState(
    val roots: List<FileNode> = emptyList(),
    val dir: String,
    val entries: List<FileNode> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val showHidden: Boolean = false,
    /** 仓库根（容器路径）；不是仓库为 null。 */
    val gitRoot: String? = null,
    val git: GitStatus? = null,
    /** guestPath → 一个字的状态（改 / 增 / 删 / 新 / 冲突），目录聚合子项。 */
    val gitBadges: Map<String, GitBadge> = emptyMap(),
    val message: String? = null,
)

enum class GitBadge(val label: String) { MODIFIED("改"), ADDED("增"), DELETED("删"), UNTRACKED("新"), RENAMED("移"), CONFLICT("冲突") }

/**
 * 文件页的逻辑（与界面分开，便于单测）。所有调用在 [scope] 上异步执行；[WorkspaceFs] 负责切到 IO 线程。
 * @param highlight 本轮被 pi 改动的文件（W5 从轮末汇总传入），列表里高亮
 */
class FilesController(
    private val fs: WorkspaceFs,
    private val scope: CoroutineScope,
    val sessionCwd: String?,
    startDir: String? = null,
) {
    private val _state = MutableStateFlow(BrowserState(dir = startDir ?: sessionCwd ?: HOME))
    val state: StateFlow<BrowserState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        scope.launch {
            val roots = fs.roots(sessionCwd)
            _state.update { it.copy(roots = roots) }
        }
        open(_state.value.dir)
        refreshGit()
    }

    fun open(dir: String) {
        job?.cancel()
        _state.update { it.copy(dir = dir, loading = true, error = null) }
        job = scope.launch {
            when (val r = fs.list(dir, _state.value.showHidden)) {
                is Outcome.Ok -> _state.update { it.copy(entries = r.value, loading = false, error = null) }
                is Outcome.Err -> _state.update { it.copy(entries = emptyList(), loading = false, error = r.message) }
            }
        }
    }

    fun refresh() { open(_state.value.dir); refreshGit() }

    /** 上一级；已经在某个根时返回 false（界面交给返回键关闭页面）。 */
    fun up(): Boolean {
        val s = _state.value
        if (s.roots.any { it.guestPath == s.dir } || s.dir == "/") return false
        val parent = s.dir.substringBeforeLast('/').ifEmpty { "/" }
        if (s.roots.none { parent == it.guestPath || parent.startsWith(it.guestPath + "/") }) return false
        open(parent)
        return true
    }

    fun toggleHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
        open(_state.value.dir)
    }

    fun mkdir(name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || '/' in clean || clean == "." || clean == "..") { toast("文件夹名无效"); return }
        val dir = _state.value.dir
        scope.launch {
            when (val r = fs.mkdirs(child(dir, clean))) {
                is Outcome.Ok -> { toast("已新建 ${r.value.name}"); open(dir) }
                is Outcome.Err -> toast(r.message)
            }
        }
    }

    /** 导入外部文件到当前目录（SAF、分享）。[streams] 每项为（显示名, 打开流）。 */
    fun import(streams: List<Pair<String, () -> InputStream?>>) {
        val dir = _state.value.dir
        scope.launch {
            var ok = 0
            val errs = ArrayList<String>()
            for ((name, open) in streams) {
                val input = runCatching { open() }.getOrNull()
                if (input == null) { errs += "$name：无法读取"; continue }
                when (val r = input.use { fs.importStream(name, it, dir) }) {
                    is Outcome.Ok -> ok++
                    is Outcome.Err -> errs += "$name：${r.message}"
                }
            }
            toast(if (errs.isEmpty()) "已导入 $ok 个文件" else "导入 $ok 个，失败 ${errs.size} 个：" + errs.joinToString("；"))
            open(dir)
            refreshGit()
        }
    }

    fun refreshGit() {
        val cwd = sessionCwd ?: return
        scope.launch {
            val st = (fs.gitStatus(cwd) as? Outcome.Ok)?.value
            if (st == null || !st.isRepo) { _state.update { it.copy(git = st, gitRoot = null, gitBadges = emptyMap()) }; return@launch }
            val root = GitRoots.find(fs, cwd)
            _state.update { it.copy(git = st, gitRoot = root, gitBadges = if (root != null) badges(root, st.files) else emptyMap()) }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun toast(m: String) = _state.update { it.copy(message = m) }

    companion object {
        const val HOME = "/root"

        fun child(dir: String, name: String) = if (dir == "/") "/$name" else "$dir/$name"

        fun badgeOf(c: GitFileChange): GitBadge = when {
            c.untracked -> GitBadge.UNTRACKED
            c.indexStatus == 'U' || c.worktreeStatus == 'U' || (c.indexStatus == 'A' && c.worktreeStatus == 'A') || (c.indexStatus == 'D' && c.worktreeStatus == 'D') -> GitBadge.CONFLICT
            c.indexStatus == 'R' || c.worktreeStatus == 'R' -> GitBadge.RENAMED
            c.indexStatus == 'A' -> GitBadge.ADDED
            c.indexStatus == 'D' || c.worktreeStatus == 'D' -> GitBadge.DELETED
            else -> GitBadge.MODIFIED
        }

        /** 每个改动文件及其所有上级目录（到仓库根为止）都挂标记；目录取「最显眼」的一个。 */
        fun badges(root: String, files: List<GitFileChange>): Map<String, GitBadge> {
            val m = HashMap<String, GitBadge>()
            for (f in files) {
                val b = badgeOf(f)
                var p = child(root, f.path.trimEnd('/'))
                m[p] = b
                while (true) {
                    val parent = p.substringBeforeLast('/').ifEmpty { "/" }
                    if (parent.length < root.length || parent == p) break
                    val cur = m[parent]
                    if (cur == null || b.ordinal > cur.ordinal) m[parent] = if (cur == null) b else maxOf(b, cur)
                    if (parent == root) break
                    p = parent
                }
            }
            return m
        }
    }
}

/** 契约的 [GitStatus] 不带仓库根：从 cwd 往上找 `.git`（目录或 worktree 的文件），适配层（§3.3）。 */
object GitRoots {
    suspend fun find(fs: WorkspaceFs, cwd: String): String? {
        var p = cwd.trimEnd('/').ifEmpty { "/" }
        repeat(64) {
            if (fs.stat(FilesController.child(p, ".git")) is Outcome.Ok) return p
            if (p == "/") return null
            p = p.substringBeforeLast('/').ifEmpty { "/" }
        }
        return null
    }
}
