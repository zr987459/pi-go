@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.zr987459.pigo.feature.chat.ui.calm

import io.github.zr987459.pigo.contract.workspace.FileMatch
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.github.zr987459.pigo.core.ui.calm.CalmDims
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.SlashCommand
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.session.BashMode
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.core.ui.calm.Calm
import io.github.zr987459.pigo.core.ui.calm.CalmIcons
import kotlinx.coroutines.launch

/** 草稿 → 输入框值（光标取 draft 的安全选区，§5.3「光标取 draft.selection」）。 */
internal fun DraftState.toFieldValue() = TextFieldValue(text, TextRange(safeSelectionStart, safeSelectionEnd))

/** 输入框值 → 草稿（只改文字与选区，其他字段保留；`!` 进入命令模式）。 */
internal fun DraftState.applyField(v: TextFieldValue): DraftState {
    bashTransition(this, v.text)?.let { return it }
    return copy(text = v.text, selectionStart = v.selection.start, selectionEnd = v.selection.end)
}

/**
 * 输入区（r5：照 OpenMinis ChatScreen 的输入卡片）：排队气泡（虚线 + 红色撤回）、重试条、斜杠 / `@` 浮层；
 * 输入卡片 = 20dp 圆角、无描边、手绘对称柔和阴影；上方附件条，中间输入框（16.5sp，⤢ 展开、× 清空），
 * 下方按钮行：＋（38dp 圆钮：图片 / 引用文件 / 运行命令）、／（38dp 圆钮：命令中心）、思考胶囊、模型胶囊 …… 上下文、发送键。
 * 发送键三态（OpenMinis 3-state Send / Enqueue / Stop）：黑 / 白圆形 ↑；生成中输入为空 = 红色圆形 ■；生成中有输入 = 引导（长按：排队 / 停止）。
 * 在输入卡片上**上滑**：有内容就发送（OpenMinis swipe-up-to-send），空输入调出历史提示。
 */
