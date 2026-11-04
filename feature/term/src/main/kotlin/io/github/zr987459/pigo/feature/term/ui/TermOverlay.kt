package io.github.zr987459.pigo.feature.term.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zr987459.pigo.contract.actions.PiActionSpec
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.contract.session.TreeFilter
import io.github.zr987459.pigo.feature.term.TermInput
import io.github.zr987459.pigo.feature.term.format.Span
import io.github.zr987459.pigo.feature.term.format.TermFormatter
import io.github.zr987459.pigo.feature.term.format.TermLine
import io.github.zr987459.pigo.feature.term.text.CellWidth
import kotlinx.coroutines.launch

/**
 * `TermSelectList`（DESIGN-r3 §3.5）：终端形态画 overlay 栈顶的面板。与 UI 形态画的是同一个 [OverlayModel]（§5.3），
 * 搜索词、页签、滚动位置、表单已填值都写回栈顶（`replaceTop`），切换形态后原样恢复。
 * 底部提示行里的键帽可点（pi 的提示行本来就列出这些键）：面板自带的 `keyCaps` + 动作表里属于该选择器的 `TermEntry.KeyCap`。
 */
@Composable
fun TermOverlay(
    host: SessionHost,
    model: OverlayModel,
    cols: Int,
    fontSp: Float,
    tokens: PiTokens,
    catalog: List<PiActionSpec>,
    onRun: (String, String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val style = monoStyle(fontSp.sp, tokens)
    fun line(spans: List<Span>, bg: String? = null) = TermLine(listOf(Span(" ")) + spans, bg)
    fun close() { host.overlay.pop() }

    Column(Modifier.fillMaxWidth().background(tokens.bg).testTag("overlay:${model.id}")) {
        // 顶部分隔线 + 标题（pi 选择器的 DynamicBorder）
        TermLineText(TermLine(listOf(Span("─".repeat(cols), "borderAccent"))), fontSp.sp, tokens)
        TermLineText(line(listOf(Span(model.title, "accent", bold = true))), fontSp.sp, tokens)
        when (model) {
            is OverlayModel.SelectList -> {
                // 搜索框：写回栈顶，切形态不丢输了一半的词
                Row(Modifier.padding(horizontal = 6.dp)) {
                    Text("> ", style = style.copy(color = tokens.fgOf("accent")))
                    BasicTextField(
                        value = model.query,
                        onValueChange = { q -> host.overlay.replaceTop(model.copy(query = q, scrollIndex = 0)) },
                        textStyle = style, cursorBrush = SolidColor(tokens.fgOf("accent")),
                        modifier = Modifier.fillMaxWidth().testTag("overlay:${model.id}:search"),
                    )
                }
                if (model.tabs.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
                    model.tabs.forEach { t ->
                        Text(
                            " $t ", style = style.copy(color = tokens.fgOf(if (t == model.tab) "accent" else "dim")),
                            modifier = Modifier.clickable { host.overlay.replaceTop(model.copy(tab = t, scrollIndex = 0)) }.testTag("overlay:${model.id}:tab:$t"),
                        )
                    }
                }
                val q = model.query.lowercase()
                val rows = model.groups.flatMap { g ->
                    val its = g.items.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.key.lowercase().contains(q) || (it.detail?.lowercase()?.contains(q) == true) }
                    if (its.isEmpty()) emptyList() else listOfNotNull(g.title?.let { "h" to it }) + its.map { "i" to it }
                }
                val state = rememberLazyListState(model.scrollIndex)
                LaunchedEffect(state) {
                    snapshotFlow { state.firstVisibleItemIndex }.collect { i ->
                        val top = host.overlay.top
                        if (top is OverlayModel.SelectList && top.id == model.id && top.scrollIndex != i) host.overlay.replaceTop(top.copy(scrollIndex = i))
                    }
                }
                LazyColumn(state = state, modifier = Modifier.heightIn(max = (fontSp * 1.35f * 10).dp)) {
                    items(rows.size) { idx ->
                        val (kind, v) = rows[idx]
                        if (kind == "h") TermLineText(line(listOf(Span(v as String, "muted", bold = true))), fontSp.sp, tokens)
                        else {
                            val it = v as io.github.zr987459.pigo.contract.session.SelectItem
                            val selected = it.key == model.selectedKey
                            val prefix = if (selected) "→ " else "  "
                            val star = when (it.starred) { true -> "★ "; false -> "☆ "; null -> "" }
                            val toggle = when (it.toggled) { true -> "[x] "; false -> "[ ] "; null -> "" }
                            val right = it.keyHint?.let { h -> "  $h" } ?: ""
                            val label = CellWidth.truncate(prefix + star + toggle + it.label + (it.detail?.let { d -> "  $d" } ?: ""), (cols - 2 - CellWidth.width(right)).coerceAtLeast(4))
                            TermLineText(
                                line(listOf(Span(label, if (!it.enabled) "dim" else if (selected) "accent" else null), Span(right, "dim")), if (selected) "selectedBg" else null),
                                fontSp.sp, tokens,
                                Modifier.clickable(enabled = it.enabled) {
                                    host.overlay.replaceTop(model.copy(selectedKey = it.key))
                                    onRun(model.id, it.key)
                                }.testTag("overlay:${model.id}:item:${it.key}"),
                            )
                        }
                    }
                }
                KeyCapRow(model.keyCaps.map { it.key + " " + it.label to it.actionId } + selectorCaps(model.id, catalog), style, tokens, model.id, onRun)
            }

            is OverlayModel.Form -> {
                Column(Modifier.heightIn(max = (fontSp * 1.35f * 12).dp).verticalScroll(rememberScrollState())) {
                    model.fields.forEach { f ->
                        val v = model.values[f.key] ?: ""
                        Row(Modifier.padding(horizontal = 6.dp)) {
                            Text(CellWidth.truncate(f.label, cols / 2) + ": ", style = style.copy(color = tokens.fgOf(if (f.enabled) "text" else "dim")))
                            BasicTextField(
                                value = v, enabled = f.enabled,
                                onValueChange = { nv -> host.overlay.replaceTop(model.copy(values = model.values + (f.key to nv))) },
                                textStyle = style.copy(color = tokens.fgOf("accent")), cursorBrush = SolidColor(tokens.fgOf("accent")),
                                modifier = Modifier.fillMaxWidth().testTag("overlay:${model.id}:field:${f.key}"),
                            )
                        }
                        f.help?.let { TermLineText(line(listOf(Span("  $it", "dim"))), fontSp.sp, tokens) }
                    }
                }
                KeyCapRow(
                    listOf("enter ${model.submitLabel ?: "保存"}" to "submit") + selectorCaps(model.id, catalog), style, tokens, model.id,
                ) { id, _ ->
                    if (id == "submit") { val vals = model.values; close(); onRun(model.id, vals.entries.joinToString("\n") { "${it.key}=${it.value}" }) } else onRun(id, null)
                }
            }

            is OverlayModel.Text -> {
                Column(Modifier.heightIn(max = (fontSp * 1.35f * 14).dp).verticalScroll(rememberScrollState())) {
                    TermFormatter.wrapSpans(listOf(Span(model.text)), cols - 2).forEach { TermLineText(line(it), fontSp.sp, tokens) }
                }
                KeyCapRow(listOf("escape 关闭" to "close") + selectorCaps(model.id, catalog), style, tokens, model.id) { id, a -> if (id == "close") close() else onRun(id, a) }
            }

            is OverlayModel.Confirm -> {
                TermFormatter.wrapSpans(listOf(Span(model.message, if (model.dangerous) "warning" else null)), cols - 2).forEach { TermLineText(line(it), fontSp.sp, tokens) }
                KeyCapRow(listOf("enter ${model.confirmLabel}" to "confirm", "escape 取消" to "cancel"), style, tokens, model.id) { id, _ ->
                    close(); if (id == "confirm") onRun(model.id, "confirm")
                }
            }

            is OverlayModel.Tree -> {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TreeFilter.entries.forEach { f ->
                        Text(
                            " ${f.piName} ", style = style.copy(color = tokens.fgOf(if (f == model.filter) "accent" else "dim")),
                            modifier = Modifier.clickable { host.overlay.replaceTop(model.copy(filter = f)) }.testTag("overlay:${model.id}:filter:${f.piName}"),
                        )
                    }
                }
                val byParent = model.nodes.groupBy { it.parentId }
                val visible = mutableListOf<Pair<Int, io.github.zr987459.pigo.contract.session.TreeNode>>()
                // 迭代遍历（不递归，避免长会话爆栈）
                val stack = ArrayDeque<Pair<Int, io.github.zr987459.pigo.contract.session.TreeNode>>()
                (byParent[null] ?: model.nodes.filter { n -> model.nodes.none { it.id == n.parentId } }).asReversed().forEach { stack.addLast(0 to it) }
                while (stack.isNotEmpty()) {
                    val (d, n) = stack.removeLast()
                    val keep = when (model.filter) {
                        TreeFilter.DEFAULT, TreeFilter.ALL -> true
                        TreeFilter.NO_TOOLS -> n.role != "toolResult"
                        TreeFilter.USER_ONLY -> n.role == "user"
                        TreeFilter.LABELED_ONLY -> n.label != null
                    } && (model.query.isEmpty() || n.text.contains(model.query, ignoreCase = true))
                    if (keep) visible += d to n
                    if (!n.collapsed) byParent[n.id]?.asReversed()?.forEach { stack.addLast((if (keep) d + 1 else d) to it) }
                }
                LazyColumn(Modifier.heightIn(max = (fontSp * 1.35f * 12).dp)) {
                    items(visible.size) { i ->
                        val (d, n) = visible[i]
                        val sel = n.id == model.selectedId
                        val fold = if (model.nodes.any { it.parentId == n.id }) (if (n.collapsed) "▸ " else "▾ ") else "  "
                        val time = if (model.showTime && n.timestampMillis != null) java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(n.timestampMillis!!)) + " " else ""
                        val lbl = n.label?.let { "[$it] " } ?: ""
                        val roleTok = when (n.role) { "user" -> "accent"; "assistant" -> null; else -> "dim" }
                        TermLineText(
                            line(listOf(Span("  ".repeat(d.coerceAtMost(cols / 4)) + fold, "dim"), Span(time, "dim"), Span(lbl, "warning"), Span(CellWidth.truncate(n.text.replace('\n', ' '), (cols - 6 - 2 * d).coerceAtLeast(8)), roleTok), Span(if (n.isLeaf) " ←" else "", "success")), if (sel) "selectedBg" else null),
                            fontSp.sp, tokens,
                            Modifier.clickable {
                                host.overlay.replaceTop(model.copy(selectedId = n.id))
                                onRun(model.id, n.id)
                            }.testTag("overlay:${model.id}:node:${n.id}"),
                        )
                    }
                }
                KeyCapRow(
                    listOf("shift+t 时间" to "local:time") + selectorCaps(model.id, catalog), style, tokens, model.id,
                ) { id, a ->
                    when (id) {
                        "local:time" -> host.overlay.replaceTop(model.copy(showTime = !model.showTime))
                        "tree.fold" -> model.selectedId?.let { sid -> host.overlay.replaceTop(model.copy(nodes = model.nodes.map { if (it.id == sid) it.copy(collapsed = !it.collapsed) else it })) }.also { onRun(id, model.selectedId) }
                        "tree.time" -> { host.overlay.replaceTop(model.copy(showTime = !model.showTime)); onRun(id, a) }
                        "tree.filter" -> { val next = TreeFilter.entries[(model.filter.ordinal + 1) % TreeFilter.entries.size]; host.overlay.replaceTop(model.copy(filter = next)); onRun(id, next.piName) }
                        else -> onRun(id, model.selectedId)
                    }
                }
            }

            is OverlayModel.ExtDialog -> {
                val r = model.request
                r.message?.let { m -> TermFormatter.wrapSpans(listOf(Span(m)), cols - 2).forEach { TermLineText(line(it), fontSp.sp, tokens) } }
                when (r.method) {
                    "select" -> r.options.forEachIndexed { i, opt ->
                        val sel = model.value == opt
                        TermLineText(
                            line(listOf(Span((if (sel) "→ " else "  ") + opt, if (sel) "accent" else null)), if (sel) "selectedBg" else null), fontSp.sp, tokens,
                            Modifier.clickable {
                                scope.launch { host.engine.answerUi(r.id, value = opt) }
                                host.overlay.remove(model.id)
                            }.testTag("overlay:${model.id}:option:$i"),
                        )
                    }
                    "confirm" -> KeyCapRow(listOf("y 确定" to "yes", "n 取消" to "no"), style, tokens, model.id) { id, _ ->
                        scope.launch { host.engine.answerUi(r.id, confirmed = id == "yes") }
                        host.overlay.remove(model.id)
                    }
                    else -> { // input / editor
                        BasicTextField(
                            value = model.value, onValueChange = { v -> host.overlay.replaceTop(model.copy(value = v)) },
                            textStyle = style, cursorBrush = SolidColor(tokens.fgOf("accent")), singleLine = r.method == "input",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("overlay:${model.id}:input"),
                        )
                        KeyCapRow(listOf("enter 提交" to "ok", "escape 取消" to "cancel"), style, tokens, model.id) { id, _ ->
                            scope.launch { if (id == "ok") host.engine.answerUi(r.id, value = model.value) else host.engine.answerUi(r.id, cancelled = true) }
                            host.overlay.remove(model.id)
                        }
                    }
                }
            }

            is OverlayModel.FullOutput -> {
                TermLineText(line(listOf(Span("完整输出：", "muted"), Span(model.path, "accent"))), fontSp.sp, tokens)
                KeyCapRow(listOf("escape 关闭" to "close"), style, tokens, model.id) { id, _ -> if (id == "close") close() }
            }
        }
        TermLineText(TermLine(listOf(Span("─".repeat(cols), "borderAccent"))), fontSp.sp, tokens)
    }
}

