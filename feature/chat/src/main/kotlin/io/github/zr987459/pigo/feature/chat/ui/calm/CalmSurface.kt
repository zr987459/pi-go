@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.zr987459.pigo.feature.chat.ui.calm

import io.github.zr987459.pigo.contract.workspace.FileMatch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import io.github.zr987459.pigo.core.ui.calm.CalmDims
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.contract.session.UiForm
import io.github.zr987459.pigo.core.ui.calm.Calm
import io.github.zr987459.pigo.core.ui.calm.CalmIcons
import io.github.zr987459.pigo.core.ui.calm.CalmTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * UI 形态（DESIGN-r3 §4）。只读 [SessionHost] 的 StateFlow，动作一律经 [SessionHost.runAction]；
 * 本 Composable 只有纯视图状态（抽屉开合、菜单、提示、搜索条），切换形态时业务状态全在 [host] 里（§5.1、§5.3）。
 *
 * @param onOpenSettings ☰ 底部齿轮：设置（只有「模型」，§6.1），由 App 导航接线。
 * @param dark null = 跟随系统。
 */
@Composable
fun CalmSurface(
    host: SessionHost,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    dark: Boolean? = null,
    /** 模型配置面板的事件接收方（装配层注入 RealModelPanels；为 null 时面板内点击照旧走 runAction）。 */
    panelEvents: PanelEventSink? = null,
    /** `@文件` 模糊搜索（装配层注入 core/workspace FileSearch，根 = 会话 cwd）；为 null 时只能手动输入路径引用。 */
    fileSearch: (suspend (String) -> List<FileMatch>)? = null,
    /** 「＋ › 图片」：装配层打开系统图片选择器，选中的图片写进 `host.draft.images`（两形态共用，PR #21 审查 Important 1）。 */
    onAttachImage: (() -> Unit)? = null,
    /**
     * 返回键注册（装配层传 `{ enabled, onBack -> BackHandler(enabled, onBack) }`；本模块不依赖 activity-compose）。
     * 有可关的层（[CalmBack]）时启用；都关着时不拦截，交给外层（FormHost 退到后台）。
     */
    backHandler: (@Composable (enabled: Boolean, onBack: () -> Unit) -> Unit)? = null,
) {
    if (dark == null) CalmTheme { CalmSurfaceBody(host, modifier, onOpenSettings, panelEvents, fileSearch, onAttachImage, backHandler) }
    else CalmTheme(dark = dark) { CalmSurfaceBody(host, modifier, onOpenSettings, panelEvents, fileSearch, onAttachImage, backHandler) }
}

/** 视图状态：只影响本形态怎么画，不是业务状态。 */
internal class CalmViewState {
    var drawerOpen by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    var searchOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var toolsExpanded by mutableStateOf(false)
    var thinkingExpanded by mutableStateOf(false)
    var editorExpanded by mutableStateOf(false)
    var jumpTick by mutableStateOf(0)
    var historyCursor by mutableStateOf(-1)
}