@Composable
internal fun CalmComposer(
    host: SessionHost,
    chat: ChatState,
    view: CalmViewState,
    onAction: (String, String?) -> Unit,
    fileSearch: (suspend (String) -> List<FileMatch>)? = null,
    /** 有面板 / 扩展对话框打开时：命令中心按钮停用（与顶栏一致）。 */
    locked: Boolean = false,
) {
    val c = Calm.colors
    val draft by host.draft.collectAsState()
    val history by host.promptHistory.collectAsState()
    val focus = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } } // 切换形态后重新请求 IME 焦点（§5.3）
    val key = sendKeyOf(chat, draft)
    val sendKey by rememberUpdatedState(key)

    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        QueueBar(chat, onAction)
        AnimatedVisibility(chat.retry != null, enter = fadeIn(tween(180)) + expandVertically(tween(180)), exit = fadeOut(tween(150)) + shrinkVertically(tween(150))) {
            val r = chat.retry
            if (r != null) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.toolCapsuleBg).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).testTag("calm:retryBar"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(color = c.accent, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "重试 ${r.attempt}/${r.maxAttempts}，${r.delayMs / 1000} 秒后" + (r.errorMessage?.let { "：$it" } ?: ""),
                    style = Calm.type.caption, color = c.secondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text("取消", style = Calm.type.caption.copy(fontWeight = FontWeight.Medium), color = c.accent, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onAction("retry.abort", null) }.padding(8.dp))
            }
        }
        SlashPanel(host, draft, onAction)
        AtPanel(host, draft, fileSearch)
        if (draft.recalled.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                draft.recalled.forEachIndexed { i, t ->
                    Chip("已撤回：${t.take(24)}", tag = "calm:recalled:$i") {
                        val d = host.draft.value
                        host.draft.value = d.copy(recalled = d.recalled.filterIndexed { k, _ -> k != i }).withText(if (d.text.isBlank()) t else d.text.trimEnd() + "\n" + t)
                    }
                }
            }
        }
        // 模型与思考胶囊：输入卡片上方左对齐的一行（OpenMinis 输入卡片上方的状态胶囊位置），整颗可见、可点可滑可长按
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ModelPill(chat, onAction, Modifier.weight(1f, fill = false))
            ThinkingPill(chat.thinkingLevel, onAction)
        }
        val inputBg = c.inputBg
        Column(
            Modifier.fillMaxWidth()
                .drawBehind { softCardShadow(inputBg, 20.dp.toPx()) }
                .background(inputBg, RoundedCornerShape(CalmDims.composerRadius))
                .then(if (c.isDark) Modifier.border(0.5.dp, c.toolBorder, RoundedCornerShape(CalmDims.composerRadius)) else Modifier)
                .pointerInput(history) {
                    // 上滑发送（有内容）/ 调出历史（空输入）——OpenMinis swipe-up-to-send
                    var total = 0f
                    detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                        val d = host.draft.value
                        if (total < -72f) {
                            val k = sendKey
                            when {
                                d.text.isEmpty() && d.images.isEmpty() -> onAction("editor.history", null)
                                k is SendKey.Send && k.enabled || k is SendKey.Steer -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onAction(k.actionId, null) }
                            }
                        }
                    }) { _, dy -> total += dy }
                }
                .padding(top = if (draft.images.isNotEmpty() || draft.fileRefs.isNotEmpty()) 8.dp else 4.dp)
                .testTag("calm:composer"),
        ) {
            if (draft.images.isNotEmpty() || draft.fileRefs.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    draft.images.forEachIndexed { i, img ->
                        AttachmentChip("图片", img.name ?: "图片 ${i + 1}", tag = "calm:image:$i") {
                            host.draft.value = host.draft.value.let { d -> d.copy(images = d.images.filterIndexed { k, _ -> k != i }) }
                        }
                    }
                    draft.fileRefs.forEach { f ->
                        AttachmentChip("@", f, tag = "calm:ref:$f") {
                            host.draft.value = host.draft.value.let { d -> d.copy(fileRefs = d.fileRefs - f) }
                        }
                    }
                }
            }
            Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.Top) {
                if (draft.bashMode != BashMode.NONE) {
                    Text(draft.bashMode.prefix, style = Calm.type.code, color = c.accent, modifier = Modifier.padding(end = 6.dp, top = 3.dp).testTag("calm:bashPrefix"))
                }
                Box(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    if (draft.text.isEmpty()) {
                        Text(
                            if (draft.bashMode != BashMode.NONE) "输入命令" else "给 pi 发消息（/ 命令，@ 文件，! 运行）",
                            style = Calm.type.bubble, color = c.text.copy(alpha = 0.25f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = draft.toFieldValue(),
                        onValueChange = { v ->
                            val old = host.draft.value
                            if (v.text.isEmpty() && old.text.isEmpty() && old.bashMode != BashMode.NONE && v.text == old.text) return@BasicTextField
                            host.draft.value = old.applyField(v)
                            view.historyCursor = -1
                        },
                        textStyle = Calm.type.bubble.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).focusRequester(focus)
                            // 命令模式下空输入退格退出（软键盘与外接键盘都会发 DEL 键事件）
                            .onPreviewKeyEvent { e ->
                                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Backspace) {
                                    bashBackspace(host.draft.value)?.let { host.draft.value = it; true } ?: false
                                } else false
                            }
                            .testTag("calm:input"),
                    )
                }
                if (draft.text.isNotEmpty() || draft.bashMode != BashMode.NONE) {
                    IconButton(onClick = { onAction("editor.clear", null) }, modifier = Modifier.size(30.dp).testTag("calm:clear")) {
                        Icon(CalmIcons.Cancel, contentDescription = "清空", tint = c.tertiary, modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = { onAction("editor.expand", null) }, modifier = Modifier.size(30.dp).testTag("calm:expand")) {
                    Icon(CalmIcons.Expand, contentDescription = "展开编辑", tint = c.secondary, modifier = Modifier.size(16.dp))
                }
            }
            // 按钮行（OpenMinis：＋ / ／ …… 发送；12dp 水平内边距与输入文字对齐）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlusMenu(host, onAction)
                InputCircleButton(onClick = { openCommandCenter(host) }, enabled = !locked, modifier = Modifier.testTag("calm:commands")) {
                    Text("/", fontSize = 20.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = c.secondary)
                }
                Spacer(Modifier.weight(1f))
                ContextRing(chat.contextPercent)
                SendButton(key, onAction)
            }
        }
    }
}