private fun selectorCaps(overlayId: String, catalog: List<PiActionSpec>): List<Pair<String, String>> {
    val selector = TermInput.SELECTOR_OVERLAY.entries.firstOrNull { it.value == overlayId }?.key ?: return emptyList()
    return TermInput.keyCapsFor(selector, catalog)
}

@Composable
private fun KeyCapRow(
    caps: List<Pair<String, String>>,
    style: androidx.compose.ui.text.TextStyle,
    tokens: PiTokens,
    overlayId: String,
    onRun: (String, String?) -> Unit,
) {
    if (caps.isEmpty()) return
    val selector = TermInput.SELECTOR_OVERLAY.entries.firstOrNull { it.value == overlayId }?.key ?: overlayId
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
        caps.forEach { (label, id) ->
            Text(
                " $label ", style = style.copy(color = tokens.fgOf("dim")),
                modifier = Modifier.clickable { onRun(id, null) }.padding(vertical = 4.dp).testTag("term:keycap:$selector:$label"),
            )
        }
    }
}

/**
 * 外接键盘在 overlay 打开时按 Enter（审查第 1 轮 Nit 7）：与点「enter …」键帽 / 选中项相同。
 * 返回 true 表示已处理。扩展 `editor`（多行）对话框里 Enter 仍是换行，交给输入框。
 */
