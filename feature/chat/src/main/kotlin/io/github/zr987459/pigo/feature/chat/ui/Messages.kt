// 消息行照 dsh-Go（https://github.com/ccch1mneyyy/dsh-Go，AGPL-3.0）逐项翻译：
// widgets/user_bubble.dart（右对齐、最宽 82%、20dp 圆角、16/8 内边距、userBubble 色）、
// widgets/stream_row.dart（过程行：24dp 前导块、8dp 间距、32dp 最小高、bodyMedium 标签 + 13sp 灰色摘要、▾ 旋转）、
// widgets/tool_row.dart + common/tool_glyph.dart（工具色块与图标、状态尾、代码井详情）、
// widgets/thinking_pill.dart、widgets/notice_row.dart。见 THIRD_PARTY_NOTICES.md §8。
package io.github.zr987459.pigo.feature.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.core.ui.AppIcon
import io.github.zr987459.pigo.core.ui.AppIcons
import io.github.zr987459.pigo.core.ui.AppMotion
import io.github.zr987459.pigo.core.ui.AppShape
import io.github.zr987459.pigo.core.ui.AppType
import io.github.zr987459.pigo.core.ui.DshTheme
import io.github.zr987459.pigo.core.ui.Glyph
import io.github.zr987459.pigo.core.ui.MonoStyle
import io.github.zr987459.pigo.feature.chat.logic.ChatRow
import io.github.zr987459.pigo.feature.chat.logic.ToolPhase
import io.github.zr987459.pigo.feature.chat.logic.ToolText
import io.github.zr987459.pigo.feature.chat.logic.formatSeconds
import kotlinx.coroutines.delay

/** 一行消息的回调集合。 */
class RowActions(
    val onRetryLocal: (String) -> Unit = {},
    val onEditLocal: (String) -> Unit = {},
    val onDeleteLocal: (String) -> Unit = {},
    val onResend: (String) -> Unit = {},
    val onToggleTurn: (String) -> Unit = {},
    val onOpenModelSettings: (() -> Unit)? = null,
)

@Composable
fun ChatRowView(row: ChatRow, isLatestUser: Boolean, actions: RowActions, modifier: Modifier = Modifier) {
    when (row) {
        is ChatRow.User -> UserBubble(row, isLatestUser, actions, modifier)
        is ChatRow.Assistant -> AssistantMessage(row, modifier)
        is ChatRow.Tool -> ToolCard(row.tool, row.phase, modifier)
        is ChatRow.Notice -> NoticeLine(row.notice, modifier)
        is ChatRow.Raw -> RawCard(row.raw, modifier)
        is ChatRow.Error -> ErrorCard(row, actions, modifier)
        is ChatRow.TurnSummary -> TurnSummaryRow(row, actions, modifier)
    }
}

// ═════════════════════ 过程行的共同骨架（dsh-Go StreamRow） ═════════════════════

private object StreamRow {
    val Lead = 24.dp
    val Gap = 8.dp
    val MinHeight = 32.dp
    val Bleed = 8.dp
}

@Composable
private fun streamLabelStyle() = MaterialTheme.typography.bodyMedium.copy(
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    lineHeight = (14 * 1.3).sp,
)

@Composable
private fun streamDetailStyle() = MaterialTheme.typography.bodySmall.copy(
    fontSize = 13.sp,
    lineHeight = (13 * 1.3).sp,
    color = DshTheme.colors.textFaint,
)

/** 一行过程：左右各外扩 8dp 的可点区域，32dp 最小高，小圆角水波纹。 */
@Composable
private fun StreamRowFrame(onClick: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp)
            .clip(AppShape.Sm)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = StreamRow.MinHeight)
            .padding(horizontal = StreamRow.Bleed, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 展开箭头：▾ 旋转 180°（280ms，easeInOutCubic）。 */
@Composable
private fun StreamChevron(open: Boolean, description: String? = null) {
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(AppMotion.Collapse), label = "chev")
    AppIcon(AppIcons.expand, description, Modifier.rotate(turn), size = 16.dp, tint = DshTheme.colors.textFaint)
}

/** 24dp 前导色块（圆角 = 尺寸 × 0.32），图标 0.6 倍。 */
@Composable
private fun LeadTile(glyph: Glyph, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(StreamRow.Lead).clip(RoundedCornerShape(StreamRow.Lead * 0.32f)).background(bg),
        contentAlignment = Alignment.Center,
    ) { AppIcon(glyph, null, size = StreamRow.Lead * 0.6f, tint = fg) }
}

