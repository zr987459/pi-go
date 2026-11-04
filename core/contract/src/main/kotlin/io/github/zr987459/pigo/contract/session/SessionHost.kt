package io.github.zr987459.pigo.contract.session

import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.actions.PiActions
import io.github.zr987459.pigo.contract.settings.PiSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 两种形态共用的会话宿主（DESIGN-r3 §5.1）。由 AppGraph 持有单例，与进程同寿；
 * 旋转、深浅色、切换形态都不重建。实现在 `:core:session`（r3/session），开发期用 `FakeSessionHost`。
 *
 * 规则：
 * - 公开 API **没有任何 [UiForm] 参数**；形态只经 [form] 读写（FormSwitchContractTest）。
 * - 改 [form] 不发出任何 RPC 命令、不重启引擎（SessionHostFormSwitchTest）。
 * - 两种形态的 Composable 不持有业务状态，只读这里的 StateFlow，动作一律走 [actions]。
 */
interface SessionHost {
    /** 唯一的引擎（`pi --mode rpc` 单进程）。 */
    val engine: EngineClient

    /** 同一个引擎的扩展能力（真实实现里与 [engine] 是同一个 `PiEngine` 对象）。 */
    val extras: EngineExtras

    /** 归约后的聊天状态，整个进程生命周期内是同一个 StateFlow 实例。 */
    val chat: StateFlow<ChatState>

    /** 共享草稿：文字、选区、图片、`@` 引用、`!` 模式、「已撤回」芯片。 */
    val draft: MutableStateFlow<DraftState>

    /** 打开中的面板（含搜索词、页签、滚动位置、表单已填值）。 */
    val overlay: OverlayStack

    /** 当前形态（存 App 设置）。只被 FormHost 与 App 设置读取。 */
    val form: MutableStateFlow<UiForm>

    /** 已发送过的提示（新的在后），供历史翻找（清单 §9 历史）。 */
    val promptHistory: StateFlow<List<String>>

    /** 阅读位置（按 ChatItem.key）；null = 贴底跟随。 */
    val scrollAnchor: MutableStateFlow<ScrollAnchor?>

    /** `/session`、`/hotkeys`、`/changelog` 等本地文本块，不写会话文件。 */
    val localBlocks: StateFlow<List<LocalBlock>>

    /** 动作表（§5.4）。形态不直接调 `actions.run`，一律经 [runAction]。 */
    val actions: PiActions

    /**
     * 两种形态执行动作的唯一入口：调 `actions.run`，再把结果落地**一次**——
     * [ActionResult.Overlay] 压进 [overlay]，[ActionResult.LocalBlock] 追加到 [localBlocks]，其余原样返回。
     * 形态拿到返回值后**不得再 push**，只渲染 [overlay] 栈顶（审查第 1 轮 Important 2）。
     */
    suspend fun runAction(id: String, args: String? = null): ActionResult

    /** pi 自己的设置（`settings.json`，§5.8）。 */
    val settings: PiSettings

    /** 会话目录（`/resume`、`/import`、删除撤销）。 */
    val sessions: SessionCatalog

    /** 当前会话是否临时会话（`--no-session` 近似，§5.7）。 */
    val ephemeral: StateFlow<Boolean>

    /**
     * 要写进系统剪贴板的文字（`/copy`、长按复制）。两种形态都收集它并真正写剪贴板（审查第 1 轮 I4）。
     * 默认空流：r3/session 加的最小契约改动，旧实现（含 FakeSessionHost）不用改。
     */
    val clipboardRequests: Flow<String> get() = emptyFlow()

    /** pi 进程 PID（经 ProcessTree 读取，仪器测试 B 档用）；引擎未运行时为 null。 */
    fun debugEnginePid(): Int?
}

/** 首个可见块的 `ChatItem.key` 与像素偏移；[followBottom] 为真表示原来贴底。 */
data class ScrollAnchor(val itemKey: String, val offsetPx: Int = 0, val followBottom: Boolean = false)

/** 本地文本块（不发给 pi、不写会话文件）。 */
data class LocalBlock(val key: String, val title: String, val text: String)
