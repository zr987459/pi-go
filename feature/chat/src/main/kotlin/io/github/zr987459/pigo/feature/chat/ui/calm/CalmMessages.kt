@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.zr987459.pigo.feature.chat.ui.calm

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import io.github.zr987459.pigo.core.ui.calm.CalmDims
import io.github.zr987459.pigo.core.ui.calm.CalmIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.ui.layout.onSizeChanged
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
    // 是否贴底跟随只由「用户拖动」和「一次滚动结束时停在哪」决定。
    // 原来是「滚动进行中就 follow = atBottom」：程序自己滚到底（scrollToItem）的那一帧 layoutInfo 还没更新，
    // atBottom 会读成 false，于是贴底被意外关掉。之后流式输出在上方长高，就会把刚出现的结果块挤出视口。
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> dragging = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> dragging = false
                else -> Unit
            }
        }
    }
    LaunchedEffect(list) {
        var wasScrolling = false
        snapshotFlow { Triple(list.isScrollInProgress, atBottom, dragging) }.distinctUntilChanged().collect { (scrolling, bottom, drag) ->
            if (drag) follow = bottom // 手指拖动中：跟手
            else if (wasScrolling && !scrolling) follow = bottom // 惯性、无障碍或程序滚动停下：停在底部才贴底
            wasScrolling = scrolling
        }
    }
    // 贴底跟随：新内容（含流式增长）到来时滚到底。
    val lastSize = (chat.items.lastOrNull() as? ChatItem.Assistant)?.blocks?.fold(0) { acc, b -> acc + if (b is ContentBlock.Text) b.text.length else 0 } ?: 0
    LaunchedEffect(rows.size, lastSize, follow) {
        if (follow && rows.isNotEmpty()) list.scrollToItem(rows.lastIndex, Int.MAX_VALUE / 2)
    }
    // 视口变高 / 变矮（软键盘弹出收起、imePadding、旋转）时仍贴底：原来只在内容变化时滚，
    // 流式暂停或已结束时弹出键盘，最后几行就被挤到视口下面，切换形态时又按「不在底部」写回锚点，终端从顶部开始显示。
    // 视口高度由 onSizeChanged 写入状态、在效应里滚动：不能在 snapshotFlow 收集里直接 scrollToItem——
    // 布局阶段（SubcomposeLayout 放置时）发出的快照通知可能同步恢复收集协程，scrollToItem 的强制重测就会嵌套在测量里
    // （performMeasureAndLayout called during measure layout）。
    var viewportH by remember { mutableStateOf(0) }
    LaunchedEffect(viewportH) {
        if (viewportH > 0 && follow && rows.isNotEmpty() && !dragging) list.requestScrollToItem(rows.lastIndex, Int.MAX_VALUE / 2)
    }
    // 新的本地结果块（「说明」条目、/session、/hotkeys 等）是用户刚触发的动作结果：滚到它并恢复贴底跟随。
    // 否则先用过「跳到上一条提示」或手动上滑（follow = false）后，结果追加在视口外，用户看不到任何反应。
    // 初值取进入时的条数，所以切换形态回来不会强行滚动，阅读位置照常恢复（§5.3）。
    var seenLocals by remember { mutableStateOf(locals.size) }
    LaunchedEffect(locals.size) {
        if (locals.size > seenLocals && rows.isNotEmpty()) {
            follow = true
            list.scrollToItem(rows.lastIndex, Int.MAX_VALUE / 2)
        }
        seenLocals = locals.size
    }
    LaunchedEffect(view.jumpTick) {
        if (view.jumpTick == 0) return@LaunchedEffect
        val cur = list.firstVisibleItemIndex
        val prev = rows.indices.lastOrNull { it < cur && (rows[it] as? Row_.Item)?.item is ChatItem.User }
        if (prev != null) { follow = false; list.scrollToItem(prev) }
    }
    DisposableEffect(Unit) {
        onDispose {
            // 贴底与否以 follow 为准（只由用户拖动 / 滚动停下的位置改写）；不再与 atBottom 相与：
            // 视口刚变化、贴底滚动还没跟上的那一帧离开，会把「贴底」误记成「停在中间」。
            val i = list.firstVisibleItemIndex
            val key = rows.getOrNull(i)?.key
            host.scrollAnchor.value = if (key == null) ScrollAnchor("", 0, followBottom = true)
            else ScrollAnchor(key, list.firstVisibleItemScrollOffset, followBottom = follow)
        }
    }

    if (rows.isEmpty()) {
        EmptyHeader(chat, modifier)
        return
    }
    // 等待首个 token：最后一条是用户消息且正在生成 → 助手页眉 + 跳动三点（OpenMinis TypingIndicator）
    val waiting = chat.running && (chat.items.lastOrNull() as? ChatItem.User) != null && locals.isEmpty()
    LazyColumn(
        state = list,
        modifier = modifier.fillMaxWidth().testTag("calm:messages").onSizeChanged { viewportH = it.height },
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(rows, key = { _, r -> r.key }) { idx, row ->
            val prev = rows.getOrNull(idx - 1)
            val enter = Modifier.animateItem(fadeInSpec = tween(220), placementSpec = null, fadeOutSpec = tween(150))
            when (row) {
                is Row_.Local -> LocalBlockView(row.block, enter)
                is Row_.Item -> when (val it = row.item) {
                    is ChatItem.User -> UserBlock(it, onAction, highlight = view.searchQuery, precededByUser = (prev as? Row_.Item)?.item is ChatItem.User, modifier = enter)
                    is ChatItem.Assistant -> AssistantBlock(it, view, onAction, showHeader = startsAssistantTurn(prev), modifier = enter)
                    is ChatItem.Tool -> Column(enter) {
                        if (startsAssistantTurn(prev)) AssistantHeader()
                        ProcessRow(host, it, expandedByDefault = view.toolsExpanded)
                    }
                    is ChatItem.Notice -> NoticeRow(it, enter)
                    is ChatItem.Raw -> Text("[${it.role ?: "raw"}]", style = Calm.type.caption, color = c.secondary, modifier = enter)
                }
            }
        }
        if (waiting) item(key = "calm:typing") {
            Column(Modifier.animateItem(fadeInSpec = tween(220), placementSpec = null, fadeOutSpec = tween(120)).testTag("calm:typing")) {
                AssistantHeader()
                Box(Modifier.padding(start = 2.dp, top = 8.dp, bottom = 8.dp)) { BouncingDots(c.secondary) }
            }
        }
    }
}

