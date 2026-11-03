package io.github.zr987459.pigo.contract.session

import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 会话目录（pi `/resume` 选择器与 `/import`，DESIGN-r3 §5.1，清单 §2 / §9 选择器选项）。
 * 实现在 `core/session`（r3/session）；删除与恢复只调用 `core/store/FileSessionRepository`（§8.1）。
 * UI 形态 ☰ 会话列表与终端形态 `/resume` 选择器都只读这里。
 */
interface SessionCatalog {
    /** 按 [options] 过滤、排序后的会话（不含回收目录）。 */
    val sessions: StateFlow<List<SessionSummary>>

    /** 选择器选项：排序（Ctrl+S）、只看已命名（Ctrl+N）、显示路径（Ctrl+P）、搜索词。 */
    val options: MutableStateFlow<SessionListOptions>

    suspend fun refresh(): Outcome<Unit>

    /** 切到该会话（`switch_session`）。生成中的排队文字退回草稿由 SessionHost 负责。 */
    suspend fun open(guestPath: String): Outcome<Unit>

    suspend fun rename(guestPath: String, name: String): Outcome<Unit>

    /** `/import`：复制 JSONL（保留会话 ID 与工作目录）再切过去；返回导入后的会话。 */
    suspend fun import(sourcePath: String): Outcome<SessionSummary>

    /** 删除（移入回收目录）；返回的 [PendingDelete] 在 [PendingDelete.undoUntilMillis] 之前可 [undoDelete]（5 秒）。 */
    suspend fun delete(guestPath: String): Outcome<PendingDelete>

    suspend fun undoDelete(pending: PendingDelete): Outcome<Unit>

    /** 新会话；[ephemeral] 为真时是临时会话（`--no-session` 近似，§5.7）。 */
    suspend fun newSession(ephemeral: Boolean = false): Outcome<Unit>
}

enum class SessionSort { RECENT, NAME }

data class SessionListOptions(
    val sort: SessionSort = SessionSort.RECENT,
    val namedOnly: Boolean = false,
    val showPath: Boolean = false,
    val query: String = "",
)

data class PendingDelete(val originalPath: String, val trashPath: String, val undoUntilMillis: Long)
