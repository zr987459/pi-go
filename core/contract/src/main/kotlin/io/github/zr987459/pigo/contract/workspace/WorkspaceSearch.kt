package io.github.zr987459.pigo.contract.workspace

import io.github.zr987459.pigo.contract.FileNode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.TextChunk
import io.github.zr987459.pigo.contract.WorkspaceFs

/*
 * DESIGN-r3 §1.2「RootfsWorkspace 改造」：工作区只留 pi 有的能力（r3/core 新增文件）。
 * - `@文件` 模糊搜索（清单 §10，pi-tui `CombinedAutocompleteProvider.getFuzzyFileSuggestions` 的行为）
 * - 读文件内容（`@` 展开、`full_output_path`）
 * - 当前分支名（页脚，清单 §11）
 * - 只列目录（「在其他目录新建会话」的目录选择器）
 * 文件浏览器、预览、Git 页由 r3/app 删除（§1.1 X4、X5）；在那之前旧的 [WorkspaceFs] 仍可用，git 部分已停用。
 */

/**
 * 一条 `@` 候选。
 * @param path 相对搜索根（会话 cwd）的路径，目录不带结尾 `/`
 * @param completion 插入输入框的文字（pi 的格式：`@路径`，目录带 `/`，含空格时加引号 `@"a b.txt"`）
 */
data class FileMatch(
    val guestPath: String,
    val path: String,
    val name: String,
    val isDir: Boolean,
    val score: Int,
    val completion: String,
)

interface WorkspaceSearch {
    /**
     * 在 [cwd] 下模糊搜索文件与目录（含隐藏文件、跳过 `.git`、遵守 `.gitignore`），按 pi 的打分排序，最多 [limit] 条。
     * [query] 为空时返回 [cwd] 第一层。
     */
    suspend fun searchFiles(cwd: String, query: String, limit: Int = DEFAULT_LIMIT): Outcome<List<FileMatch>>

    /** 读文本（`@` 展开、查看截断输出的 `full_output_path`）；超过 [maxBytes] 截断。 */
    suspend fun readFile(guestPath: String, maxBytes: Long = WorkspaceFs.DEFAULT_MAX_TEXT_BYTES): Outcome<TextChunk>

    /** [cwd] 所在 git 仓库的当前分支名；不是仓库返回 null，分离头指针返回 `"detached"`（与 pi 页脚一致）。 */
    suspend fun branchName(cwd: String): String?

    /** 只列 [guestDir] 下的子目录（目录选择器）。 */
    suspend fun listDirectories(guestDir: String, showHidden: Boolean = false): Outcome<List<FileNode>>

    companion object {
        /** pi-tui 取前 20 条候选。 */
        const val DEFAULT_LIMIT = 20
    }
}
