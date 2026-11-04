package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.workspace.FileMatch
import io.github.zr987459.pigo.contract.workspace.WorkspaceSearch
import io.github.zr987459.pigo.core.engine.PiEngine
import io.github.zr987459.pigo.core.workspace.RootfsWorkspace

/**
 * `@文件` 候选（DESIGN-r3 §1.2、清单 §10）：core/workspace 的 [WorkspaceSearch]（RootfsWorkspace → FileSearch，
 * 行为照 pi-tui getFuzzyFileSuggestions），搜索根是当前会话的 cwd（与 pi 进程同一个 cwd）。两种形态共用。
 */
class AppFileSearch(private val search: WorkspaceSearch, private val cwd: () -> String?) {

    suspend fun suggest(query: String, limit: Int = WorkspaceSearch.DEFAULT_LIMIT): List<FileMatch> {
        val dir = cwd() ?: return emptyList()
        // 会话 cwd 不在默认允许范围（/root）时登记一下，和 RootfsWorkspace.roots() 的做法一致。
        (search as? RootfsWorkspace)?.allowRoot(dir)
        return (search.searchFiles(dir, query, limit) as? Outcome.Ok)?.value ?: emptyList()
    }

    /** 给形态用的函数（CalmSurface 的 fileSearch 参数）；同一个实例，引用稳定。 */
    val asLambda: suspend (String) -> List<FileMatch> = { q -> suggest(q) }

    companion object {
        /** 真实引擎的当前 cwd（GatedEngineClient 包着的那个 PiEngine）；Fake 引擎返回 null。 */
        fun cwdOf(engine: EngineClient): () -> String? = {
            (((engine as? GatedEngineClient)?.inner ?: engine) as? PiEngine)?.cwd
        }
    }
}