/** 助手一轮的开头（前一行是用户消息或列表开头）：画 OpenMinis 的助手页眉。 */
private fun startsAssistantTurn(prev: Row_?): Boolean = prev == null || (prev as? Row_.Item)?.item is ChatItem.User

/** 助手页眉（OpenMinis AssistantHeader：渐变 ✦ 图标 + 名字，16sp SemiBold）。 */
@Composable
private fun AssistantHeader() {
    val c = Calm.colors
    Row(Modifier.padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            CalmIcons.Sparkle, contentDescription = null,
            modifier = Modifier.size(18.dp).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val brush = Brush.linearGradient(listOf(Color(0xFF7B61FF), Color(0xFF3FA9F5)))
                    onDrawWithContent { drawContent(); drawRect(brush, blendMode = BlendMode.SrcAtop) }
                },
            tint = c.accent,
        )
        Spacer(Modifier.width(6.dp))
        Text("pi", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text)
    }
}

/** 跳动三点（OpenMinis BouncingDots：4dp 圆点，700ms 周期，依次错开 120ms）。 */
@Composable
internal fun BouncingDots(color: Color) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            val y by t.animateFloat(
                0f, 1f,
                infiniteRepeatable(keyframes { durationMillis = 700; 0f at 0; 1f at 175; 0f at 350; 0f at 700 }, RepeatMode.Restart, StartOffset(i * 120)),
                label = "dot$i",
            )
            Box(Modifier.size(5.dp).graphicsLayer { translationY = -3.dp.toPx() * y }.background(color, CircleShape))
        }
    }
}

/** 空会话：pi 页眉信息（§4、X9：不放建议胶囊）——OpenMinis 空聊天页的居中问候样式。 */
@Composable
private fun EmptyHeader(chat: ChatState, modifier: Modifier) {
    val c = Calm.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp).testTag("calm:empty"),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).background(c.accent.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            Text("π", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.accent)
        }
        Text("有什么可以帮你？", style = Calm.type.largeTitle, color = c.text)
        chat.model?.let { Text("${it.provider} / ${it.name ?: it.id}" + (chat.thinkingLevel?.let { t -> " · $t" } ?: ""), style = Calm.type.caption, color = c.secondary) }
        Text("输入消息开始；/ 命令，@ 文件，! 运行命令。", style = Calm.type.label, color = c.secondary)
        Text("点输入框里的 / 能找到 pi 的全部功能。", style = Calm.type.label, color = c.tertiary)
    }
}