/** OpenMinis 输入卡片的对称柔和阴影（Modifier.shadow 只向下投影，这里手绘两层）。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.softCardShadow(bg: Color, radiusPx: Float) {
    val paint = android.graphics.Paint().apply { color = bg.toArgb(); isAntiAlias = true }
    val canvas = drawContext.canvas.nativeCanvas
    paint.setShadowLayer(6.dp.toPx(), 0f, 0f, android.graphics.Color.argb(22, 0, 0, 0))
    canvas.drawRoundRect(0f, 0f, size.width, size.height, radiusPx, radiusPx, paint)
    paint.setShadowLayer(10.dp.toPx(), 0f, 3.dp.toPx(), android.graphics.Color.argb(24, 0, 0, 0))
    canvas.drawRoundRect(0f, 0f, size.width, size.height, radiusPx, radiusPx, paint)
}

/** 38dp 圆钮（OpenMinis InputCircleButton：inputIconBg 底 + 0.5dp 描边）。 */
@Composable
internal fun InputCircleButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, onLongClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = Calm.colors
    Box(
        modifier.size(38.dp).clip(CircleShape).background(c.inputIconBg).border(0.5.dp, c.inputIconBorder, CircleShape)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 排队消息（OpenMinis 排队气泡：靠右、透明底、虚线框、灰字，右侧红色 ⊗ 撤回）。 */
@Composable
private fun QueueBar(chat: ChatState, onAction: (String, String?) -> Unit) {
    val c = Calm.colors
    val q = chat.queue
    AnimatedVisibility(!q.isEmpty, enter = fadeIn(tween(180)) + expandVertically(tween(180)), exit = fadeOut(tween(150)) + shrinkVertically(tween(150))) {
        val all = q.steering + q.followUp
        val shown = all.takeLast(3)
        Column(Modifier.fillMaxWidth().testTag("calm:queueBar"), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            shown.forEachIndexed { i, t ->
                // i 是 takeLast 之后的下标：换算回 all 里的真实下标再判断是引导还是排队（PR #31 审查 N1）
                val steering = queueLabelIsSteering(all.size, shown.size, i, q.steering.size)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (steering) "引导 · " else "排队 · ") + t,
                        style = Calm.type.label, color = c.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 300.dp).dashedBorder(c.secondary.copy(alpha = 0.5f), CalmDims.bubbleRadius)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    if (i == shown.lastIndex) {
                        // queue.dequeue → dequeueAll：全部排队消息取回输入框，文案按实际行为写（N1）
                        IconButton(onClick = { onAction("queue.dequeue", null) }, modifier = Modifier.size(44.dp).testTag("calm:dequeue")) {
                            Icon(CalmIcons.Cancel, contentDescription = "全部撤回到输入框", tint = c.danger, modifier = Modifier.size(24.dp))
                        }
                    } else Spacer(Modifier.width(44.dp))
                }
            }
            if (all.size > 3) Text("还有 ${all.size - 3} 条排队中", style = Calm.type.caption, color = c.tertiary, modifier = Modifier.padding(end = 52.dp))
        }
    }
}

/** 排队气泡第 [i] 条（显示的是 all 的最后 [shownSize] 条）是否为引导消息：all = steering + followUp。 */
internal fun queueLabelIsSteering(allSize: Int, shownSize: Int, i: Int, steeringSize: Int): Boolean = allSize - shownSize + i < steeringSize

/** 浮层卡片（斜杠 / @ 候选、搜索条）：OpenMinis 10dp 圆角 + 阴影 + 细描边。 */
internal fun Modifier.popoverCard(c: io.github.zr987459.pigo.core.ui.calm.CalmColors): Modifier =
    this.shadow(8.dp, RoundedCornerShape(12.dp)).background(c.inputBg, RoundedCornerShape(12.dp)).border(0.5.dp, c.toolBorder, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))

/** `/` 内联补全：同一张动作表按斜杠别名过滤，另列扩展命令（get_commands）。 */
@Composable
private fun SlashPanel(host: SessionHost, draft: DraftState, onAction: (String, String?) -> Unit) {
    val q = slashQuery(draft) ?: return
    val c = Calm.colors
    var ext by remember { mutableStateOf<List<SlashCommand>>(emptyList()) }
    LaunchedEffect(Unit) { (host.extras.commands() as? Outcome.Ok)?.let { ext = it.value } }
    val hits = slashCompletions(host.actions.all, q)
    val extHits = ext.filter { it.name.contains(q, ignoreCase = true) }
    if (hits.isEmpty() && extHits.isEmpty()) return
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 260.dp).popoverCard(c).testTag("calm:slashPanel"),
    ) {
        items(hits, key = { it.id }) { spec ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    host.draft.value = host.draft.value.withText("")
                    onAction(spec.id, null)
                }.padding(horizontal = 12.dp, vertical = 10.dp).testTag("slash:${spec.id}"),
            ) {
                Text("/${spec.slash.first()}", style = Calm.type.code, color = c.accent)
                Spacer(Modifier.size(12.dp))
                Text(spec.title, style = Calm.type.label, color = c.secondary)
            }
        }
        items(extHits, key = { "ext:" + it.name }) { cmd ->
            Row(
                Modifier.fillMaxWidth().clickable { host.draft.value = host.draft.value.withText("/${cmd.name} ") }.padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text("/${cmd.name}", style = Calm.type.code, color = c.text)
                Spacer(Modifier.size(12.dp))
                Text(cmd.description ?: "扩展命令", style = Calm.type.label, color = c.secondary, maxLines = 1)
            }
        }
    }
}

