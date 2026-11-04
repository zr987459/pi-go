// 输入条照 dsh-Go（https://github.com/ccch1mneyyy/dsh-Go，AGPL-3.0）app/lib/ui/chat/composer.dart、
// widgets/attachments.dart（AttachButton）、empty_actions.dart（QuietChip）逐项翻译：
// Chrome 层玻璃 + 28dp 圆角，40dp 圆键、8dp 键距，正文 bodyLarge/1.45，最多 6 行；
// 发送键 发送/停止/麦克风 三态，按下缩到 0.9。见 THIRD_PARTY_NOTICES.md §8。
package io.github.zr987459.pigo.feature.chat.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.RetryBanner
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.core.ui.AppIcon
import io.github.zr987459.pigo.core.ui.AppIcons
import io.github.zr987459.pigo.core.ui.AppMotion
import io.github.zr987459.pigo.core.ui.AppShape
import io.github.zr987459.pigo.core.ui.DshTheme
import io.github.zr987459.pigo.core.ui.GlassSurface
import io.github.zr987459.pigo.core.ui.GlassTier
import io.github.zr987459.pigo.core.ui.Glyph
import io.github.zr987459.pigo.feature.chat.logic.ComposerState
import io.github.zr987459.pigo.feature.chat.logic.PrimaryAction

private val KeySize = 40.dp
private val KeyGap = 8.dp
private const val LineHeight = 1.45f
private const val MaxLines = 6

/** 空状态/输入框上方的「安静胶囊」（dsh-Go `QuietChip`）：淡底、无阴影、36dp 高。 */
@Composable
fun QuietChip(
    label: String,
    modifier: Modifier = Modifier,
    icon: Glyph? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    maxLabelWidth: androidx.compose.ui.unit.Dp = 260.dp,
) {
    val dark = DshTheme.isDark
    val fill = if (dark) Color.White.copy(alpha = 0.07f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) AppMotion.PressScale else 1f, tween(if (pressed) AppMotion.Press else 200), label = "press")
    Row(
        modifier
            .scale(scale)
            .clip(AppShape.Pill)
            .background(fill)
            .then(if (onClick != null) Modifier.clickable(interaction, ripple(), enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 36.dp)
            .padding(start = if (icon != null) 13.dp else 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AppIcon(icon, null, size = 16.dp, tint = muted)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = maxLabelWidth),
        )
    }
}

/** 输入框上方的胶囊区：扩展状态行、待插话/待排队（以 `queue_update` 为准）、已撤回 n 条。 */
@Composable
fun QueueAndStatus(
    queue: QueueSnapshot,
    withdrawn: List<String>,
    statusLines: List<String>,
    onWithdrawnClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = !(queue.isEmpty && withdrawn.isEmpty() && statusLines.isEmpty())
    AnimatedVisibility(visible, enter = expandVertically(tween(AppMotion.Collapse)) + fadeIn(), exit = shrinkVertically(tween(AppMotion.Collapse)) + fadeOut()) {
        Column(modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            statusLines.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = DshTheme.colors.textFaint,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp).testTag("chat:status"),
                )
            }
            if (!queue.isEmpty || withdrawn.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    queue.steering.forEach { t -> QuietChip("待插话：$t", Modifier.testTag("chip:steer"), icon = AppIcons.send, maxLabelWidth = 220.dp) }
                    queue.followUp.forEach { t -> QuietChip("待排队：$t", Modifier.testTag("chip:followUp"), icon = AppIcons.history, maxLabelWidth = 220.dp) }
                    if (withdrawn.isNotEmpty()) {
                        QuietChip(
                            "已撤回 ${withdrawn.size} 条 · 点此处理",
                            Modifier.testTag("chip:withdrawn"),
                            icon = AppIcons.retry,
                            onClick = onWithdrawnClick,
                        )
                    }
                }
            }
        }
    }
}