@Composable
private fun CalmSurfaceBody(
    host: SessionHost,
    modifier: Modifier,
    onOpenSettings: () -> Unit,
    panelEvents: PanelEventSink? = null,
    fileSearch: (suspend (String) -> List<FileMatch>)? = null,
    onAttachImage: (() -> Unit)? = null,
    backHandler: (@Composable (enabled: Boolean, onBack: () -> Unit) -> Unit)? = null,
) {
    val c = Calm.colors
    val scope = rememberCoroutineScope()
    val view = remember { CalmViewState() }
    val sendGate = remember { PanelSendGate() }
    val chat by host.chat.collectAsState()
    val overlays by host.overlay.items.collectAsState()
    val engineStatus by host.engine.status.collectAsState()
    val ephemeral by host.ephemeral.collectAsState()
    val clipboard = LocalClipboardManager.current
    // `/copy`、分享链接等：共享层经 SessionHost.clipboardRequests 请求写剪贴板，本形态真正写入。
    LaunchedEffect(host) { host.clipboardRequests.collect { t -> clipboard.setText(AnnotatedString(t)) } }

    val run: (String, String?) -> Unit = { id, args ->
        scope.launch {
            val r = host.runAction(id, args)
            if (r is ActionResult.Toast) view.toast = r.text
            when (id) {
                "transcript.search" -> view.searchOpen = true
                "tools.expandAll" -> view.toolsExpanded = !view.toolsExpanded
                "thinking.toggle" -> view.thinkingExpanded = !view.thinkingExpanded
                "editor.expand" -> view.editorExpanded = true
                "editor.clear" -> host.draft.value = io.github.zr987459.pigo.contract.session.DraftState(recalled = host.draft.value.recalled)
                "editor.history" -> historyAt(host.promptHistory.value, view.historyCursor)?.let { (t, k) ->
                    view.historyCursor = k; host.draft.value = host.draft.value.withText(t)
                }
                "transcript.jump" -> view.jumpTick++
                "attach.image" -> if (onAttachImage != null) onAttachImage() else view.toast = "图片附件未接线"
            }
        }
    }
    val toggleForm: () -> Unit = { host.form.value = if (host.form.value == UiForm.CALM) UiForm.TERM else UiForm.CALM }

    LaunchedEffect(view.toast) { if (view.toast != null) { delay(2500); view.toast = null } }

    // 顶栏下沿（相对本形态的位置，px）：面板 / 扩展对话框的遮罩从这里往下画，顶栏的 ⇄ 在面板打开时仍可点
    // （硬性要求 ②「一键即切」；面板、扩展对话框在两种形态共用，切过去照样在）。
    // 顶栏其余按钮（☰、改名、⌘、⋯）在有面板时锁住，与之前遮罩盖住整个顶栏时一致：扩展对话框等待应答期间仍是模态的
    // （ExtDialog 固定在栈顶，PR #23；终端形态 Esc 也是先应答对话框），会话列表也不会画到面板下面（PR #26 增量审查 I1、Nit 1）。
    var surfaceY by remember { mutableFloatStateOf(0f) }
    var topBarBottom by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    Box(modifier.fillMaxSize().background(c.background).testTag("calm:surface").onGloballyPositioned { surfaceY = it.positionInRoot().y }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Box(Modifier.onGloballyPositioned { topBarBottom = it.positionInRoot().y + it.size.height }) {
            CalmTopBar(
                title = chat.sessionName,
                subtitle = subtitleOf(chat.running, chat.statusLine, chat.retry?.let { "重试 ${it.attempt}/${it.maxAttempts}" }, ephemeral),
                running = chat.running,
                onMenu = { view.drawerOpen = true },
                onRename = { run("session.name", null) },
                onToggleForm = toggleForm,
                onAction = run,
                locked = overlays.isNotEmpty(),
            )
            }
            if (engineStatus is EngineStatus.Crashed) {
                val s = engineStatus as EngineStatus.Crashed
                Text(
                    "引擎已停止：${s.hint}",
                    style = Calm.type.caption, color = c.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                        .background(c.error.copy(alpha = 0.08f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("calm:engineCrashed"),
                )
            }
            AnimatedVisibility(view.searchOpen, enter = fadeIn(tween(CalmDims.MOTION_MS)) + expandVertically(tween(CalmDims.MOTION_MS)), exit = fadeOut(tween(CalmDims.MOTION_MS)) + shrinkVertically(tween(CalmDims.MOTION_MS))) {
                TranscriptSearch(
                    query = view.searchQuery,
                    onQuery = { view.searchQuery = it },
                    hits = countHits(chat.items, view.searchQuery),
                    onClose = { view.searchOpen = false; view.searchQuery = "" },
                )
            }
            CalmMessages(
                host = host,
                chat = chat,
                view = view,
                onAction = run,
                modifier = Modifier.weight(1f),
            )
            CalmComposer(host = host, chat = chat, view = view, onAction = run, fileSearch = fileSearch, locked = overlays.isNotEmpty())
        }

        // 会话列表（OpenMinis 的首页 SessionListScreen）：整页从左侧推入，聊天页在它之上「推出」（OpenMinis 导航转场）。
        AnimatedVisibility(
            view.drawerOpen,
            enter = slideInHorizontally(tween(CalmDims.PAGE_MS, easing = FastOutSlowInEasing)) { -it / 3 } + fadeIn(tween(CalmDims.PAGE_MS)),
            exit = slideOutHorizontally(tween(CalmDims.PAGE_MS, easing = FastOutSlowInEasing)) { -it / 3 } + fadeOut(tween(CalmDims.PAGE_MS)),
        ) {
            CalmSessionList(
                host = host,
                onAction = run,
                onClose = { view.drawerOpen = false },
                onOpenSettings = { view.drawerOpen = false; onOpenSettings() },
                onOpenTerminal = { view.drawerOpen = false; host.form.value = UiForm.TERM },
                onToast = { view.toast = it },
            )
        }

        if (view.editorExpanded) {
            ExpandedEditor(host = host, onClose = { view.editorExpanded = false })
        }

        // 面板（overlay 栈顶，两种形态共用同一模型，§5.4）。画在会话列表、展开编辑器之上（第 2 轮 Nit 2）：
        // 从会话列表或编辑器里弹出的面板 / 扩展对话框必须在用户眼前；返回键顺序（CalmBack）与这个画法一致。
        val top = overlays.lastOrNull()
        fun dismissTop(t: io.github.zr987459.pigo.contract.session.OverlayModel) {
            val sink = panelEvents?.takeIf { it.owns(t.id) }
            if (sink != null) CalmPanelLogic.dismissEvent(t)?.let { k -> scope.launch { sink.onEvent(t.id, k, null) } }
            host.overlay.remove(t.id)
        }
        // 返回键（r5：OpenMinis 导航——聊天页在会话列表之上）：面板 → 展开编辑器 → 搜索 → 聊天页返回会话列表；
        // 会话列表是首页，再按返回不拦截（外层退到后台）。
        val backStep = CalmBack.step(view.editorExpanded, top, view.drawerOpen, view.searchOpen)
        backHandler?.invoke(backStep != CalmBack.Step.NONE) {
            when (CalmBack.step(view.editorExpanded, host.overlay.top, view.drawerOpen, view.searchOpen)) {
                CalmBack.Step.EDITOR -> view.editorExpanded = false
                CalmBack.Step.OVERLAY -> host.overlay.top?.let { dismissTop(it) }
                CalmBack.Step.LIST -> view.drawerOpen = true
                CalmBack.Step.SEARCH -> { view.searchOpen = false; view.searchQuery = "" }
                CalmBack.Step.NONE -> Unit
            }
        }
        if (top != null) {
            val sink = panelEvents?.takeIf { it.owns(top.id) }
            val panel: ((String, String?) -> Unit)? = sink?.let { s ->
                { key, value ->
                    scope.launch {
                        CalmPanelLogic.dispatch(host.overlay, sendGate, s, top.id, key, value)?.let { view.toast = it }
                    }
                }
            }
            Box(Modifier.fillMaxSize().padding(top = with(density) { (topBarBottom - surfaceY).coerceAtLeast(0f).toDp() })) {
                CalmSheet(
                    host = host, model = top, onAction = run, panel = panel,
                    onDismiss = { dismissTop(top) },
                )
            }
        }

        // 提示（OpenMinis：底部居中的深色圆角胶囊，淡入淡出）
        AnimatedVisibility(
            view.toast != null,
            enter = fadeIn(tween(CalmDims.MOTION_MS)) + slideInVertically(tween(CalmDims.MOTION_MS)) { it / 2 },
            exit = fadeOut(tween(CalmDims.MOTION_MS)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 128.dp),
        ) {
            var shown by remember { mutableStateOf("") }
            view.toast?.let { shown = it }
            Text(
                shown,
                style = Calm.type.label,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 24.dp).background(c.toastBg, RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 10.dp).testTag("calm:toast"),
            )
        }
    }
}

