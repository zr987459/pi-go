// 页面布局照 dsh-Go（https://github.com/ccch1mneyyy/dsh-Go，AGPL-3.0）app/lib/ui/chat/chat_screen.dart、
// empty_state.dart、empty_actions.dart、common/fade_in.dart 逐项翻译：渐变背景上，顶栏与输入条悬浮，
// 列表从两者下面滚过；空会话居中问候（displaySmall）+ 状态灰字（bodyLarge）+ 主色胶囊 / 建议胶囊，
// 逐个淡入上浮（240ms，间隔 30ms，上浮 8dp）。见 THIRD_PARTY_NOTICES.md §8。
package io.github.zr987459.pigo.feature.chat.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.core.ui.AppBackdrop
import io.github.zr987459.pigo.core.ui.AppIcons
import io.github.zr987459.pigo.core.ui.AppMotion
import io.github.zr987459.pigo.core.ui.AppShape
import io.github.zr987459.pigo.core.ui.AppSpace
import io.github.zr987459.pigo.core.ui.ChromeIconButton
import io.github.zr987459.pigo.core.ui.DshTheme
import io.github.zr987459.pigo.core.ui.GlassSurface
import io.github.zr987459.pigo.core.ui.GlassTier
import io.github.zr987459.pigo.core.ui.Glyph
import io.github.zr987459.pigo.core.ui.PrimaryPillButton
import io.github.zr987459.pigo.core.ui.statusColor
import io.github.zr987459.pigo.core.ui.StatusTone
import io.github.zr987459.pigo.feature.chat.ChatController
import io.github.zr987459.pigo.feature.chat.logic.ChatRow
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 原生聊天页（DESIGN.md §5.3–§5.5），外观照 dsh-Go 主页。
 *
 * @param onOpenModelSettings 错误卡上「去设置模型」的跳转；为 null 时不显示该按钮（导航归 :app）。
 * @param backToBackground 聊天页按返回键时把 App 退到后台（N4），前台服务与引擎不结束。
 * @param onOpenDrawer 顶栏「≡」：打开侧边会话抽屉；为 null 时不显示。
 * @param onNewChat 顶栏「新建对话」；为 null 时不显示。
 * @param onModelClick 顶栏「选择模型▾」；为 null 时用内置的模型/思考级别下拉。
 * @param onAttachClick 输入框「+」；为 null 时提示「暂不支持添加附件」。
 */
