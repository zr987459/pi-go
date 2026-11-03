package io.github.zr987459.pigo.contract.bridge

import io.github.zr987459.pigo.contract.Outcome
import kotlinx.coroutines.flow.StateFlow

/*
 * DESIGN-r3 §5.6：补 RPC 缺口的随包扩展 `pigo-bridge.ts` 的调用契约（r3/core 新增文件）。
 * 真实实现是 `PiEngine`（与 EngineClient/EngineExtras 同一个对象），开发期用 `FakePiBridge`。
 * 实测结论见 docs/protocol/r3-measurements.md §1。
 */

enum class BridgeState {
    /** 引擎刚启动，还没收到桥接的 ready。 */
    PENDING,

    /** 已收到 ready，四条命令可用。 */
    READY,

    /** 未安装、加载失败（extension_error）或 ready 超时：走退路。 */
    UNAVAILABLE,
}

data class BridgeStatus(
    val state: BridgeState,
    /** UNAVAILABLE 时的原因（给界面显示「标签不可用：…」）。 */
    val reason: String? = null,
    val version: Int? = null,
)

/**
 * @param viaFork 桥接不可用、退化为「从该记录 fork 出新会话」（§5.6 退路）；此时 [sessionFile] 是新会话文件。
 */
data class TreeNavResult(
    val leafId: String?,
    val sessionFile: String?,
    val cancelled: Boolean,
    val viaFork: Boolean = false,
)

interface PiBridge {
    val bridgeStatus: StateFlow<BridgeStatus>

    /** `/pigo:tree-nav`：在同一会话文件内把叶子切到 [entryId]（`/tree` 选中）。桥接不可用时退化为 fork。 */
    suspend fun treeNavigate(entryId: String, summarize: Boolean = false): Outcome<TreeNavResult>

    /** `/pigo:label`：给记录设置标签；[label] 为 null 或空白 = 清除。桥接不可用时返回 Err（带原因）。 */
    suspend fun setEntryLabel(entryId: String, label: String?): Outcome<Unit>

    /**
     * `/pigo:reload`：重新加载扩展、技能、模板、主题、上下文文件（App 写完 settings.json 等文件后调用）。
     * 桥接不可用时返回 Err，[RELOAD_NEEDS_RESTART] 作为消息，由界面确认后走唯一允许的重启路径（§5.5）。
     */
    suspend fun reloadResources(): Outcome<Unit>

    /** `/pigo:switch`：经扩展切换会话文件（等价于 RPC `switch_session`，但走扩展的会话替换路径）。 */
    suspend fun switchSessionViaBridge(sessionPath: String): Outcome<Unit>

    companion object {
        const val RELOAD_NEEDS_RESTART = "需要重启引擎才能重新加载（桥接扩展不可用）"

        /** 桥接注册的命令名前缀；`get_commands` 结果里要过滤掉。 */
        const val COMMAND_PREFIX = "pigo:"
    }
}