/**
 * 用户气泡（OpenMinis UserMessageBubble）：靠右、最宽 80%、18dp 圆角、16.5sp；长按震动并弹出贴右对齐的菜单。
 * 排队中的消息（delivery = QUEUED）画成透明底 + 虚线框 + 灰字。
 */
@Composable
private fun UserBlock(item: ChatItem.User, onAction: (String, String?) -> Unit, highlight: String, precededByUser: Boolean, modifier: Modifier = Modifier) {
    val c = Calm.colors
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val hit = highlight.isNotBlank() && item.text.contains(highlight, ignoreCase = true)
    val queued = item.delivery == io.github.zr987459.pigo.contract.Delivery.QUEUED
    BoxWithConstraints(modifier.fillMaxWidth().padding(top = if (precededByUser) 14.dp else 10.dp, bottom = 4.dp)) {
        val maxW = maxWidth * 0.8f
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f).widthIn(min = 60.dp))
            Box(Modifier.widthIn(max = maxW)) {
                val shape = RoundedCornerShape(CalmDims.bubbleRadius)
                Text(
                    item.text,
                    style = Calm.type.bubble,
                    color = if (queued) c.secondary else c.text,
                    modifier = Modifier
                        .clip(shape)
                        .background(if (hit) c.accent.copy(alpha = 0.22f) else if (queued) Color.Transparent else c.userBlock, shape)
                        .then(if (queued) Modifier.dashedBorder(c.secondary.copy(alpha = 0.5f), CalmDims.bubbleRadius) else Modifier)
                        .combinedClickable(onClick = {}, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true })
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("msg:${item.key}"),
                )
                MessageMenu(menu, onDismiss = { menu = false }, key = item.key, text = item.text, isUser = true, onAction = onAction, alignEnd = true)
            }
        }
    }
}

/** 虚线圆角框（OpenMinis 排队气泡）。 */
internal fun Modifier.dashedBorder(color: Color, radius: androidx.compose.ui.unit.Dp): Modifier = drawBehind {
    val stroke = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f))
    drawRoundRect(color = color, cornerRadius = CornerRadius(radius.toPx(), radius.toPx()), style = stroke)
}

/** 助手消息（OpenMinis AssistantMessageView）：无气泡、全宽；思考块、正文 Markdown；正文增长时平滑撑开。 */
@Composable
private fun AssistantBlock(item: ChatItem.Assistant, view: CalmViewState, onAction: (String, String?) -> Unit, showHeader: Boolean, modifier: Modifier = Modifier) {
    val c = Calm.colors
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val markdown = item.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
    Column(modifier.fillMaxWidth()) {
        if (showHeader) AssistantHeader()
        Box {
            Column(
                Modifier.fillMaxWidth().animateContentSize(tween(160))
                    .combinedClickable(onClick = {}, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true })
                    .padding(vertical = 4.dp)
                    .testTag("msg:${item.key}"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (b in item.blocks) {
                    when (b) {
                        is ContentBlock.Thinking -> ThinkingRow(b, view.thinkingExpanded, streaming = item.streaming && b === item.blocks.lastOrNull())
                        is ContentBlock.Text -> MarkdownLite(b.text, highlight = view.searchQuery)
                        is ContentBlock.ToolCall -> Unit // 工具调用由 ChatItem.Tool 的胶囊行显示
                    }
                }
                item.errorMessage?.let { InlineErrorBanner(it) }
            }
            MessageMenu(menu, onDismiss = { menu = false }, key = item.key, text = markdown, isUser = false, onAction = onAction)
        }
    }
}

