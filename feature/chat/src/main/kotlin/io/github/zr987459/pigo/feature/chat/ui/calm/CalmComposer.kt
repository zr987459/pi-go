package io.github.zr987459.pigo.feature.chat.ui.calm

import io.github.zr987459.pigo.contract.workspace.FileMatch
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedContent
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
 * 输入区（§4.3）：队列条、重试条、斜杠 / `@` 面板、共享草稿输入框（⤢ 展开、× 清空）、
 * ＋ 附件 / 引用文件 / 运行命令、思考胶囊、模型胶囊（左右滑循环）、上下文环、发送键三态。
 */
@Composable
internal fun CalmComposer(
    host: SessionHost,
    chat: ChatState,
    view: CalmViewState,
    onAction: (String, String?) -> Unit,
    fileSearch: (suspend (String) -> List<FileMatch>)? = null,
) {
    val c = Calm.colors
    val draft by host.draft.collectAsState()
    val history by host.promptHistory.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } } // 切换形态后重新请求 IME 焦点（§5.3）

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        QueueBar(chat, onAction)
        chat.retry?.let { r ->
            Row(Modifier.fillMaxWidth().testTag("calm:retryBar"), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "重试 ${r.attempt}/${r.maxAttempts}，${r.delayMs / 1000} 秒后" + (r.errorMessage?.let { "：$it" } ?: ""),
                    style = Calm.type.caption, color = c.secondary, modifier = Modifier.weight(1f), maxLines = 1,
                )
                Text("取消", style = Calm.type.caption, color = c.accent, modifier = Modifier.clickable { onAction("retry.abort", null) }.padding(6.dp))
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
        Column(
            Modifier.fillMaxWidth()
                .background(c.surface, RoundedCornerShape(20.dp))
                .border(1.dp, c.stroke, RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("calm:composer"),
        ) {
            if (draft.images.isNotEmpty() || draft.fileRefs.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    draft.images.forEachIndexed { i, img ->
                        Chip("[img] ${img.name ?: "图片 ${i + 1}"}  ×", tag = "calm:image:$i") {
                            host.draft.value = host.draft.value.let { d -> d.copy(images = d.images.filterIndexed { k, _ -> k != i }) }
                        }
                    }
                    draft.fileRefs.forEach { f ->
                        Chip("@$f  ×", tag = "calm:ref:$f") {
                            host.draft.value = host.draft.value.let { d -> d.copy(fileRefs = d.fileRefs - f) }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                if (draft.bashMode != BashMode.NONE) {
                    Text(draft.bashMode.prefix, style = Calm.type.code, color = c.accent, modifier = Modifier.padding(end = 6.dp, top = 2.dp).testTag("calm:bashPrefix"))
                }
                Box(
                    Modifier.weight(1f).pointerInput(history) {
                        // 空输入上滑：历史提示（清单 §9）
                        var total = 0f
                        detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                            if (total < -40f && host.draft.value.text.isEmpty()) onAction("editor.history", null)
                        }) { _, dy -> total += dy }
                    },
                ) {
                    if (draft.text.isEmpty()) {
                        Text(
                            if (draft.bashMode != BashMode.NONE) "输入命令" else "输入消息，/ 命令，@ 文件，! 运行",
                            style = Calm.type.body, color = c.secondary,
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
                        textStyle = Calm.type.body.copy(color = c.text),
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
                    IconButton(onClick = { onAction("editor.clear", null) }, modifier = Modifier.size(28.dp).testTag("calm:clear")) {
                        Icon(CalmIcons.Close, contentDescription = "清空", tint = c.secondary, modifier = Modifier.size(16.dp))
                    }
                }
                IconButton(onClick = { onAction("editor.expand", null) }, modifier = Modifier.size(28.dp).testTag("calm:expand")) {
                    Icon(CalmIcons.Expand, contentDescription = "展开编辑", tint = c.secondary, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlusMenu(host, onAction)
                ThinkingPill(chat.thinkingLevel, onAction)
                ModelPill(chat, onAction, Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                ContextRing(chat.contextPercent)
                SendButton(sendKeyOf(chat, draft), onAction)
            }
        }
    }
}

@Composable
private fun QueueBar(chat: ChatState, onAction: (String, String?) -> Unit) {
    val c = Calm.colors
    val q = chat.queue
    if (q.isEmpty) return
    val all = q.steering + q.followUp
    Row(Modifier.fillMaxWidth().testTag("calm:queueBar"), verticalAlignment = Alignment.CenterVertically) {
        Text("排队 ${all.size} 条：${all.first().take(30)}", style = Calm.type.caption, color = c.secondary, maxLines = 1, modifier = Modifier.weight(1f))
        Text("撤回", style = Calm.type.caption, color = c.accent, modifier = Modifier.clickable { onAction("queue.dequeue", null) }.padding(6.dp).testTag("calm:dequeue"))
    }
}

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
        Modifier.fillMaxWidth().heightIn(max = 240.dp).background(c.surface, RoundedCornerShape(12.dp)).border(1.dp, c.stroke, RoundedCornerShape(12.dp)).testTag("calm:slashPanel"),
    ) {
        items(hits, key = { it.id }) { spec ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    host.draft.value = host.draft.value.withText("")
                    onAction(spec.id, null)
                }.padding(horizontal = 12.dp, vertical = 10.dp).testTag("slash:${spec.id}"),
            ) {
                Text("/${spec.slash.first()}", style = Calm.type.code, color = c.text)
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
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(12.dp)).border(1.dp, c.stroke, RoundedCornerShape(12.dp)).testTag("calm:atPanel"),
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
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp).testTag("calm:plus")) {
            Icon(CalmIcons.Plus, contentDescription = "添加", tint = c.text, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(PLUS_ATTACH_LABEL) }, onClick = { open = false; onAction("attach.image", null) }, modifier = Modifier.testTag("plus:attach"))
            DropdownMenuItem(text = { Text("引用文件 @") }, onClick = {
                open = false
                val d = host.draft.value
                val t = if (d.text.isEmpty() || d.text.endsWith(" ")) d.text + "@" else d.text + " @"
                host.draft.value = d.withText(t)
            }, modifier = Modifier.testTag("plus:at"))
            DropdownMenuItem(text = { Text("运行命令 !") }, onClick = {
                open = false
                host.draft.value = host.draft.value.copy(bashMode = BashMode.INCLUDE)
            }, modifier = Modifier.testTag("plus:bash"))
            DropdownMenuItem(text = { Text("运行命令（不进上下文）!!") }, onClick = {
                open = false
                host.draft.value = host.draft.value.copy(bashMode = BashMode.EXCLUDE)
            }, modifier = Modifier.testTag("plus:bashExclude"))
        }
    }
}