internal fun subtitleOf(running: Boolean, status: String?, retry: String?, ephemeral: Boolean): String =
    listOfNotNull(
        if (ephemeral) "临时会话：离开后不保留" else null,
        retry ?: if (running) "生成中" else null,
        status?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

internal fun openCommandCenter(host: SessionHost) {
    if (host.overlay.top?.id == COMMANDS_OVERLAY_ID) return
    // 不盖住扩展对话框（ExtDialog 固定在栈顶，PR #23）
    CalmPanelLogic.pushBelowPinned(
        host.overlay,
        io.github.zr987459.pigo.contract.session.OverlayModel.SelectList(id = COMMANDS_OVERLAY_ID, title = "⌘ 命令中心"),
    )
}

private fun countHits(items: List<ChatItem>, q: String): Int {
    if (q.isBlank()) return 0
    return items.count { searchableText(it).contains(q, ignoreCase = true) }
}

internal fun searchableText(item: ChatItem): String = when (item) {
    is ChatItem.User -> item.text
    is ChatItem.Assistant -> item.blocks.joinToString("\n") { b ->
        when (b) { is ContentBlock.Text -> b.text; is ContentBlock.Thinking -> b.text; is ContentBlock.ToolCall -> b.name }
    }
    is ChatItem.Tool -> toolSummary(item) + "\n" + toolOutput(item)
    is ChatItem.Notice -> item.text
    is ChatItem.Raw -> item.json.toString()
}

/**
 * 聊天页顶栏（OpenMinis ChatScreen TopAppBar）：左 ← 返回会话列表；中间居中的会话名（点按改名，下方一行状态）；
 * 右侧「终端」图标（OpenMinis 会话列表 / 聊天菜单里的 Terminal 入口）与 ⋯ 菜单（带图标的分组菜单）。
 */
@Composable
private fun CalmTopBar(
    title: String?,
    subtitle: String,
    running: Boolean,
    onMenu: () -> Unit,
    onRename: () -> Unit,
    onToggleForm: () -> Unit,
    onAction: (String, String?) -> Unit,
    /** 有面板 / 扩展对话框打开：只有终端入口可点，其余按钮停用（[CalmSurface] 里的说明）。 */
    locked: Boolean = false,
) {
    val c = Calm.colors
    var menu by remember { mutableStateOf(false) }
    val tint = if (locked) c.tertiary else c.text
    // 上锁（来了面板 / 扩展对话框）时收起 ⋯ 菜单并复位：否则对话框结束后菜单会自己重新弹出（PR #26 定向复核 Nit）
    LaunchedEffect(locked) { if (locked) menu = false }
    Box(Modifier.fillMaxWidth().height(56.dp).background(c.background)) {
        IconButton(
            onClick = onMenu, enabled = !locked,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp).testTag(persistentTag("session.resume") ?: "persist:session.resume"),
        ) {
            Icon(CalmIcons.ArrowBack, contentDescription = "会话列表", tint = tint)
        }
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 104.dp).clip(RoundedCornerShape(10.dp))
                .clickable(enabled = !locked, onClick = onRename).padding(horizontal = 10.dp, vertical = 2.dp)
                .testTag(persistentTag("session.name") ?: "persist:session.name"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title?.takeIf { it.isNotBlank() } ?: "新对话",
                style = Calm.type.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            AnimatedVisibility(subtitle.isNotEmpty(), enter = fadeIn(tween(CalmDims.MOTION_MS)), exit = fadeOut(tween(CalmDims.MOTION_MS))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (running) { BreathingDot(c.accent); Spacer(Modifier.width(5.dp)) }
                    Text(subtitle, style = Calm.type.caption, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("calm:subtitle"))
                }
            }
        }
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.testTag("form:toggle")) {
                IconButton(onClick = onToggleForm, modifier = Modifier.testTag(persistentTag("form.toggle") ?: "persist:form.toggle")) {
                    Icon(CalmIcons.Terminal, contentDescription = "打开终端", tint = c.text)
                }
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = !locked, modifier = Modifier.testTag("calm:sessionMenu")) {
                    Icon(CalmIcons.More, contentDescription = "会话菜单", tint = tint)
                }
                // 菜单开着时弹出扩展对话框 / 面板：菜单一并收起
                MinisMenu(expanded = menu && !locked, onDismissRequest = { menu = false }) {
                    SESSION_MENU.forEachIndexed { i, (id, label) ->
                        if (id in SESSION_MENU_DIVIDER_BEFORE && i > 0) MinisMenuDivider()
                        MinisMenuItem(
                            label = label,
                            icon = sessionMenuIcon(id),
                            onClick = { menu = false; if (id == "form.toggle") onToggleForm() else onAction(id, null) },
                            modifier = Modifier.testTag("menu:$id"),
                        )
                    }
                }
            }
        }
    }
}