/** 自动重试提示（§3.5 auto_retry_*）：浮在输入条上方的 Chrome 胶囊，警告色字。 */
@Composable
fun RetryBar(retry: RetryBanner, onStop: () -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("chat:retry"), GlassTier.Chrome, AppShape.Lg) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.8.dp, color = DshTheme.colors.warning)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "模型请求失败，正在第 ${retry.attempt}/${retry.maxAttempts} 次重试…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DshTheme.colors.warning,
                )
                retry.errorMessage?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = DshTheme.colors.textFaint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // 契约暂无 abortRetry()（§3.5 abort_retry），这里走的是 stop()：clear_queue + abort，会一并撤回排队消息，文案写明。
            TextButton(onClick = onStop, modifier = Modifier.testTag("retry:stop")) { Text("停止并撤回排队") }
        }
    }
}

/**
 * 输入条（dsh-Go `Composer`，§3.7 语义不变）：空闲=发送；生成中有字=排队（长按选插话）；生成中无字=停止。
 *
 * @param connected 引擎可用（Idle/Running）。不可用时占位文字为「连接引擎后即可发送」。
 * @param onAttachClick 「+」键；为 null 时按钮可见但点了提示「暂不支持」（附件功能由功能工作流接入）。
 * @param onVoiceClick 麦克风键（输入框空且空闲时出现）；为 null 时点了提示「暂不支持语音输入」。
 * @param onUnsupported 显示「暂不支持」类提示（Snackbar）。
 * @param contextPercent 上下文占用；非 null 时在发送键左边画 dsh-Go 的上下文小环。
 */