@Composable
private fun Reveal(open: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        open,
        enter = expandVertically(tween(AppMotion.Collapse)) + fadeIn(tween(AppMotion.Collapse)),
        exit = shrinkVertically(tween(AppMotion.Collapse)) + fadeOut(tween(AppMotion.Quick)),
    ) { content() }
}

// ═════════════════════ 用户气泡 ═════════════════════

@Composable
private fun UserBubble(row: ChatRow.User, isLatest: Boolean, actions: RowActions, modifier: Modifier) {
    val failed = row.delivery == Delivery.FAILED
    val colors = DshTheme.colors
    BoxWithConstraints(modifier.fillMaxWidth().testTag("row:user")) {
        val maxBubble = maxWidth * 0.82f
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Box(
                Modifier
                    .widthIn(max = maxBubble)
                    .clip(AppShape.Bubble)
                    .background(if (failed) MaterialTheme.colorScheme.errorContainer else colors.userBubble)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    row.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (failed) MaterialTheme.colorScheme.onErrorContainer else colors.onUserBubble,
                )
            }
            val delivered: Pair<Glyph, String?> = when (row.delivery) {
                Delivery.SENDING -> AppIcons.history to "发送中…"
                Delivery.QUEUED -> AppIcons.history to "已排队"
                Delivery.FAILED -> AppIcons.failed to ("发送失败" + (row.error?.let { "：$it" } ?: ""))
                Delivery.SENT -> AppIcons.check to (if (row.localId != null || isLatest) "已发送" else null)
            }
            val glyph = delivered.first
            val label = delivered.second
            if (label != null) {
                val muted = if (failed) MaterialTheme.colorScheme.error else colors.textFaint
                Row(
                    Modifier.padding(top = 4.dp, end = 8.dp).testTag("delivery:${row.delivery.name}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(glyph, null, size = 12.dp, tint = muted)
                    Spacer(Modifier.width(4.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
            if (failed && row.localId != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    SmallAction("删除") { actions.onDeleteLocal(row.localId) }
                    SmallAction("改一改") { actions.onEditLocal(row.localId) }
                    SmallAction("重试", Modifier.testTag("failed:retry")) { actions.onRetryLocal(row.localId) }
                }
                val settings = actions.onOpenModelSettings
                if (row.needsSettings && settings != null) {
                    SmallAction("去设置模型", Modifier.testTag("failed:settings"), onClick = settings)
                }
            }
        }
    }
}

@Composable
private fun SmallAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier.height(32.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
    }
}

// ═════════════════════ 助手消息 ═════════════════════

@Composable
private fun AssistantMessage(row: ChatRow.Assistant, modifier: Modifier) {
    val item = row.item
    Column(modifier.fillMaxWidth().testTag("row:assistant"), verticalArrangement = Arrangement.spacedBy(AppMotionGap)) {
        var anyText = false
        for (b in item.blocks) {
            when (b) {
                is ContentBlock.Thinking -> if (row.showThinking) {
                    val ms = row.thinkingMs[b.index]
                    val active = item.streaming && ms == null && b.index == item.blocks.maxOf { it.index }
                    ThinkingBlock(b.text, active = active, durationMs = ms)
                }
                is ContentBlock.Text -> if (row.showText && b.text.isNotEmpty()) {
                    anyText = true
                    MarkdownText(b.text)
                }
                is ContentBlock.ToolCall -> Unit // 由工具行显示
            }
        }
        if (row.showText && item.streaming && !anyText && item.blocks.none { it is ContentBlock.Thinking }) {
            TypingIndicator()
        }
        if (row.showText) {
            val tag = when (item.stopReason) {
                "aborted" -> "已停止"
                "length" -> "输出被截断（达到长度上限）"
                else -> null
            }
            if (tag != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(AppIcons.stopGenerating, null, size = 12.dp, tint = DshTheme.colors.textFaint)
                    Spacer(Modifier.width(4.dp))
                    Text(tag, style = MaterialTheme.typography.labelSmall, color = DshTheme.colors.textFaint)
                }
            }
        }
    }
}

private val AppMotionGap = 6.dp

/** dsh-Go `TypingIndicator`：三个点依次起伏（「正在回复…」）。 */
@Composable
private fun TypingIndicator() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    Row(
        Modifier.defaultMinSize(minHeight = StreamRow.MinHeight).semantics { contentDescription = "正在回复…" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) { i ->
            val a by t.animateFloat(
                0.25f,
                1f,
                androidx.compose.animation.core.infiniteRepeatable(
                    tween(600, delayMillis = i * 150),
                    androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "d$i",
            )
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = a)))
        }
    }
}