/** 行内错误条（OpenMinis InlineErrorBanner：浅红底 12dp 圆角 + ⚠）。 */
@Composable
private fun InlineErrorBanner(text: String) {
    val c = Calm.colors
    Row(
        Modifier.fillMaxWidth().background(c.danger.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(CalmIcons.Alert, contentDescription = null, tint = c.danger, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = Calm.type.label, color = c.danger)
    }
}

/** 长按消息菜单（§4.5，OpenMinis 带图标菜单）：复制、复制 Markdown、从这里分叉（用户消息）、设置标签、在分支树中定位。 */
@Composable
private fun MessageMenu(expanded: Boolean, onDismiss: () -> Unit, key: String, text: String, isUser: Boolean, onAction: (String, String?) -> Unit, alignEnd: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    MinisMenu(expanded = expanded, onDismissRequest = onDismiss, alignEnd = alignEnd, offset = DpOffset(0.dp, 6.dp), minWidth = 200.dp) {
        MinisMenuItem("复制", icon = CalmIcons.Copy, onClick = { onDismiss(); clipboard.setText(AnnotatedString(stripMarkdown(text))) }, modifier = Modifier.testTag("msgmenu:copy"))
        MinisMenuItem("复制 Markdown", icon = CalmIcons.Copy, onClick = { onDismiss(); clipboard.setText(AnnotatedString(text)) }, modifier = Modifier.testTag("msgmenu:copyMd"))
        MinisMenuDivider()
        if (isUser) {
            MinisMenuItem("从这里分叉", icon = CalmIcons.Branch, onClick = { onDismiss(); onAction("session.fork", key) }, modifier = Modifier.testTag("msgmenu:fork"))
        }
        MinisMenuItem("设置标签", icon = CalmIcons.Pencil, onClick = { onDismiss(); onAction("tree.label", key) }, modifier = Modifier.testTag(longPressTag("tree.label") ?: "longpress:tree.label"))
        MinisMenuItem("在分支树中定位", icon = CalmIcons.Search, onClick = { onDismiss(); onAction("session.tree", key) }, modifier = Modifier.testTag("msgmenu:locate"))
    }
}

internal fun stripMarkdown(s: String): String = s.replace(Regex("```[a-zA-Z0-9_-]*\\n?"), "").replace(Regex("[*_`#>]+"), "")

/**
 * 思考块（OpenMinis ThinkingBlock）：浅蓝底 12dp 圆角卡片 + 0.5dp 蓝描边；生成中左侧转圈，结束后 💡；
 * 标题「思考中… / 思考了 N 秒」+ 字数；点按展开 / 收起（展开有尺寸动画）。
 */
@Composable
private fun ThinkingRow(b: ContentBlock.Thinking, expandAll: Boolean, streaming: Boolean) {
    val c = Calm.colors
    var open by rememberSaveable { mutableStateOf(false) }
    val shown = open || expandAll
    val blue = c.thinking
    val arrow by animateFloatAsState(if (shown) 90f else 0f, tween(200), label = "thinkArrow")
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(blue.copy(alpha = 0.06f))
            .border(0.5.dp, blue.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .clickable { open = !open }
            .animateContentSize(tween(200))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("calm:thinking"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (streaming) CircularProgressIndicator(color = blue, strokeWidth = 1.5.dp, modifier = Modifier.size(13.dp))
            else Icon(CalmIcons.Lightbulb, contentDescription = null, tint = blue, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            val secs = b.durationMs?.let { " ${(it / 1000).coerceAtLeast(1)} 秒" } ?: ""
            Text(if (streaming) "思考中…" else "思考了$secs", style = Calm.type.label.copy(fontWeight = FontWeight.Medium), color = blue, modifier = Modifier.weight(1f))
            val n = b.text.length
            Text(if (n >= 1000) "${n / 1000}K 字" else "$n 字", style = Calm.type.caption, color = blue.copy(alpha = 0.7f))
            Spacer(Modifier.width(4.dp))
            Icon(CalmIcons.ChevronRight, contentDescription = null, tint = blue.copy(alpha = 0.7f), modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = arrow })
        }
        if (shown) Text(b.text, style = Calm.type.label, color = c.secondary, modifier = Modifier.padding(top = 6.dp))
    }
}

/** 极简 Markdown：正文 + 围栏代码块；mermaid 代码块可切「图示」（字符画，§0 Mermaid 取舍）。 */
@Composable
internal fun MarkdownLite(text: String, highlight: String = "") {
    val c = Calm.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (seg in splitFences(text)) {
            when (seg) {
                is MdSegment.Prose -> SelectionContainer {
                    val hit = highlight.isNotBlank() && seg.text.contains(highlight, ignoreCase = true)
                    Text(
                        inlineMarkdown(seg.text, c.inlineCodeBg, c.inlineCodeText, c.link),
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

/** 行内 Markdown（**粗体**、`行内代码`、标题 #）：OpenMinis 行内代码 = 浅灰底 + 橙字。 */
internal fun inlineMarkdown(src: String, codeBg: Color, codeFg: Color, link: Color): AnnotatedString = buildAnnotatedString {
    val lines = src.split('\n')
    lines.forEachIndexed { li, raw ->
        var line = raw
        val heading = Regex("^(#{1,6})\\s+").find(line)
        val headStart = length
        if (heading != null) line = line.substring(heading.value.length)
        var i = 0
        while (i < line.length) {
            when {
                line.startsWith("**", i) && line.indexOf("**", i + 2) > i -> {
                    val e = line.indexOf("**", i + 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(line.substring(i + 2, e)) }
                    i = e + 2
                }
                line[i] == '`' && line.indexOf('`', i + 1) > i -> {
                    val e = line.indexOf('`', i + 1)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = codeFg, fontSize = 14.sp)) { append(" " + line.substring(i + 1, e) + " ") }
                    i = e + 1
                }
                else -> { append(line[i]); i++ }
            }
        }
        if (heading != null) addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (20 - heading.groupValues[1].length).coerceAtLeast(16).sp), headStart, length)
        if (li < lines.lastIndex) append('\n')
    }
}

/** 代码块（OpenMinis：黑底绿字 / 深色 #262626，12dp 圆角；顶部语言名 + 复制）。 */
@Composable
private fun CodeBlock(lang: String, code: String) {
    val c = Calm.colors
    val clipboard = LocalClipboardManager.current
    var diagram by rememberSaveable(code) { mutableStateOf(false) }
    val isMermaid = lang.equals("mermaid", ignoreCase = true)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.codeBlockBg)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lang.ifBlank { "code" }, style = Calm.type.caption, color = c.codeBlockText.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
            if (isMermaid) {
                Text(
                    if (diagram) "代码" else "图示",
                    style = Calm.type.caption, color = c.codeBlockText,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { diagram = !diagram }.padding(6.dp).testTag("calm:mermaidToggle"),
                )
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.size(32.dp)) {
                Icon(CalmIcons.Copy, contentDescription = "复制代码", tint = c.codeBlockText.copy(alpha = 0.7f), modifier = Modifier.size(15.dp))
            }
        }
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            SelectionContainer {
                Text(if (isMermaid && diagram) MermaidAscii.render(code) else code, style = Calm.type.code.copy(fontSize = 13.sp, lineHeight = 19.sp), color = c.codeBlockText)
            }
        }
    }
}