@Deprecated("r3: r3/calm 删除（DESIGN-r3 X13，改用 ui/calm/CalmSurface；待 r3/app 去掉引用后删文件）")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposerBar(
    composer: ComposerState,
    primary: PrimaryAction,
    running: Boolean,
    onDraftChange: (String) -> Unit,
    onSubmit: (SendMode?) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    connected: Boolean = true,
    contextPercent: Double? = null,
    onAttachClick: (() -> Unit)? = null,
    onVoiceClick: (() -> Unit)? = null,
    onUnsupported: (String) -> Unit = {},
    /** 点上下文小环：打开用量弹层（W4 U1–U3，W5 接线）；为 null 时只显示不响应。 */
    onContextClick: (() -> Unit)? = null,
) {
    // 光标位置留在界面里；草稿文本同步到控制器（旋转、切页都不丢）。只有程序改写草稿（revision 变化）时才回写输入框。
    var field by remember { mutableStateOf(TextFieldValue(composer.draft, TextRange(composer.draft.length))) }
    LaunchedEffect(composer.revision) {
        if (composer.draft != field.text) field = TextFieldValue(composer.draft, TextRange(composer.draft.length))
    }
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Column(modifier.fillMaxWidth()) {
        // 停止中 / 生成中的提示行（dsh-Go 把这类提示放在输入条上沿的胶囊里）。
        AnimatedVisibility(composer.stopping || (running && composer.draft.isNotBlank())) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (composer.stopping) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.8.dp, color = colors.primary)
                    Text(
                        if (composer.stopSlow) "仍在停止，正在强制结束…" else "正在停止…",
                        style = MaterialTheme.typography.labelMedium,
                        color = DshTheme.colors.textFaint,
                        modifier = Modifier.testTag("chat:stopping"),
                    )
                } else {
                    Text(
                        "生成中 · 点「排队」本轮结束后再发；长按可「插话」",
                        style = MaterialTheme.typography.labelSmall,
                        color = DshTheme.colors.textFaint,
                        modifier = Modifier.weight(1f),
                    )
                    // 输入框有字时发送键是「排队」，这里保留一个停止入口；草稿不会被撤回的消息覆盖。
                    QuietChip("停止", Modifier.testTag("chat:stopAlt"), icon = AppIcons.stopGenerating, onClick = onStop)
                }
            }
        }

        GlassSurface(Modifier.fillMaxWidth(), GlassTier.Chrome, AppShape.Xl) {
            Row(
                Modifier.padding(start = KeyGap, end = KeyGap, top = KeyGap, bottom = KeyGap),
                verticalAlignment = Alignment.Bottom,
            ) {
                AttachButton(
                    enabled = connected,
                    onClick = { onAttachClick?.invoke() ?: onUnsupported("暂不支持添加附件") },
                )
                Spacer(Modifier.width(8.dp))
                val hint = when {
                    !connected -> "连接引擎后即可发送"
                    composer.stopping -> "正在停止…"
                    running -> "生成中 · 发送会排队，长按可插话"
                    else -> "给 pi 发消息…"
                }
                val textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = colors.onSurface,
                    lineHeight = MaterialTheme.typography.bodyLarge.fontSize * LineHeight,
                )
                BasicTextField(
                    value = field,
                    onValueChange = {
                        val changed = it.text != field.text
                        field = it
                        if (changed) onDraftChange(it.text)
                    },
                    textStyle = textStyle,
                    cursorBrush = SolidColor(colors.primary),
                    maxLines = MaxLines,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f).heightIn(min = KeySize).testTag("chat:input"),
                    decorationBox = { inner ->
                        Box(Modifier.heightIn(min = KeySize).padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                            if (field.text.isEmpty()) {
                                Text(
                                    hint,
                                    style = textStyle.copy(color = DshTheme.colors.textFaint),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                if (contextPercent != null) {
                    ContextRing(
                        contextPercent,
                        Modifier
                            .align(Alignment.CenterVertically)
                            .then(if (onContextClick != null) Modifier.clip(CircleShape).clickable(onClick = onContextClick).testTag("chat:usage") else Modifier)
                            .padding(end = 6.dp),
                    )
                }
                Box {
                    val mode = when {
                        primary == PrimaryAction.STOP -> KeyMode.Stop
                        primary == PrimaryAction.QUEUE -> KeyMode.Queue
                        primary == PrimaryAction.DISABLED && !composer.stopping && !running && composer.draft.isBlank() -> KeyMode.Mic
                        else -> KeyMode.Send
                    }
                    SendKey(
                        mode = mode,
                        enabled = when (mode) {
                            KeyMode.Mic -> true
                            else -> primary != PrimaryAction.DISABLED
                        },
                        busy = composer.stopping,
                        onClick = {
                            when (mode) {
                                KeyMode.Mic -> onVoiceClick?.invoke() ?: onUnsupported("暂不支持语音输入")
                                else -> when (primary) {
                                    PrimaryAction.SEND, PrimaryAction.QUEUE -> onSubmit(null)
                                    PrimaryAction.STOP -> onStop()
                                    PrimaryAction.DISABLED -> Unit
                                }
                            }
                        },
                        onLongClick = if (primary == PrimaryAction.QUEUE) ({ menu = true }) else null,
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            leadingIcon = { AppIcon(AppIcons.send, null, size = 18.dp) },
                            text = {
                                Column {
                                    Text("插话")
                                    Text("当前工具执行完就送达", style = MaterialTheme.typography.labelSmall, color = DshTheme.colors.textFaint)
                                }
                            },
                            onClick = {
                                menu = false
                                onSubmit(SendMode.STEER)
                            },
                            modifier = Modifier.testTag("menu:steer"),
                        )
                        DropdownMenuItem(
                            leadingIcon = { AppIcon(AppIcons.history, null, size = 18.dp) },
                            text = {
                                Column {
                                    Text("排队")
                                    Text("本轮彻底结束后再发送", style = MaterialTheme.typography.labelSmall, color = DshTheme.colors.textFaint)
                                }
                            },
                            onClick = {
                                menu = false
                                onSubmit(SendMode.FOLLOW_UP)
                            },
                            modifier = Modifier.testTag("menu:followUp"),
                        )
                    }
                }
            }
        }
    }
}

/** dsh-Go `AttachButton`：40dp 圆、onSurface 6% 底、24dp「+」。 */
@Composable
private fun AttachButton(enabled: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(KeySize)
            .clip(CircleShape)
            .background(c.copy(alpha = 0.06f))
            .clickable(role = Role.Button, onClickLabel = "添加附件", onClick = onClick)
            .semantics { contentDescription = "添加附件" }
            .testTag("chat:attach"),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(AppIcons.add, null, size = 24.dp, tint = if (enabled) c else c.copy(alpha = 0.38f))
    }
}

private enum class KeyMode { Send, Queue, Stop, Mic }