/** S3：思考行（dsh-Go `ThinkingPill`）：主色淡底前导块 + 「思考中 / 思考 · N 秒」+ 一行预览 + ▾。 */
@Composable
private fun ThinkingBlock(text: String, active: Boolean, durationMs: Long?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().testTag("thinking")) {
        StreamRowFrame(onClick = { expanded = !expanded }) {
            Box(Modifier.size(StreamRow.Lead).clip(RoundedCornerShape(StreamRow.Lead * 0.32f)).background(primary.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                if (active) {
                    CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.6.dp, color = primary)
                } else {
                    AppIcon(AppIcons.generating, null, size = 15.dp, tint = primary)
                }
            }
            Spacer(Modifier.width(StreamRow.Gap))
            Text(
                when {
                    active -> "思考中"
                    durationMs != null && durationMs > 0 -> "思考 · ${formatSeconds(durationMs)}"
                    else -> "思考"
                },
                style = streamLabelStyle(),
            )
            Spacer(Modifier.width(8.dp))
            val preview = text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            Text(
                if (active && preview != null) preview else "",
                style = streamDetailStyle(),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                softWrap = false,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            StreamChevron(expanded, if (expanded) "收起思考" else "展开思考")
        }
        Reveal(expanded && text.isNotBlank()) {
            Text(
                ToolText.clip(text),
                style = streamDetailStyle().copy(fontSize = 13.sp, lineHeight = 20.sp),
                modifier = Modifier.padding(start = StreamRow.Lead + StreamRow.Gap + StreamRow.Bleed, top = 4.dp, bottom = 8.dp, end = 8.dp),
            )
        }
    }
}

// ═════════════════════ 工具行 ═════════════════════

/** dsh-Go `ToolTone` → (底色, 前景色)，浅色/深色两套，顺序 terminal, read, edit, search, web, agent, plan, neutral。 */
private enum class ToolTone { Terminal, Read, Edit, Search, Web, Agent, Plan, Neutral }

private val ToolLight = listOf(
    0xFFE6EAF2 to 0xFF343D51, 0xFFDAECFF to 0xFF173F65, 0xFFFAE6CD to 0xFF543600, 0xFFEDE5FE to 0xFF443261,
    0xFFD7F1E2 to 0xFF034A2E, 0xFFF9E2F0 to 0xFF582B4A, 0xFFD0F1F0 to 0xFF004747, 0xFFE7E9EF to 0xFF363D4C,
)
private val ToolDark = listOf(
    0xFF303540 to 0xFFD1D8E5, 0xFF21374E to 0xFFC0DBF8, 0xFF443116 to 0xFFEBD3B4, 0xFF39304B to 0xFFDCD1F3,
    0xFF1D3D2D to 0xFFBDE2CD, 0xFF452C3D to 0xFFEDCCE1, 0xFF0F3D3D to 0xFFB3E2E1, 0xFF32353D to 0xFFD3D7E1,
)

private fun toneFor(name: String): ToolTone = when (name) {
    "bash", "pwsh" -> ToolTone.Terminal
    "read", "read_image", "ls" -> ToolTone.Read
    "write", "edit", "str_replace_editor" -> ToolTone.Edit
    "grep", "glob", "find" -> ToolTone.Search
    "web_fetch", "web_search" -> ToolTone.Web
    "subagent", "skill" -> ToolTone.Agent
    "todo_write", "ask_user_question" -> ToolTone.Plan
    else -> ToolTone.Neutral
}

private fun glyphFor(name: String): Glyph = when (name) {
    "bash", "pwsh" -> AppIcons.terminal
    "read", "ls" -> AppIcons.document
    "read_image" -> AppIcons.image
    "write", "edit", "str_replace_editor" -> AppIcons.edit
    "grep", "find" -> AppIcons.search
    "glob" -> AppIcons.folderOpen
    "web_fetch" -> AppIcons.web
    "web_search" -> AppIcons.webSearch
    "subagent" -> AppIcons.subagents
    "skill" -> AppIcons.skill
    "todo_write" -> AppIcons.todo
    "ask_user_question" -> AppIcons.question
    else -> if (name.startsWith("mcp__")) AppIcons.plugin else AppIcons.tool
}

/**
 * T1/T2：运行中小环 + 计时；结束显示耗时；失败 ✕、默认展开并显示错误文本。
 * 回合已结束但工具没走到终态（[ToolPhase.NOT_RUN] / [ToolPhase.INTERRUPTED]）时不再转圈计时，显示「未执行 / 已中断」。
 * 外观是 dsh-Go 的过程行 + 代码井详情。
 */