@Composable
fun ChatScreen(
    controller: ChatController,
    modifier: Modifier = Modifier,
    onOpenModelSettings: (() -> Unit)? = null,
    backToBackground: Boolean = true,
    onOpenDrawer: (() -> Unit)? = null,
    onNewChat: (() -> Unit)? = null,
    onModelClick: (() -> Unit)? = null,
    onAttachClick: (() -> Unit)? = null,
    topBanner: (@Composable () -> Unit)? = null,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

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
    val scrolledUnder by remember { derivedStateOf { listState.canScrollForward } }
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
    val density = LocalDensity.current
    var bottomPx by remember { mutableIntStateOf(0) }
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val headerHeight = statusTop + ChatHeaderBarHeight + 8.dp
    val connected = state.engineStatus is EngineStatus.Idle || state.engineStatus is EngineStatus.Running

    AppBackdrop(modifier.testTag("page:chat")) {
        Box(Modifier.fillMaxSize().imePadding()) {
            // —— 消息列表：从顶栏和输入条下面滚过 ——
            LazyColumn(
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(
                    start = AppSpace.Gutter,
                    end = AppSpace.Gutter,
                    top = headerHeight + 8.dp,
                    bottom = with(density) { bottomPx.toDp() } + 8.dp,
            // 空会话：放在消息列表之后（位于上层），「启动引擎 / 重新连接」和建议胶囊的点击不会被列表拦住。
            if (rows.isEmpty()) {
                ChatEmptyState(
                    status = state.engineStatus,
                    onStartEngine = controller::restartEngine,
                    onSuggestion = { controller.setDraftFromSuggestion(it) },
                    modifier = Modifier.fillMaxSize().padding(top = headerHeight, bottom = with(density) { bottomPx.toDp() }),
                )
            }
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpace.InTurn, Alignment.Bottom),
                modifier = Modifier.fillMaxSize().testTag("chat:list"),
            ) {
                items(rows.asReversed(), key = { it.key }, contentType = { it::class }) { row ->
                    RowEnter(row.key) {
                        ChatRowView(
                            row,
                            isLatestUser = row.key == latestUserKey,
                            actions = actions,
                            modifier = Modifier.widthIn(max = AppSpace.ReadingWidth).padding(top = if (row is ChatRow.User) AppSpace.BetweenTurns - AppSpace.InTurn else 0.dp),
                        )
                    }
                }
            }

            // —— 顶栏：滚过内容时铺一层由背景色到透明的渐隐（dsh-Go TopFade） ——
            AnimatedVisibility(scrolledUnder, enter = fadeIn(tween(AppMotion.Quick)), exit = fadeOut(tween(AppMotion.Quick))) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(headerHeight + 12.dp)
                        .background(Brush.verticalGradient(listOf(DshTheme.colors.backdropTop, DshTheme.colors.backdropTop.copy(alpha = 0f)))),
                )
            }
            ChatTopBar(
                title = state.title,
                model = state.chat.model,
                thinkingLevel = state.chat.thinkingLevel,
                contextPercent = state.contextPercent,
                running = state.chat.running,
                loadModels = controller::loadModels,
                onPickModel = controller::setModel,
                loadThinkingLevels = controller::thinkingLevels,
                onPickThinking = controller::setThinking,
                status = headerStatus(state.engineStatus, state.chat.running),
                onMenuClick = onOpenDrawer,
                onModelClick = onModelClick,
                onNewChat = onNewChat,
            )

            // —— 底部：横幅胶囊 + 输入条 ——
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = AppSpace.FloatInset, end = AppSpace.FloatInset, bottom = 8.dp)
                    .widthIn(max = AppSpace.ReadingWidth)
                    .onSizeChanged { bottomPx = it.height },
            ) {
                topBanner?.invoke()
                EngineBanner(state.engineStatus, onRestart = controller::restartEngine)
                state.retry?.let { RetryBar(it, onStop = controller::stop) }
                QueueAndStatus(
                    queue = state.chat.queue,
                    withdrawn = state.composer.withdrawn,
                    statusLines = state.statusLines,
                    onWithdrawnClick = { showWithdrawn = true },
                )
                Box {
                    ComposerBar(
                        composer = state.composer,
                        primary = state.primary,
                        running = state.chat.running,
                        onDraftChange = controller::setDraft,
                        onSubmit = controller::submit,
                        onStop = controller::stop,
                        connected = connected,
                        contextPercent = state.contextPercent,
                        onAttachClick = onAttachClick,
                        onUnsupported = ::toast,
                    )
                    // 回到底部：浮在输入条右上方的小圆键。
                    androidx.compose.animation.AnimatedVisibility(
                        !atBottom,
                        enter = scaleIn(tween(AppMotion.Quick)) + fadeIn(),
                        exit = scaleOut(tween(AppMotion.Quick)) + fadeOut(),
                        modifier = Modifier.align(Alignment.TopEnd).graphicsLayer { translationY = -with(density) { 52.dp.toPx() } },
                    ) {
                        ChromeIconButton(
                            AppIcons.scrollToBottom,
                            "回到底部",
                            { scope.launch { listState.animateScrollToItem(0) } },
                            Modifier.testTag("chat:toBottom"),
                            size = 40.dp,
                            iconSize = 20.dp,
                        )
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = with(density) { bottomPx.toDp() } + 8.dp))
        }
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

/** 建议胶囊只往输入框里填文字（和用户自己打字一样），不新增发送逻辑；已有草稿时追加到末尾，不覆盖。 */
private fun ChatController.setDraftFromSuggestion(prompt: String) = setDraft(SuggestionText.merge(state.value.composer.draft, prompt))

/** dsh-Go `greetingFor`：5–11 早上好、11–13 中午好、13–18 下午好、18–23 晚上好、其余 夜深了。 */
fun greetingFor(time: LocalTime): String = when (time.hour) {
    in 5..10 -> "早上好"
    in 11..12 -> "中午好"
    in 13..17 -> "下午好"
    in 18..22 -> "晚上好"
    else -> "夜深了"
}

/** dsh-Go 的四个默认建议（文案照抄 app_zh.arb，把「DSH」换成 pi 的语境）。 */
internal val DefaultSuggestions: List<Triple<Glyph, String, String>>
    get() = listOf(
        Triple(AppIcons.folderOpen, "看看工作区有什么", "看看工作区里现在有哪些文件和项目，简单介绍一下。"),
        Triple(AppIcons.code, "写个词频统计脚本", "写一个 Python 脚本：统计一个文本文件里出现最多的 20 个词，并运行一次给我看结果。"),
        Triple(AppIcons.terminal, "新建 Node.js 项目", "在工作区新建一个 Node.js 项目，配好 TypeScript 和一个最小的单元测试。"),
        Triple(AppIcons.tip, "rebase 还是 merge", "用一个具体例子解释 git rebase 和 git merge 的区别，各自什么时候用。"),
    )