/** dsh-Go `_SendButton`：40dp 圆键，发送=主色、停止=主色、麦克风=安静底；「排队」时展开成带字胶囊（pi-go 语义）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SendKey(mode: KeyMode, enabled: Boolean, busy: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val quiet = mode == KeyMode.Mic
    val active = enabled || busy
    val bg by animateColorAsState(
        when {
            quiet -> colors.onSurface.copy(alpha = 0.06f)
            active -> colors.primary
            else -> colors.onSurface.copy(alpha = 0.12f)
        },
        tween(AppMotion.Quick),
        label = "bg",
    )
    val fg = when {
        quiet -> colors.onSurface
        active -> colors.onPrimary
        else -> colors.onSurface.copy(alpha = 0.38f)
    }
    val label = when (mode) {
        KeyMode.Send -> "发送"
        KeyMode.Queue -> "排队"
        KeyMode.Stop -> if (busy) "停止中" else "停止"
        KeyMode.Mic -> "语音输入"
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(AppMotion.Press), label = "press")
    Box(
        Modifier
            .scale(scale)
            .height(KeySize)
            .defaultMinSize(minWidth = KeySize)
            .clip(AppShape.Pill)
            .background(bg)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled && !busy,
                role = Role.Button,
                onClickLabel = label,
                onLongClickLabel = if (onLongClick != null) "选择插话或排队" else null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            // 测试与读屏都按文字认这个键（「发送 / 排队 / 停止」），图标本身不带字。
            .semantics { contentDescription = label; text = AnnotatedString(label) }
            .testTag("chat:send"),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            mode to busy,
            transitionSpec = { (scaleIn(tween(AppMotion.Quick)) + fadeIn()) togetherWith (scaleOut(tween(AppMotion.Quick)) + fadeOut()) },
            label = "key",
        ) { (m, b) ->
            when {
                b -> CircularProgressIndicator(Modifier.padding(11.dp).size(18.dp), strokeWidth = 2.dp, color = fg)
                m == KeyMode.Queue -> Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(AppIcons.send, null, size = 18.dp, tint = fg)
                    Spacer(Modifier.width(4.dp))
                    Text("排队", style = MaterialTheme.typography.labelLarge, color = fg)
                }
                m == KeyMode.Stop -> AppIcon(AppIcons.stopGenerating, null, size = 20.dp, tint = fg)
                m == KeyMode.Mic -> AppIcon(AppIcons.voice, null, size = 22.dp, tint = fg)
                else -> AppIcon(AppIcons.send, null, size = 22.dp, tint = fg)
            }
        }
    }
}

/** dsh-Go `ContextRing`：上下文占用小环（24dp，2dp 描边，>80% 警告色）。 */
@Composable
private fun ContextRing(percent: Double, modifier: Modifier = Modifier) {
    val f = (percent / 100.0).coerceIn(0.0, 1.0).toFloat()
    val track = MaterialTheme.colorScheme.outlineVariant
    val color = if (f >= 0.8f) DshTheme.colors.warning else MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(24.dp)
            .semantics { contentDescription = "上下文已用 ${percent.toInt().coerceIn(0, 100)}%" }
            .drawBehind {
                val stroke = 2.dp.toPx()
                val inset = stroke / 2
                val sz = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(stroke))
                drawArc(color, -90f, 360f * f, false, Offset(inset, inset), sz, style = Stroke(stroke, cap = StrokeCap.Round))
            },
    )
}

/** 「已撤回 n 条」芯片的处理对话框：插入输入框（接在草稿后面）或丢弃。 */
@Composable
fun WithdrawnDialog(texts: List<String>, onInsert: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DshTheme.colors.sheet.fill,
        title = { Text("已撤回 ${texts.size} 条消息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("停止时这些排队/插话的消息没有发出。你正在写草稿，所以没有自动放回输入框。", style = MaterialTheme.typography.bodySmall)
                texts.forEach { Text("· $it", maxLines = 4, overflow = TextOverflow.Ellipsis) }
            }
        },
        confirmButton = { TextButton(onClick = onInsert, modifier = Modifier.testTag("withdrawn:insert")) { Text("插入输入框") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("丢弃") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