@Composable
fun ToolCard(tool: ChatItem.Tool, phase: ToolPhase = ToolPhase.of(tool.status, live = true), modifier: Modifier = Modifier) {
    val failed = phase == ToolPhase.ERROR
    val running = phase.spinning
    val halted = phase == ToolPhase.NOT_RUN || phase == ToolPhase.INTERRUPTED
    var userExpanded by rememberSaveable(tool.key) { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: failed
    val firstSeen = remember(tool.key) { System.currentTimeMillis() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val colors = DshTheme.colors
    val (bg, fg) = (if (colors.isDark) ToolDark else ToolLight)[toneFor(tool.toolName).ordinal]
    Column(modifier.fillMaxWidth().testTag("tool:${tool.toolCallId}")) {
        StreamRowFrame(onClick = { userExpanded = !expanded }) {
            LeadTile(glyphFor(tool.toolName), Color(bg), Color(fg))
            Spacer(Modifier.width(StreamRow.Gap))
            Text(ToolText.label(tool.toolName), style = streamLabelStyle())
            Spacer(Modifier.width(8.dp))
            Text(
                ToolText.argSummary(tool) ?: tool.toolName,
                style = AppType.mono(12).copy(fontSize = 12.5.sp, lineHeight = (12.5 * 1.3).sp, color = colors.textFaint),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            when {
                running -> {
                    Text(
                        formatSeconds((now - (tool.startedAtMillis ?: firstSeen)).coerceAtLeast(0)),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textFaint,
                        modifier = Modifier.testTag("tool:time"),
                    )
                    Spacer(Modifier.width(6.dp))
                    CircularProgressIndicator(Modifier.size(14.dp).testTag("tool:spinner"), strokeWidth = 1.8.dp, color = MaterialTheme.colorScheme.primary)
                }
                failed -> AppIcon(AppIcons.failed, "失败", size = 16.dp, tint = colors.danger)
                halted -> Text(
                    ToolText.phaseLabel(phase).orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.warning,
                    modifier = Modifier.testTag("tool:halted"),
                )
                tool.durationMs != null -> Text(
                    formatDuration(tool.durationMs!!),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textFaint,
                    modifier = Modifier.testTag("tool:time").semantics { contentDescription = "完成，用时 ${formatDuration(tool.durationMs!!)}" },
                )
                // 成功但引擎没给耗时：仍显示 ✓，读屏念「完成」（旧版行为）。
                phase == ToolPhase.OK ->
                    AppIcon(AppIcons.check, "完成", size = 16.dp, tint = colors.textFaint, modifier = Modifier.testTag("tool:done"))
            }
            Spacer(Modifier.width(4.dp))
            StreamChevron(expanded, if (expanded) "收起" else "展开")
        }
        Reveal(expanded) {
            CodeWell(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                tool.args?.takeIf { it.isNotEmpty() }?.let { args ->
                    WellSection("参数") { Text(args.toString(), style = AppType.mono(12), color = MaterialTheme.colorScheme.onSurface) }
                    HorizontalDivider(thickness = 0.5.dp, color = colors.codeWellBorder)
                }
                val output = ToolText.of(tool.result) ?: ToolText.of(tool.partialResult)
                WellSection(if (failed) "错误信息" else if (running) "输出（进行中）" else "输出") {
                    Text(
                        output?.let { ToolText.clip(it) } ?: when {
                            running -> "等待输出…"
                            phase == ToolPhase.NOT_RUN -> "（这一轮已结束，工具没有执行）"
                            phase == ToolPhase.INTERRUPTED -> "（工具执行被中断，没有收到结果）"
                            else -> "（无输出）"
                        },
                        style = AppType.mono(12),
                        color = if (failed) colors.danger else MaterialTheme.colorScheme.onSurface,
                        softWrap = false,
                        modifier = Modifier.testTag("tool:output"),
                    )
                }
            }
        }
    }
}

/** 代码井：codeWell 底、0.5dp 描边、14dp 圆角。 */
@Composable
internal fun CodeWell(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = DshTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(AppShape.Md)
            .background(c.codeWell)
            .border(0.5.dp, c.codeWellBorder, AppShape.Md),
    ) { content() }
}

@Composable
private fun WellSection(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = AppType.Eyebrow.copy(fontSize = 11.sp, letterSpacing = 0.4.sp),
        color = DshTheme.colors.textFaint,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
    )
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
        content()
    }
}