/**
 * `@` 文件引用面板：候选来自 [fileSearch]（core/workspace FileSearch，pi-tui 的打分与排序，最多 20 条），
 * 点候选成为 fileRefs 引用（目录带结尾 `/`）；没有候选时仍可按输入的路径引用。发送时按 pi 的 `@路径` 展开，由共享层负责。
 */
@Composable
private fun AtPanel(host: SessionHost, draft: DraftState, fileSearch: (suspend (String) -> List<FileMatch>)?) {
    val q = atQuery(draft) ?: return
    val c = Calm.colors
    var hits by remember { mutableStateOf<List<FileMatch>>(emptyList()) }
    LaunchedEffect(q, fileSearch) {
        hits = emptyList()
        if (fileSearch != null) {
            delay(AT_SEARCH_DEBOUNCE_MS)
            hits = runCatching { fileSearch(q) }.getOrDefault(emptyList())
        }
    }
    Column(
        Modifier.fillMaxWidth().popoverCard(c).testTag("calm:atPanel"),
    ) {
        if (hits.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                items(hits, key = { it.path + if (it.isDir) "/" else "" }) { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { host.draft.value = acceptAtRef(host.draft.value, atRefPath(m)) }
                            .padding(horizontal = 12.dp, vertical = 10.dp).testTag("at:${atRefPath(m)}"),
                    ) {
                        Text(atRefPath(m), style = Calm.type.code, color = c.text, maxLines = 1)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (q.isEmpty()) "输入文件名或路径" else "引用 @$q", style = Calm.type.label, color = if (q.isEmpty()) c.secondary else c.text, modifier = Modifier.weight(1f))
            if (q.isNotEmpty()) {
                Text("引用", style = Calm.type.label, color = c.accent, modifier = Modifier.clickable { host.draft.value = acceptAtRef(host.draft.value, q) }.padding(4.dp).testTag("calm:atAccept"))
            }
        }
    }
}

private const val AT_SEARCH_DEBOUNCE_MS = 120L

/**
 * 「＋」菜单里附件项的文案，与能力一致（PR #21 审查第 2 轮 Nit 3）：装配层目前只接了系统照片选择器（只能选图片）。
 * 文件附件、相机、剪贴板图片留给 PR 2。
 */
internal const val PLUS_ATTACH_LABEL = "图片"

@Composable
private fun PlusMenu(host: SessionHost, onAction: (String, String?) -> Unit) {
    val c = Calm.colors
    var open by remember { mutableStateOf(false) }
    Box {
        InputCircleButton(onClick = { open = true }, modifier = Modifier.testTag("calm:plus")) {
            Icon(CalmIcons.Plus, contentDescription = "添加", tint = c.secondary, modifier = Modifier.size(20.dp))
        }
        MinisMenu(expanded = open, onDismissRequest = { open = false }) {
            MinisMenuItem(PLUS_ATTACH_LABEL, icon = CalmIcons.Sparkle, onClick = { open = false; onAction("attach.image", null) }, modifier = Modifier.testTag("plus:attach"))
            MinisMenuItem("引用文件 @", icon = CalmIcons.Folder, onClick = {
                open = false
                val d = host.draft.value
                val t = if (d.text.isEmpty() || d.text.endsWith(" ")) d.text + "@" else d.text + " @"
                host.draft.value = d.withText(t)
            }, modifier = Modifier.testTag("plus:at"))
            MinisMenuDivider()
            MinisMenuItem("运行命令 !", icon = CalmIcons.Terminal, onClick = {
                open = false
                host.draft.value = host.draft.value.copy(bashMode = BashMode.INCLUDE)
            }, modifier = Modifier.testTag("plus:bash"))
            MinisMenuItem("运行命令（不进上下文）!!", icon = CalmIcons.Terminal, onClick = {
                open = false
                host.draft.value = host.draft.value.copy(bashMode = BashMode.EXCLUDE)
            }, modifier = Modifier.testTag("plus:bashExclude"))
        }
    }
}

