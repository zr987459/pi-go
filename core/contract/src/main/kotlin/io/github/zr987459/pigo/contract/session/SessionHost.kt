package io.github.zr987459.pigo.contract.session

import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.actions.PiActions
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** 动作表：两种形态的全部入口都走这里（§5.4）。 */
    val actions: PiActions

    /** 当前会话是否临时会话（`--no-session` 近似，§5.7）。 */
    val ephemeral: StateFlow<Boolean>

    /** pi 进程 PID（经 ProcessTree 读取，仪器测试 B 档用）；引擎未运行时为 null。 */
    fun debugEnginePid(): Int?
}

/** 首个可见块的 `ChatItem.key` 与像素偏移；[followBottom] 为真表示原来贴底。 */
data class ScrollAnchor(val itemKey: String, val offsetPx: Int = 0, val followBottom: Boolean = false)

/** 本地文本块（不发给 pi、不写会话文件）。 */
data class LocalBlock(val key: String, val title: String, val text: String)
