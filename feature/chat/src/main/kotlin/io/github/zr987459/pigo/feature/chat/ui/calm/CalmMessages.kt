package io.github.zr987459.pigo.feature.chat.ui.calm

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.contract.ToolStatus
import io.github.zr987459.pigo.contract.session.LocalBlock
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.ScrollAnchor
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.core.ui.calm.Calm
import kotlinx.coroutines.flow.distinctUntilChanged

/** 消息区的一行：会话条目或本地块。key 与 ChatItem.key 一致（阅读位置按它恢复，§5.3）。 */
private sealed interface Row_ {
    val key: String
    data class Item(val item: ChatItem) : Row_ { override val key get() = item.key }
    data class Local(val block: LocalBlock) : Row_ { override val key get() = block.key }
}

@Composable
internal fun CalmMessages(
    host: SessionHost,
    chat: ChatState,
    view: CalmViewState,
    onAction: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Calm.colors
    val locals by host.localBlocks.collectAsState()
    val rows = remember(chat.items, locals) { chat.items.map { Row_.Item(it) } + locals.map { Row_.Local(it) } }

    // 阅读位置：进入时按 host.scrollAnchor 恢复；离开（切形态）时写回（§5.3）。
    val initial = remember {
        val a = host.scrollAnchor.value
        val idx = if (a == null || a.followBottom) rows.lastIndex else rows.indexOfFirst { it.key == a.itemKey }
        (if (idx < 0) rows.lastIndex else idx).coerceAtLeast(0) to (if (a == null || a.followBottom) 0 else a.offsetPx)
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = initial.first, initialFirstVisibleItemScrollOffset = initial.second)
    val atBottom by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || (last.index >= info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 4)
        }
    }
    var follow by remember { mutableStateOf(host.scrollAnchor.value?.followBottom ?: true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to atBottom }.distinctUntilChanged().collect { (scrolling, bottom) ->
            if (scrolling) follow = bottom
        }
    }
    // 贴底跟随：新内容（含流式增长）到来时滚到底。
    val lastSize = (chat.items.lastOrNull() as? ChatItem.Assistant)?.blocks?.fold(0) { acc, b -> acc + if (b is ContentBlock.Text) b.text.length else 0 } ?: 0
    LaunchedEffect(rows.size, lastSize, follow) {
        if (follow && rows.isNotEmpty()) list.scrollToItem(rows.lastIndex, Int.MAX_VALUE / 2)
    }
    LaunchedEffect(view.jumpTick) {
        if (view.jumpTick == 0) return@LaunchedEffect
        val cur = list.firstVisibleItemIndex
        val prev = rows.indices.lastOrNull { it < cur && (rows[it] as? Row_.Item)?.item is ChatItem.User }
        if (prev != null) { follow = false; list.scrollToItem(prev) }
    }
    DisposableEffect(Unit) {
        onDispose {
            val i = list.firstVisibleItemIndex
            val key = rows.getOrNull(i)?.key
            host.scrollAnchor.value = if (key == null) ScrollAnchor("", 0, followBottom = true)
            else ScrollAnchor(key, list.firstVisibleItemScrollOffset, followBottom = follow && atBottom)
        }
    }

    if (rows.isEmpty()) {
        EmptyHeader(chat, modifier)
        return
    }
    LazyColumn(
        state = list,
        modifier = modifier.fillMaxWidth().testTag("calm:messages"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is Row_.Local -> LocalBlockView(row.block)
                is Row_.Item -> when (val it = row.item) {
                    is ChatItem.User -> UserBlock(it, onAction, highlight = view.searchQuery)
                    is ChatItem.Assistant -> AssistantBlock(it, view, onAction)
                    is ChatItem.Tool -> ProcessRow(host, it, expandedByDefault = view.toolsExpanded)
                    is ChatItem.Notice -> NoticeRow(it)
                    is ChatItem.Raw -> Text("[${it.role ?: "raw"}]", style = Calm.type.caption, color = c.secondary)
                }
            }
        }
    }
}