/**
 * 空会话（dsh-Go `ChatEmptyState`）：问候 + 引擎状态 + 一个主色胶囊（引擎没在运行时「启动引擎」）或建议胶囊。
 * 「启动引擎」接现有的 [ChatController.restartEngine]（引擎的 restart 在停止状态下就是启动）。
 */
@Composable
fun ChatEmptyState(
    status: EngineStatus,
    onStartEngine: () -> Unit,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
    now: LocalTime = LocalTime.now(),
) {
    val connected = status is EngineStatus.Idle || status is EngineStatus.Running
    val subtitle = when (status) {
        is EngineStatus.Idle, is EngineStatus.Running -> "今天想做点什么？"
        is EngineStatus.Starting -> "正在启动引擎…"
        is EngineStatus.Crashed -> "没能连上引擎"
        is EngineStatus.Stopped -> "引擎未运行"
    }
    val pill = when (status) {
        is EngineStatus.Stopped -> "启动引擎"
        is EngineStatus.Crashed -> "重新连接"
        else -> null
    }
    Box(modifier.padding(horizontal = AppSpace.FloatInset).testTag("chat:empty"), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = AppSpace.ReadingWidth - AppSpace.FloatInset * 2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FadeSlideIn(0) {
                Text(
                    greetingFor(now),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("chat:greeting"),
                )
            }
            Spacer(Modifier.height(4.dp))
            FadeSlideIn(1) {
                AnimatedContent(subtitle, transitionSpec = { fadeIn(tween(AppMotion.Quick)) togetherWith fadeOut(tween(AppMotion.Quick)) }, label = "sub") { s ->
                    Text(
                        s,
                        style = MaterialTheme.typography.bodyLarge,
                        color = DshTheme.colors.textFaint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("chat:engineState"),
                    )
                }
            }
            if (pill != null) {
                Spacer(Modifier.height(12.dp))
                FadeSlideIn(2) {
                    PrimaryPillButton(pill, onStartEngine, Modifier.testTag("chat:startEngine"))
                }
            }
            if (connected) {
                Spacer(Modifier.height(32.dp))
                val list = DefaultSuggestions
                list.chunked(2).forEachIndexed { r, pair ->
                    if (r > 0) Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEachIndexed { c, (icon, label, prompt) ->
                            FadeSlideIn(3 + r * 2 + c, Modifier.weight(1f)) {
                                QuietChip(label, Modifier.fillMaxWidth(), icon = icon, onClick = { onSuggestion(prompt) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** dsh-Go `FadeSlideIn`：按 index 错开 30ms，240ms 淡入并从下方 8dp 浮起。 */
@Composable
fun FadeSlideIn(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val anim = remember { Animatable(if (index >= AppMotion.StaggerLimit) 1f else 0f) }
    val rise = with(LocalDensity.current) { AppMotion.Rise.toPx() }
    LaunchedEffect(Unit) {
        if (anim.value < 1f) {
            delay((AppMotion.Stagger * index).toLong())
            anim.animateTo(1f, tween(AppMotion.Standard, easing = AppMotion.Enter))
        }
    }
    Box(modifier.graphicsLayer { alpha = anim.value; translationY = (1f - anim.value) * rise }) { content() }
}

/** 新消息出现：淡入 + 上浮（只在首次组合时播放，滚动回来不重播）。 */
@Composable
private fun RowEnter(key: String, content: @Composable () -> Unit) {
    val seen = remember(key) { Animatable(0f) }
    val rise = with(LocalDensity.current) { AppMotion.Rise.toPx() }
    LaunchedEffect(key) { seen.animateTo(1f, tween(AppMotion.Standard, easing = AppMotion.Enter)) }
    Box(Modifier.graphicsLayer { alpha = seen.value; translationY = (1f - seen.value) * rise }) { content() }
}

/** 引擎状态横幅：启动中 / 异常退出（给「重启引擎」）。dsh-Go 的横幅都是输入条上方的胶囊。 */
@Composable
private fun EngineBanner(status: EngineStatus, onRestart: () -> Unit) {
    val (text, canRestart) = when (status) {
        is EngineStatus.Starting -> "pi 正在启动…" to false
        is EngineStatus.Crashed -> ("pi 异常退出：" + status.hint.ifBlank { "原因未知" } +
            (status.retryInMs?.let { "，${(it + 999) / 1000} 秒后自动重启" } ?: "")) to true
        else -> return
    }
    val tone = if (canRestart) StatusTone.Error else StatusTone.Busy
    GlassSurface(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("chat:engine"), GlassTier.Chrome, AppShape.Lg) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            io.github.zr987459.pigo.core.ui.StatusDot(tone, size = 8.dp)
            Spacer(Modifier.padding(start = 10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (canRestart) statusColor(StatusTone.Error) else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
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