/** 生成中标题下的呼吸点（OpenMinis 的 TypingIndicator / 呼吸动效的简化版）。 */
@Composable
internal fun BreathingDot(color: Color, size: androidx.compose.ui.unit.Dp = 6.dp) {
    val t = rememberInfiniteTransition(label = "breath")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathAlpha")
    Box(Modifier.size(size).graphicsLayer { alpha = a }.background(color, CircleShape))
}

private fun sessionMenuIcon(id: String) = when (id) {
    "session.new" -> CalmIcons.Compose
    "session.tree" -> CalmIcons.Branch
    "session.clone" -> CalmIcons.Copy
    "session.export" -> CalmIcons.Folder
    "session.share" -> CalmIcons.Share
    "transcript.search" -> CalmIcons.Search
    "tools.expandAll" -> CalmIcons.Wrench
    "session.info" -> CalmIcons.Info
    "form.toggle" -> CalmIcons.Terminal
    else -> null
}

/** 菜单分组：这些项前画分隔线（OpenMinis MinisMenuDivider 的用法）。 */
internal val SESSION_MENU_DIVIDER_BEFORE = setOf("session.tree", "transcript.search", "form.toggle")

/** 会话菜单（⋯，§4.3）。 */
internal val SESSION_MENU: List<Pair<String, String>> = listOf(
    "session.new" to "新对话",
    "session.share" to "分享链接",
    "session.tree" to "分支树",
    "session.clone" to "复制会话",
    "session.export" to "导出（HTML / JSONL）",
    "session.share" to "分享链接",
    "transcript.search" to "搜索本会话",
    "tools.expandAll" to "全部展开 / 折叠工具",
    "session.info" to "会话信息",
    "form.toggle" to "打开终端",
)