/** 思考胶囊（OpenMinis ThinkingLevelPicker 的胶囊）：单击循环档位（cycle_thinking_level），长按列出档位（§6.4）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThinkingPill(level: String?, onAction: (String, String?) -> Unit) {
    val c = Calm.colors
    val on = level != null && level != "off"
    Row(
        Modifier.height(30.dp).clip(RoundedCornerShape(50)).background(if (on) c.thinking.copy(alpha = 0.12f) else c.secondary.copy(alpha = 0.10f))
            .combinedClickable(onClick = { onAction("thinking.cycle", null) }, onLongClick = { onAction("thinking.select", null) })
            .padding(horizontal = 10.dp)
            .testTag(persistentTag("thinking.select") ?: "persist:thinking.select"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CalmIcons.Lightbulb, contentDescription = null, tint = if (on) c.thinking else c.secondary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(level ?: "off", style = Calm.type.caption.copy(fontWeight = FontWeight.Medium), color = if (on) c.thinking else c.secondary, maxLines = 1)
    }
}

/** 模型胶囊：点 → 模型面板；左右滑 → 上 / 下一个模型；长按 → 设为默认（§4.3、§6.4）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelPill(chat: ChatState, onAction: (String, String?) -> Unit, modifier: Modifier = Modifier) {
    val c = Calm.colors
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(modifier) {
        Row(
            Modifier.height(30.dp).clip(RoundedCornerShape(50)).background(c.secondary.copy(alpha = 0.10f))
                .pointerInput(Unit) {
                    var total = 0f
                    detectHorizontalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                        if (total < -48f) onAction("model.cycle", "forward") else if (total > 48f) onAction("model.cycle", "backward")
                    }) { _, dx -> total += dx }
                }
                .combinedClickable(onClick = { onAction("model.select", null) }, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true })
                .padding(horizontal = 10.dp)
                .testTag(persistentTag("model.select") ?: "persist:model.select"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(CalmIcons.Sparkle, contentDescription = null, tint = c.accent, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(chat.model?.let { it.name ?: it.id } ?: "选择模型", style = Calm.type.caption.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(CalmIcons.ChevronDown, contentDescription = null, tint = c.secondary, modifier = Modifier.size(12.dp))
        }
        MinisMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MinisMenuItem("设为默认模型", icon = CalmIcons.Star, onClick = { menu = false; onAction("model.setDefault", null) }, modifier = Modifier.testTag(longPressTag("model.setDefault") ?: "longpress:model.setDefault"))
            MinisMenuItem("下一个模型", icon = CalmIcons.ChevronRight, onClick = { menu = false; onAction("model.cycle", "forward") })
        }
    }
}

/** 上下文用量环（OpenMinis ContextUsageHint：小圆环，≥ 80% 变红）。 */
@Composable
private fun ContextRing(percent: Double?) {
    val c = Calm.colors
    if (percent == null) return
    val p = (percent / 100.0).toFloat().coerceIn(0f, 1f)
    val color = if (percent >= 80) c.danger else if (percent >= 60) Color(0xFFFF9500) else c.secondary
    val track = c.secondary.copy(alpha = 0.18f)
    Box(Modifier.size(26.dp).testTag("calm:contextRing"), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
            val st = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, 0f, 360f, false, style = st)
            drawArc(color, -90f, 360f * p, false, style = st)
        }
        Text("${percent.toInt()}", fontSize = 7.sp, color = color)
    }
}

