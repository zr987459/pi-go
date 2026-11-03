package io.github.zr987459.pigo.feature.chat.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.feature.chat.ChatController
import io.github.zr987459.pigo.feature.chat.logic.ChatRow
import kotlinx.coroutines.launch

/**
 * 原生聊天页（DESIGN.md §5.3–§5.5）。
 *
 * @param onOpenModelSettings 错误卡上「去设置模型」的跳转；为 null 时不显示该按钮（导航归 :app）。
 * @param backToBackground 聊天页按返回键时把 App 退到后台（N4），前台服务与引擎不结束。
 */
@Composable
fun ChatScreen(
    controller: ChatController,
    modifier: Modifier = Modifier,
    onOpenModelSettings: (() -> Unit)? = null,
    backToBackground: Boolean = true,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 前台/后台：后台时对话类扩展请求用通知提醒。离开聊天页也算「看不到」。
    DisposableEffect(controller, lifecycleOwner) {
        if (controller.notifier == null) controller.notifier = AndroidExtUiNotifier(context)
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> controller.setForeground(true)
                Lifecycle.Event.ON_STOP -> controller.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            controller.setForeground(false)
        }
    }

    // N4：聊天页按返回 → 退到后台，不结束 Activity，也不碰引擎。
    if (backToBackground) BackToBackgroundEffect(context)

    LaunchedEffect(controller) {
        controller.messages.collect { snackbar.showSnackbar(it) }
    }

    val rows = state.rows
    val latestUserKey = remember(rows) { rows.lastOrNull { it is ChatRow.User }?.key }
    val listState = rememberLazyListState()
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    val lastKey = rows.lastOrNull()?.key
    // 倒序布局天然贴底；新行出现时如果用户本来就在底部，跟到最新。
    LaunchedEffect(lastKey) {
        if (atBottom && rows.isNotEmpty()) listState.scrollToItem(0)
    }

    val actions = remember(controller, onOpenModelSettings) {
        RowActions(
            onRetryLocal = controller::retry,
            onEditLocal = controller::editFailed,
            onDeleteLocal = controller::deleteFailed,
            onResend = controller::resend,
            onToggleTurn = controller::toggleTurn,
            onOpenModelSettings = onOpenModelSettings,
        )
    }
    var showWithdrawn by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().testTag("page:chat")) {
        Column(Modifier.fillMaxSize().imePadding()) {
            ChatTopBar(
                title = state.title,
                model = state.chat.model,
                thinkingLevel = state.chat.thinkingLevel,
                contextPercent = state.contextPercent,
                running = state.chat.running,
                loadModels = controller::models,
                onPickModel = controller::setModel,
                loadThinkingLevels = controller::thinkingLevels,
                onPickThinking = controller::setThinking,
            )
            EngineBanner(state.engineStatus, onRestart = controller::restartEngine)
            state.chat.retry?.let { RetryBar(it, onStop = controller::stop) }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (rows.isEmpty()) {
                    EmptyHint(Modifier.align(Alignment.Center))
                }
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                    modifier = Modifier.fillMaxSize().testTag("chat:list"),
                ) {
                    items(rows.asReversed(), key = { it.key }, contentType = { it::class }) { row ->
                        ChatRowView(row, isLatestUser = row.key == latestUserKey, actions = actions)
                    }
                }
                if (!atBottom) {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("chat:toBottom"),
                    ) { Text("↓", style = MaterialTheme.typography.titleMedium) }
                }
            }

            QueueAndStatus(
                queue = state.chat.queue,
                withdrawn = state.composer.withdrawn,
                statusLines = state.statusLines,
                onWithdrawnClick = { showWithdrawn = true },
            )
            ComposerBar(
                composer = state.composer,
                primary = state.primary,
                running = state.chat.running,
                onDraftChange = controller::setDraft,
                onSubmit = controller::submit,
                onStop = controller::stop,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }

    state.dialog?.let { d ->
        ExtUiDialog(d, pending = state.pendingDialogs, onAnswer = controller::answerDialog, onExpired = controller::expireDialog)
    }
    state.replaceDraftOffer?.let {
        ReplaceDraftDialog(it, onReplace = controller::acceptReplaceDraft, onKeep = controller::rejectReplaceDraft)
    }
    if (showWithdrawn && state.composer.withdrawn.isNotEmpty()) {
        WithdrawnDialog(
            texts = state.composer.withdrawn,
            onInsert = {
                controller.insertWithdrawn()
                showWithdrawn = false
            },
            onDiscard = {
                controller.discardWithdrawn()
                showWithdrawn = false
            },
            onDismiss = { showWithdrawn = false },
        )
    }
}

@Composable
private fun EmptyHint(modifier: Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("开始和 pi 对话", style = MaterialTheme.typography.titleMedium)
        Text(
            "pi 会在手机里的 Ubuntu 容器中读写文件、运行命令。\n试试：「列出当前目录并读 README」",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 引擎状态条：启动中 / 异常退出（给「重启引擎」）。 */
@Composable
private fun EngineBanner(status: EngineStatus, onRestart: () -> Unit) {
    val (text, canRestart) = when (status) {
        is EngineStatus.Starting -> "pi 正在启动…" to false
        is EngineStatus.Crashed -> ("pi 异常退出：" + status.hint.ifBlank { "原因未知" } +
            (status.retryInMs?.let { "，${(it + 999) / 1000} 秒后自动重启" } ?: "")) to true
        else -> return
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("chat:engine")) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (canRestart) TextButton(onClick = onRestart) { Text("重启引擎") }
        }
    }
}

/** 把返回键改成「退到后台」（moveTaskToBack），只在聊天页生效。 */
@Composable
private fun BackToBackgroundEffect(context: Context) {
    val activity = remember(context) { context.findActivity() as? ComponentActivity } ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    val dispatcher = activity.onBackPressedDispatcher
    DisposableEffect(dispatcher, lifecycleOwner) {
        val cb = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                activity.moveTaskToBack(true)
            }
        }
        dispatcher.addCallback(lifecycleOwner, cb)
        onDispose { cb.remove() }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