/**
 * 工具胶囊（OpenMinis ToolCallPill）：36dp 高的圆角胶囊（toolCapsuleBg + 0.5dp 描边），左图标（状态色）、标题、
 * 运行中跳动三点 + 扫光、右侧等宽耗时；点按在下方展开参数与输出卡片（OpenMinis 打开详情面板，这里就地展开）。
 */
@Composable
private fun ProcessRow(host: SessionHost, item: ChatItem.Tool, expandedByDefault: Boolean) {
    val c = Calm.colors
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val shown = open || expandedByDefault
    val running = item.status == ToolStatus.RUNNING || item.status == ToolStatus.PENDING
    val tint = when (item.status) {
        ToolStatus.PENDING, ToolStatus.RUNNING -> c.accent
        ToolStatus.OK -> c.success
        ToolStatus.ERROR -> c.danger
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).animateContentSize(tween(200)).testTag("tool:${item.key}")) {
        Box(Modifier.widthIn(max = 520.dp)) {
            Row(
                Modifier.clip(CircleShape).background(c.toolCapsuleBg).border(0.5.dp, c.toolBorder, CircleShape)
                    .clickable { open = !open }
                    .padding(horizontal = 12.dp).height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(toolIcon(item.toolName), contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(toolSummary(item), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (running) { Spacer(Modifier.width(4.dp)); BouncingDots(c.text) }
                editStats(item)?.let { (add, del) ->
                    Spacer(Modifier.width(8.dp))
                    Text("+$add", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.success)
                    Text(" −$del", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.danger)
                }
                item.durationMs?.let {
                    Spacer(Modifier.width(8.dp))
                    val sec = it / 1000.0
                    Text(if (sec < 10) "%.1fs".format(sec) else "%.0fs".format(sec), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.secondary.copy(alpha = 0.6f), maxLines = 1)
                }
            }
            if (running) Shimmer(Modifier.matchParentSize().clip(CircleShape))
        }
        if (shown) {
            val out = toolOutput(item)
            if (out.isNotBlank()) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 6.dp)
                        .clip(RoundedCornerShape(12.dp)).background(c.toolCapsuleBg).border(0.5.dp, c.toolBorder, RoundedCornerShape(12.dp))
                        .padding(10.dp).horizontalScroll(rememberScrollState()),
                ) {
                    SelectionContainer { Text(out.lines().take(200).joinToString("\n"), style = Calm.type.code.copy(fontSize = 12.5.sp, lineHeight = 18.sp), color = c.text) }
                }
            }
            fullOutputPath(item)?.let { path ->
                Text(
                    "查看完整输出",
                    style = Calm.type.caption, color = c.accent,
                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable {
                        host.overlay.push(OverlayModel.FullOutput(id = "fullOutput", title = "完整输出", path = path))
                    }.padding(6.dp).testTag("calm:fullOutput"),
                )
            }
        }
    }
}