/** 空会话：pi 页眉信息（§4、X9：不放建议胶囊）。 */
@Composable
private fun EmptyHeader(chat: ChatState, modifier: Modifier) {
    val c = Calm.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp).testTag("calm:empty"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("pi", style = Calm.type.title.copy(fontWeight = FontWeight.Bold), color = c.text)
        chat.model?.let { Text("${it.provider} / ${it.name ?: it.id}" + (chat.thinkingLevel?.let { t -> " · $t" } ?: ""), style = Calm.type.code, color = c.secondary) }
        Text("输入消息开始；/ 命令，@ 文件，! 运行命令。", style = Calm.type.label, color = c.secondary)
        Text("⌘ 里能找到 pi 的全部功能。", style = Calm.type.label, color = c.secondary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBlock(item: ChatItem.User, onAction: (String, String?) -> Unit, highlight: String) {
    val c = Calm.colors
    var menu by remember { mutableStateOf(false) }
    val hit = highlight.isNotBlank() && item.text.contains(highlight, ignoreCase = true)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier.widthIn(max = 320.dp)
                .background(if (hit) c.accent.copy(alpha = 0.18f) else c.userBlock, RoundedCornerShape(12.dp))
                .combinedClickable(onClick = {}, onLongClick = { menu = true })
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("msg:${item.key}"),
        ) {
            Text(item.text, style = Calm.type.body, color = c.text)
            MessageMenu(menu, onDismiss = { menu = false }, key = item.key, text = item.text, isUser = true, onAction = onAction)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantBlock(item: ChatItem.Assistant, view: CalmViewState, onAction: (String, String?) -> Unit) {
    val c = Calm.colors
    var menu by remember { mutableStateOf(false) }
    val markdown = item.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
    Column(
        Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menu = true }).testTag("msg:${item.key}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (b in item.blocks) {
            when (b) {
                is ContentBlock.Thinking -> ThinkingRow(b, view.thinkingExpanded)
                is ContentBlock.Text -> MarkdownLite(b.text, highlight = view.searchQuery)
                is ContentBlock.ToolCall -> Unit // 工具调用由 ChatItem.Tool 的过程行显示
            }
        }
        item.errorMessage?.let { Text(it, style = Calm.type.label, color = c.error) }
        MessageMenu(menu, onDismiss = { menu = false }, key = item.key, text = markdown, isUser = false, onAction = onAction)
    }
}

/** 长按消息菜单（§4.5）：复制、复制 Markdown、从这里分叉（用户消息）、设置标签、在分支树中定位。 */
@Composable
private fun MessageMenu(expanded: Boolean, onDismiss: () -> Unit, key: String, text: String, isUser: Boolean, onAction: (String, String?) -> Unit) {
    val clipboard = LocalClipboardManager.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("复制") }, onClick = { onDismiss(); clipboard.setText(AnnotatedString(stripMarkdown(text))) }, modifier = Modifier.testTag("msgmenu:copy"))
        DropdownMenuItem(text = { Text("复制 Markdown") }, onClick = { onDismiss(); clipboard.setText(AnnotatedString(text)) }, modifier = Modifier.testTag("msgmenu:copyMd"))
        if (isUser) {
            DropdownMenuItem(text = { Text("从这里分叉") }, onClick = { onDismiss(); onAction("session.fork", key) }, modifier = Modifier.testTag("msgmenu:fork"))
        }
        DropdownMenuItem(
            text = { Text("设置标签") },
            onClick = { onDismiss(); onAction("tree.label", key) },
            modifier = Modifier.testTag(longPressTag("tree.label") ?: "longpress:tree.label"),
        )
        DropdownMenuItem(text = { Text("在分支树中定位") }, onClick = { onDismiss(); onAction("session.tree", key) }, modifier = Modifier.testTag("msgmenu:locate"))
    }
}

internal fun stripMarkdown(s: String): String = s.replace(Regex("```[a-zA-Z0-9_-]*\\n?"), "").replace(Regex("[*_`#>]+"), "")

@Composable
private fun ThinkingRow(b: ContentBlock.Thinking, expandAll: Boolean) {
    val c = Calm.colors
    var open by rememberSaveable { mutableStateOf(false) }
    val shown = open || expandAll
    Column(Modifier.fillMaxWidth().clickable { open = !open }.testTag("calm:thinking")) {
        val secs = b.durationMs?.let { " ${(it / 1000).coerceAtLeast(1)} 秒" } ?: ""
        Text((if (shown) "▾ " else "▸ ") + "思考了$secs", style = Calm.type.label, color = c.secondary)
        if (shown) Text(b.text, style = Calm.type.label, color = c.secondary, modifier = Modifier.padding(start = 14.dp, top = 4.dp))
    }
}

