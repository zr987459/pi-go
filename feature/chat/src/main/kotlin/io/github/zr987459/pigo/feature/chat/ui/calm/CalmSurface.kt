package io.github.zr987459.pigo.feature.chat.ui.calm

import io.github.zr987459.pigo.contract.workspace.FileMatch
import androidx.compose.animation.AnimatedVisibility
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
) {
    if (dark == null) CalmTheme { CalmSurfaceBody(host, modifier, onOpenSettings, panelEvents, fileSearch) }
    else CalmTheme(dark = dark) { CalmSurfaceBody(host, modifier, onOpenSettings, panelEvents, fileSearch) }
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
) {
    val c = Calm.colors
    val scope = rememberCoroutineScope()
    val view = remember { CalmViewState() }
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
            }
        }
    }
    val toggleForm: () -> Unit = { host.form.value = if (host.form.value == UiForm.CALM) UiForm.TERM else UiForm.CALM }

    LaunchedEffect(view.toast) { if (view.toast != null) { delay(2500); view.toast = null } }

    Box(modifier.fillMaxSize().background(c.background).testTag("calm:surface")) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            CalmTopBar(
                title = chat.sessionName,
                subtitle = subtitleOf(chat.running, chat.statusLine, chat.retry?.let { "重试 ${it.attempt}/${it.maxAttempts}" }, ephemeral),
                onMenu = { view.drawerOpen = true },
                onRename = { run("session.name", null) },
                onCommands = { openCommandCenter(host) },
                onToggleForm = toggleForm,
                onAction = run,
            )
            if (engineStatus is EngineStatus.Crashed) {
                val s = engineStatus as EngineStatus.Crashed
                Text(
                    "引擎已停止：${s.hint}",
                    style = Calm.type.caption, color = c.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("calm:engineCrashed"),
                )
            }
            if (view.searchOpen) {
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
            CalmComposer(host = host, chat = chat, view = view, onAction = run, fileSearch = fileSearch)
        }

        // 面板（overlay 栈顶，两种形态共用同一模型，§5.4）
        val top = overlays.lastOrNull()
        if (top != null) {
            val sink = panelEvents?.takeIf { it.owns(top.id) }
            val panel: ((String, String?) -> Unit)? = sink?.let { s ->
                { key, value ->
                    scope.launch {
                        val r = s.onEvent(top.id, key, value)
                        CalmPanelLogic.apply(host.overlay, top.id, r)?.let { view.toast = it }
                    }
                }
            }
            CalmSheet(
                host = host, model = top, onAction = run, panel = panel,
                onDismiss = {
                    if (sink != null) CalmPanelLogic.dismissEvent(top)?.let { k -> scope.launch { sink.onEvent(top.id, k, null) } }
                    host.overlay.pop()
                },
            )
        }

        // ☰ 会话列表
        AnimatedVisibility(view.drawerOpen, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).testTag("calm:drawerScrim").clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
            ) { view.drawerOpen = false })
        }
        AnimatedVisibility(
            view.drawerOpen,
            enter = slideInHorizontally(tween(180)) { -it },
            exit = slideOutHorizontally(tween(180)) { -it },
        ) {
            CalmSessionList(
                host = host,
                onAction = run,
                onClose = { view.drawerOpen = false },
                onOpenSettings = { view.drawerOpen = false; onOpenSettings() },
                onToast = { view.toast = it },
            )
        }

        view.toast?.let { t ->
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp)) {
                Text(
                    t,
                    style = Calm.type.label,
                    color = c.background,
                    modifier = Modifier.background(c.text.copy(alpha = 0.88f), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp).testTag("calm:toast"),
                )
            }
        }
        if (view.editorExpanded) {
            ExpandedEditor(host = host, onClose = { view.editorExpanded = false })
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
    host.overlay.push(
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

@Composable
private fun CalmTopBar(
    title: String?,
    subtitle: String,
    onMenu: () -> Unit,
    onRename: () -> Unit,
    onCommands: () -> Unit,
    onToggleForm: () -> Unit,
    onAction: (String, String?) -> Unit,
) {
    val c = Calm.colors
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMenu, modifier = Modifier.testTag(persistentTag("session.resume") ?: "persist:session.resume")) {
            Icon(CalmIcons.Menu, contentDescription = "会话列表", tint = c.text)
        }
        Column(
            Modifier.weight(1f).clickable(onClick = onRename).padding(horizontal = 4.dp, vertical = 2.dp)
                .testTag(persistentTag("session.name") ?: "persist:session.name"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title?.takeIf { it.isNotBlank() } ?: "新对话",
                    style = Calm.type.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(CalmIcons.ChevronDown, contentDescription = null, tint = c.secondary, modifier = Modifier.size(16.dp))
            }
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = Calm.type.caption, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("calm:subtitle"))
            }
        }
        IconButton(onClick = onCommands, modifier = Modifier.testTag("calm:commands")) {
            Icon(CalmIcons.Command, contentDescription = "命令中心", tint = c.text)
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("calm:sessionMenu")) {
                Icon(CalmIcons.More, contentDescription = "会话菜单", tint = c.text)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for ((id, label) in SESSION_MENU) {
                    DropdownMenuItem(
                        text = { Text(label, style = Calm.type.label) },
                        onClick = { menu = false; if (id == "form.toggle") onToggleForm() else onAction(id, null) },
                        modifier = Modifier.testTag("menu:$id"),
                    )
                }
            }
        }
        Box(Modifier.testTag("form:toggle")) {
            IconButton(onClick = onToggleForm, modifier = Modifier.testTag(persistentTag("form.toggle") ?: "persist:form.toggle")) {
                Icon(CalmIcons.Swap, contentDescription = "切到终端形态", tint = c.text)
            }
        }
        Spacer(Modifier.width(4.dp))
    }
}

/** 会话菜单（⋯，§4.3）。 */
internal val SESSION_MENU: List<Pair<String, String>> = listOf(
    "session.tree" to "分支树",
    "session.clone" to "复制会话",
    "session.export" to "导出（HTML / JSONL）",
    "session.share" to "分享链接",
    "transcript.search" to "搜索本会话",
    "tools.expandAll" to "全部展开 / 折叠工具",
    "session.info" to "会话信息",
    "form.toggle" to "切换形态",
)