/** 发送键三态（§4.4，OpenMinis ComposerSendButton / ComposerStopButton）：↑ 发送 / ■ 停止（红色圆）/ 引导（长按：排队 / 停止）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SendButton(key: SendKey, onAction: (String, String?) -> Unit) {
    val c = Calm.colors
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val enabled = key !is SendKey.Send || key.enabled
    val bg by animateColorAsState(
        when {
            key is SendKey.Stop -> c.danger
            enabled -> c.sendButton
            else -> c.sendButtonDisabled
        }, tween(180), label = "sendBg",
    )
    val fg = if (key is SendKey.Stop) Color.White else c.background
    Box {
        Box(
            Modifier.size(38.dp).clip(CircleShape)
                .background(bg)
                .combinedClickable(
                    enabled = enabled,
                    onClick = { onAction(key.actionId, null) },
                    onLongClick = { if (key is SendKey.Steer) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true } },
                )
                .testTag(persistentTag("send") ?: "persist:send"),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(key, transitionSpec = { (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.6f)) togetherWith (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.6f)) }, label = "sendKey") { k ->
                when (k) {
                    is SendKey.Send -> if (k.bash) Text("运行", style = Calm.type.caption.copy(fontWeight = FontWeight.Bold), color = fg, modifier = Modifier.testTag("send:run"))
                    else Icon(CalmIcons.ArrowUp, contentDescription = "发送", tint = fg, modifier = Modifier.size(20.dp).testTag("send:send"))
                    is SendKey.Stop -> Icon(CalmIcons.Stop, contentDescription = "停止", tint = Color.White, modifier = Modifier.size(18.dp).testTag("send:stop"))
                    SendKey.Steer -> Text("引导", style = Calm.type.caption.copy(fontWeight = FontWeight.Bold), color = fg, modifier = Modifier.testTag("send:steer"))
                }
            }
        }
        MinisMenu(expanded = menu, onDismissRequest = { menu = false }, alignEnd = true) {
            MinisMenuItem("排队发送", icon = CalmIcons.Forum, onClick = { menu = false; onAction("send.followUp", null) }, modifier = Modifier.testTag(longPressTag("send.followUp") ?: "longpress:send.followUp"))
            MinisMenuItem("停止生成", icon = CalmIcons.Stop, destructive = true, onClick = { menu = false; onAction("abort", null) }, modifier = Modifier.testTag(longPressTag("abort") ?: "longpress:abort"))
        }
    }
}

/** 附件条目（OpenMinis AttachmentChip：圆角 8dp 灰底，左类型、右 ×）。 */
@Composable
private fun AttachmentChip(kind: String, text: String, tag: String, onRemove: () -> Unit) {
    val c = Calm.colors
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(c.toolCapsuleBg).border(0.5.dp, c.toolBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onRemove).padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(kind, style = Calm.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
        Spacer(Modifier.width(6.dp))
        Text(text, style = Calm.type.caption, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
        Spacer(Modifier.width(4.dp))
        Icon(CalmIcons.Close, contentDescription = "移除", tint = c.secondary, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun Chip(text: String, tag: String, onClick: () -> Unit) {
    val c = Calm.colors
    Text(
        text,
        style = Calm.type.caption, color = c.text, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.toolCapsuleBg).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp).testTag(tag),
    )
}

/** 搜索本会话（§4.5 TranscriptSearch，OpenMinis ChatSearchBar：灰色圆角搜索栏 + 命中数 + 完成）。 */
@Composable
internal fun TranscriptSearch(query: String, onQuery: (String) -> Unit, hits: Int, onClose: () -> Unit) {
    val c = Calm.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("calm:transcriptSearch"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).background(if (c.isDark) Color(0xFF1C1C1E) else Color(0x1F767680), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(CalmIcons.Search, contentDescription = null, tint = c.secondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("搜索本会话", style = Calm.type.label, color = c.secondary)
                BasicTextField(query, onQuery, textStyle = Calm.type.label.copy(color = c.text), singleLine = true, cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            }
            if (query.isNotBlank()) Text("$hits 条", style = Calm.type.caption, color = c.secondary, modifier = Modifier.padding(horizontal = 6.dp))
        }
        Text("完成", style = Calm.type.label.copy(fontWeight = FontWeight.Medium), color = c.accent, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).padding(horizontal = 10.dp, vertical = 8.dp))
    }
}

/** ⤢ 全屏编辑（编辑同一份草稿），OpenMinis DocumentEditorField 式整页编辑。 */
@Composable
internal fun ExpandedEditor(host: SessionHost, onClose: () -> Unit) {
    val c = Calm.colors
    val draft by host.draft.collectAsState()
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().imePadding().testTag("calm:expandedEditor")) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("calm:expandedClose")) { Icon(CalmIcons.Close, contentDescription = "收起", tint = c.text) }
            Text("编辑消息", style = Calm.type.title, color = c.text, modifier = Modifier.weight(1f))
            Text("完成", style = Calm.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.accent, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        BasicTextField(
            value = draft.toFieldValue(),
            onValueChange = { host.draft.value = host.draft.value.applyField(it) },
            textStyle = Calm.type.bubble.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