private fun toolIcon(name: String) = when (name) {
    "bash" -> CalmIcons.Terminal
    "read", "ls", "find", "grep" -> CalmIcons.Search
    "edit", "write" -> CalmIcons.Pencil
    else -> CalmIcons.Wrench
}

/** 运行中工具胶囊上的扫光（OpenMinis 1.4s 周期、60% 宽白色渐变带）。 */
@Composable
private fun Shimmer(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "shimmerPhase")
    Box(
        modifier.graphicsLayer { translationX = (-1f + phase * 3f) * size.width * 0.6f }
            .drawWithCache {
                val band = size.width * 0.6f
                val brush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0f)), start = Offset.Zero, end = Offset(band, 0f))
                onDrawBehind { drawRect(brush, size = Size(band, size.height)) }
            },
    )
}

/** 压缩卡、分支摘要卡（SummaryCard）与其他提示（OpenMinis：居中细分隔线 + 文案）。 */
@Composable
private fun NoticeRow(item: ChatItem.Notice, modifier: Modifier = Modifier) {
    val c = Calm.colors
    if (item.kind == NoticeKind.COMPACTION) {
        var open by rememberSaveable(item.key) { mutableStateOf(false) }
        Column(
            modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(c.toolCapsuleBg)
                .clickable { open = !open }.animateContentSize(tween(200)).padding(12.dp).testTag("calm:summaryCard"),
        ) {
            Text(item.text.lineSequence().firstOrNull().orEmpty(), style = Calm.type.label, color = c.secondary)
            if (open) Text(item.text, style = Calm.type.label, color = c.text, modifier = Modifier.padding(top = 6.dp))
        }
        return
    }
    if (item.kind == NoticeKind.ERROR) {
        Box(modifier.padding(vertical = 4.dp)) { InlineErrorBanner(item.text) }
        return
    }
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(0.5.dp).background(c.stroke))
        Text(item.text, style = Calm.type.caption, color = c.secondary, modifier = Modifier.padding(horizontal = 10.dp).widthIn(max = 280.dp), maxLines = 3)
        Box(Modifier.weight(1f).height(0.5.dp).background(c.stroke))
    }
}

@Composable
private fun LocalBlockView(block: LocalBlock, modifier: Modifier = Modifier) {
    val c = Calm.colors
    Column(
        modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(c.toolCapsuleBg)
            .border(0.5.dp, c.toolBorder, RoundedCornerShape(12.dp)).padding(12.dp).testTag("local:${block.title}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(CalmIcons.Info, contentDescription = null, tint = c.accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(block.title.removePrefix("explain:").let { if (block.title.startsWith("explain:")) "说明" else it }, style = Calm.type.caption.copy(fontWeight = FontWeight.Medium), color = c.secondary)
        }
        Spacer(Modifier.size(6.dp))
        SelectionContainer { Text(block.text, style = Calm.type.label, color = c.text) }
    }
}