/** 极简 Markdown：正文 + 围栏代码块；mermaid 代码块可切「图示」（字符画，§0 Mermaid 取舍）。 */
@Composable
internal fun MarkdownLite(text: String, highlight: String = "") {
    val c = Calm.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (seg in splitFences(text)) {
            when (seg) {
                is MdSegment.Prose -> SelectionContainer {
                    val hit = highlight.isNotBlank() && seg.text.contains(highlight, ignoreCase = true)
                    Text(
                        seg.text,
                        style = Calm.type.body,
                        color = c.text,
                        modifier = if (hit) Modifier.background(c.accent.copy(alpha = 0.12f)) else Modifier,
                    )
                }
                is MdSegment.Code -> CodeBlock(seg.lang, seg.code)
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val c = Calm.colors
    var diagram by rememberSaveable(code) { mutableStateOf(false) }
    val isMermaid = lang.equals("mermaid", ignoreCase = true)
    Column(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(12.dp)).border(1.dp, c.stroke, RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(lang.ifBlank { "code" }, style = Calm.type.caption, color = c.secondary, modifier = Modifier.weight(1f))
            if (isMermaid) {
                Text(
                    if (diagram) "代码" else "图示",
                    style = Calm.type.caption, color = c.accent,
                    modifier = Modifier.clickable { diagram = !diagram }.padding(4.dp).testTag("calm:mermaidToggle"),
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            SelectionContainer {
                Text(if (isMermaid && diagram) MermaidAscii.render(code) else code, style = Calm.type.code, color = c.text)
            }
        }
    }
}

/** 过程行（§4.5 ProcessRow）：工具、bash 共用骨架；点开看参数与输出。 */
@Composable
private fun ProcessRow(host: SessionHost, item: ChatItem.Tool, expandedByDefault: Boolean) {
    val c = Calm.colors
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val shown = open || expandedByDefault
    val dot = when (item.status) {
        ToolStatus.PENDING, ToolStatus.RUNNING -> c.accent
        ToolStatus.OK -> c.success
        ToolStatus.ERROR -> c.error
    }
    Column(Modifier.fillMaxWidth().testTag("tool:${item.key}")) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val running = item.status == ToolStatus.RUNNING || item.status == ToolStatus.PENDING
            Box(
                Modifier.size(8.dp).then(
                    if (running) Modifier.border(1.5.dp, dot, CircleShape) else Modifier.background(dot, CircleShape),
                ),
            )
            Spacer(Modifier.size(10.dp))
            Text(toolSummary(item), style = Calm.type.label, color = c.text, maxLines = 1, modifier = Modifier.weight(1f))
            editStats(item)?.let { (add, del) ->
                Text("+$add −$del", style = Calm.type.caption, color = c.secondary, modifier = Modifier.padding(start = 8.dp))
            }
            item.durationMs?.let { Text(" %.1fs".format(it / 1000.0), style = Calm.type.caption, color = c.secondary) }
        }
        if (shown) {
            val out = toolOutput(item)
            if (out.isNotBlank()) {
                Box(
                    Modifier.fillMaxWidth().padding(start = 18.dp, top = 4.dp)
                        .background(c.surface, RoundedCornerShape(10.dp)).border(1.dp, c.stroke, RoundedCornerShape(10.dp))
                        .padding(10.dp).horizontalScroll(rememberScrollState()),
                ) {
                    SelectionContainer { Text(out.lines().take(200).joinToString("\n"), style = Calm.type.code, color = c.text) }
                }
            }
            fullOutputPath(item)?.let { path ->
                Text(
                    "查看完整输出",
                    style = Calm.type.caption, color = c.accent,
                    modifier = Modifier.padding(start = 18.dp, top = 4.dp).clickable {
                        host.overlay.push(OverlayModel.FullOutput(id = "fullOutput", title = "完整输出", path = path))
                    }.testTag("calm:fullOutput"),
                )
            }
        }
    }
}

/** 压缩卡、分支摘要卡（SummaryCard）与其他提示。 */
@Composable
private fun NoticeRow(item: ChatItem.Notice) {
    val c = Calm.colors
    if (item.kind == NoticeKind.COMPACTION) {
        var open by rememberSaveable(item.key) { mutableStateOf(false) }
        Column(
            Modifier.fillMaxWidth().border(1.dp, c.stroke, RoundedCornerShape(12.dp)).clickable { open = !open }.padding(12.dp).testTag("calm:summaryCard"),
        ) {
            Text(item.text.lineSequence().firstOrNull().orEmpty(), style = Calm.type.label, color = c.secondary)
            if (open) Text(item.text, style = Calm.type.label, color = c.text, modifier = Modifier.padding(top = 6.dp))
        }
        return
    }
    val color = when (item.kind) {
        NoticeKind.ERROR -> c.error
        else -> c.secondary
    }
    Text(item.text, style = Calm.type.caption, color = color, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun LocalBlockView(block: LocalBlock) {
    val c = Calm.colors
    Column(
        Modifier.fillMaxWidth().border(1.dp, c.stroke, RoundedCornerShape(12.dp)).padding(12.dp).testTag("local:${block.title}"),
    ) {
        Text(block.title.removePrefix("explain:").let { if (block.title.startsWith("explain:")) "说明" else it }, style = Calm.type.caption, color = c.secondary)
        Spacer(Modifier.size(4.dp))
        SelectionContainer { Text(block.text, style = Calm.type.label, color = c.text) }
    }
}
