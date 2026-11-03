package io.github.zr987459.pigo.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.zr987459.pigo.contract.DraftStore
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.feature.chat.ui.ChatScreen
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 每个引擎一个 [ChatController]，生命周期跟随引擎（即整个 App 进程），而不是某个页面：
 * - 扩展对话框请求即使在聊天页不可见时也有人接收并保留，回到聊天页时仍在；
 * - 草稿、撤回芯片、折叠状态在旋转屏幕、切页、切后台后都保留（S5）。
 */
object ChatControllers {
    private val controllers = WeakHashMap<EngineClient, ChatController>()

    /**
     * @param scope 控制器协程的作用域；默认是主线程上的独立 SupervisorJob（与 App 同寿）。
     *              `:app` 的 ChatBindings 会传入 AppGraph.appScope。
     */
    @Synchronized
    fun obtain(
        engine: EngineClient,
        scope: CoroutineScope? = null,
        config: ChatConfig = ChatConfig(),
        /** W1 的其余 RPC（图片、`/`、`!`、分叉）；W5 接线时由 app 传入。 */
        extras: EngineExtras? = null,
        /** W4 的按会话草稿存储。 */
        drafts: DraftStore? = null,
    ): ChatController = controllers.getOrPut(engine) {
        ChatController(engine, scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), config, extras = extras, drafts = drafts)
    }
}

/** 聊天页入口（按引擎取控制器）。 */
@Composable
fun ChatRoute(
    engine: EngineClient,
    modifier: Modifier = Modifier,
    onOpenModelSettings: (() -> Unit)? = null,
) {
    val controller = remember(engine) { ChatControllers.obtain(engine) }
    ChatScreen(controller, modifier, onOpenModelSettings)
}

/**
 * scaffold 导航（`:app` 的 PiGoNavHost）仍以这个名字调用聊天页；占位内容已替换为真正的聊天界面。
 *
 * 待办（交给 mvp/integration，见 PR #4 说明）：这里没有 `onOpenModelSettings`，错误卡上的「去设置模型」（§3.9）
 * 暂时不会出现。integration 接导航时改为直接调用 [ChatRoute] 并传入跳转设置页的回调。
 */
@Composable
fun ChatPlaceholder(engine: EngineClient, modifier: Modifier = Modifier) {
    ChatRoute(engine, modifier)
}