/** 思考胶囊：单击循环档位（cycle_thinking_level），长按列出档位（§6.4）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThinkingPill(level: String?, onAction: (String, String?) -> Unit) {
    val c = Calm.colors
    Text(
        "◐ ${level ?: "off"}",
        style = Calm.type.caption, color = c.text,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, c.stroke, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = { onAction("thinking.cycle", null) }, onLongClick = { onAction("thinking.select", null) })
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(persistentTag("thinking.select") ?: "persist:thinking.select"),
    )
}

/** 模型胶囊：点 → 模型面板；左右滑 → 上 / 下一个模型；长按 → 设为默认（§4.3、§6.4）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelPill(chat: ChatState, onAction: (String, String?) -> Unit, modifier: Modifier = Modifier) {
    val c = Calm.colors
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Text(
            chat.model?.let { it.name ?: it.id } ?: "选择模型",
            style = Calm.type.caption, color = c.text, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, c.stroke, RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    var total = 0f
                    detectHorizontalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                        if (total < -48f) onAction("model.cycle", "forward") else if (total > 48f) onAction("model.cycle", "backward")
                    }) { _, dx -> total += dx }
                }
                .combinedClickable(onClick = { onAction("model.select", null) }, onLongClick = { menu = true })
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag(persistentTag("model.select") ?: "persist:model.select"),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("设为默认模型") },
                onClick = { menu = false; onAction("model.setDefault", null) },
                modifier = Modifier.testTag(longPressTag("model.setDefault") ?: "longpress:model.setDefault"),
            )
            DropdownMenuItem(text = { Text("下一个模型") }, onClick = { menu = false; onAction("model.cycle", "forward") })
        }
    }
}

@Composable
private fun ContextRing(percent: Double?) {
    val c = Calm.colors
    if (percent == null) return
    Text("◯${percent.toInt()}%", style = Calm.type.caption, color = if (percent >= 80) c.error else c.secondary, modifier = Modifier.testTag("calm:contextRing"))
}

/** 发送键三态（§4.4）：↑ 发送 / ■ 停止 / 引导（长按：排队 / 停止）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SendButton(key: SendKey, onAction: (String, String?) -> Unit) {
    val c = Calm.colors
    var menu by remember { mutableStateOf(false) }
    val enabled = key !is SendKey.Send || key.enabled
    Box {
        Box(
            Modifier.size(36.dp).clip(CircleShape)
                .background(if (enabled) c.accent else c.stroke)
                .combinedClickable(
                    enabled = enabled,
                    onClick = { onAction(key.actionId, null) },
                    onLongClick = { if (key is SendKey.Steer) menu = true },
                )
                .testTag(persistentTag("send") ?: "persist:send"),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(key, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) }, label = "sendKey") { k ->
                when (k) {
                    is SendKey.Send -> if (k.bash) Text("运行", style = Calm.type.caption, color = c.background, modifier = Modifier.testTag("send:run"))
                    else Icon(CalmIcons.ArrowUp, contentDescription = "发送", tint = c.background, modifier = Modifier.size(20.dp).testTag("send:send"))
                    is SendKey.Stop -> Icon(CalmIcons.Stop, contentDescription = "停止", tint = c.background, modifier = Modifier.size(20.dp).testTag("send:stop"))
                    SendKey.Steer -> Text("引导", style = Calm.type.caption, color = c.background, modifier = Modifier.testTag("send:steer"))
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("排队发送") },
                onClick = { menu = false; onAction("send.followUp", null) },
                modifier = Modifier.testTag(longPressTag("send.followUp") ?: "longpress:send.followUp"),
            )
            DropdownMenuItem(
                text = { Text("停止生成") },
                onClick = { menu = false; onAction("abort", null) },
                modifier = Modifier.testTag(longPressTag("abort") ?: "longpress:abort"),
            )
        }
    }
}

@Composable
private fun Chip(text: String, tag: String, onClick: () -> Unit) {
    val c = Calm.colors
    Text(
        text,
        style = Calm.type.caption, color = c.text, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.userBlock).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp).testTag(tag),
    )
}

/** 搜索本会话（§4.5 TranscriptSearch）。 */
@Composable
internal fun TranscriptSearch(query: String, onQuery: (String) -> Unit, hits: Int, onClose: () -> Unit) {
    val c = Calm.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(c.surface, RoundedCornerShape(12.dp)).border(1.dp, c.stroke, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp).testTag("calm:transcriptSearch"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CalmIcons.Search, contentDescription = null, tint = c.secondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("搜索本会话", style = Calm.type.label, color = c.secondary)
            BasicTextField(query, onQuery, textStyle = Calm.type.label.copy(color = c.text), singleLine = true, cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
        }
        if (query.isNotBlank()) Text("$hits 条", style = Calm.type.caption, color = c.secondary, modifier = Modifier.padding(horizontal = 6.dp))
        IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) { Icon(CalmIcons.Close, contentDescription = "关闭搜索", tint = c.secondary, modifier = Modifier.size(14.dp)) }
    }
}

/** ⤢ 全屏编辑（编辑同一份草稿）。 */
@Composable
internal fun ExpandedEditor(host: SessionHost, onClose: () -> Unit) {
    val c = Calm.colors
    val draft by host.draft.collectAsState()
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().imePadding().padding(16.dp).testTag("calm:expandedEditor")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("编辑", style = Calm.type.title, color = c.text, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.testTag("calm:expandedClose")) { Icon(CalmIcons.Close, contentDescription = "收起", tint = c.text) }
        }
        BasicTextField(
            value = draft.toFieldValue(),
            onValueChange = { host.draft.value = host.draft.value.applyField(it) },
            textStyle = Calm.type.body.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxSize(),
        )
    }
}