private fun formatDuration(ms: Long): String =
    if (ms < 1000) "$ms 毫秒" else if (ms < 60_000) String.format(java.util.Locale.ROOT, "%.1f 秒", ms / 1000.0) else formatSeconds(ms)

// ═════════════════════ 折叠摘要 ═════════════════════

/** T3：回合结束后，思考与工具折叠成一行摘要（dsh-Go `TurnSummary`），可展开。 */
@Composable
private fun TurnSummaryRow(row: ChatRow.TurnSummary, actions: RowActions, modifier: Modifier) {
    StreamRowFrame(onClick = { actions.onToggleTurn(row.key) }, modifier.testTag("turn:summary")) {
        LeadTile(
            if (row.failedTools > 0) AppIcons.warning else AppIcons.tool,
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
            if (row.failedTools > 0) DshTheme.colors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(StreamRow.Gap))
        Text(
            row.label,
            style = streamLabelStyle().copy(color = if (row.failedTools > 0) DshTheme.colors.danger else MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        StreamChevron(row.expanded, if (row.expanded) "收起过程" else "展开过程")
    }
}

// ═════════════════════ 错误卡 / 提示行 / 原始 JSON ═════════════════════

/** S6 / §3.9：中文错误卡 + 原文可展开 +「重试」+「去设置模型」。 */
@Composable
private fun ErrorCard(row: ChatRow.Error, actions: RowActions, modifier: Modifier) {
    var showRaw by rememberSaveable(row.key) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(AppShape.Lg)
            .background(scheme.errorContainer.copy(alpha = if (DshTheme.isDark) 0.6f else 0.7f))
            .border(0.5.dp, scheme.error.copy(alpha = 0.25f), AppShape.Lg)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("row:error"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(AppIcons.warning, null, size = 18.dp, tint = scheme.error)
            Spacer(Modifier.width(8.dp))
            Text(
                row.info.title,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onErrorContainer,
                modifier = Modifier.testTag("error:title"),
            )
        }
        Text(row.info.hint, style = MaterialTheme.typography.bodyMedium, color = scheme.onErrorContainer)
        if (row.info.raw != null) {
            Reveal(showRaw) {
                CodeWell { Box(Modifier.padding(12.dp)) { Text(row.info.raw, style = AppType.mono(12), color = scheme.onSurface) } }
            }
        }
        // FlowRow：360dp 宽屏上三个胶囊放不下一行时换行，不挤窄「重试」。
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (row.info.raw != null) {
                QuietChip(if (showRaw) "收起原文" else "查看原文", onClick = { showRaw = !showRaw })
            }
            val settings = actions.onOpenModelSettings
            if (settings != null && row.info.suggestsSettings) {
                QuietChip("去设置模型", Modifier.testTag("error:settings"), icon = AppIcons.settings, onClick = settings)
            }
            if (row.retryText != null) {
                io.github.zr987459.pigo.core.ui.PrimaryPillButton(
                    "重试",
                    { actions.onResend(row.retryText) },
                    Modifier.height(36.dp).testTag("error:retry"),
                    leading = AppIcons.retry,
                )
            }
        }
    }
}

/** dsh-Go `NoticeRow`：居中灰字，两侧发丝线。 */
@Composable
private fun NoticeLine(notice: ChatItem.Notice, modifier: Modifier) {
    val color = when (notice.kind) {
        NoticeKind.ERROR -> DshTheme.colors.danger
        NoticeKind.STOPPED, NoticeKind.INTERRUPTED, NoticeKind.TRUNCATED -> DshTheme.colors.warning
        else -> DshTheme.colors.textFaint
    }
    val rule = MaterialTheme.colorScheme.outlineVariant
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("row:notice"), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), thickness = 0.5.dp, color = rule)
        Text(
            notice.text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.weight(1f), thickness = 0.5.dp, color = rule)
    }
}

/** 未知 role：可展开的原始 JSON（§3.8），代码井外观。 */
@Composable
private fun RawCard(raw: ChatItem.Raw, modifier: Modifier) {
    var expanded by rememberSaveable(raw.key) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        StreamRowFrame(onClick = { expanded = !expanded }) {
            LeadTile(AppIcons.code, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(StreamRow.Gap))
            Text(
                "未识别的消息（${raw.role ?: "未知类型"}）· 点击${if (expanded) "收起" else "查看原始内容"}",
                style = streamLabelStyle(),
                modifier = Modifier.weight(1f),
            )
            StreamChevron(expanded)
        }
        Reveal(expanded) {
            CodeWell(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                Box(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
                    Text(ToolText.clip(raw.json.toString()), style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