fun overlayEnter(host: SessionHost, model: OverlayModel, scope: kotlinx.coroutines.CoroutineScope, onRun: (String, String?) -> Unit): Boolean {
    when (model) {
        is OverlayModel.SelectList -> {
            val q = model.query.lowercase()
            val visible = model.groups.flatMap { g -> g.items.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.key.lowercase().contains(q) || (it.detail?.lowercase()?.contains(q) == true) } }
                .filter { it.enabled }
            val pick = visible.firstOrNull { it.key == model.selectedKey } ?: visible.firstOrNull() ?: return true
            host.overlay.replaceTop(model.copy(selectedKey = pick.key))
            onRun(model.id, pick.key)
        }
        is OverlayModel.Form -> {
            val vals = model.values
            host.overlay.pop()
            onRun(model.id, vals.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
        is OverlayModel.Confirm -> { host.overlay.pop(); onRun(model.id, "confirm") }
        is OverlayModel.Text -> host.overlay.pop()
        is OverlayModel.ExtDialog -> {
            val r = model.request
            when (r.method) {
                "select" -> {
                    val v = model.value.takeIf { it in r.options } ?: r.options.firstOrNull() ?: return true
                    scope.launch { host.engine.answerUi(r.id, value = v) }
                }
                "confirm" -> scope.launch { host.engine.answerUi(r.id, confirmed = true) }
                "input" -> { val v = model.value; scope.launch { host.engine.answerUi(r.id, value = v) } }
                else -> return false
            }
            host.overlay.remove(model.id)
        }
        else -> return false
    }
    return true
}
