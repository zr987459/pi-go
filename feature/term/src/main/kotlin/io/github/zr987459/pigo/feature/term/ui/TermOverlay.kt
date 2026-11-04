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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
    panels: io.github.zr987459.pigo.feature.term.TermPanelRouter,
) {
    val scope = rememberCoroutineScope()
    // 面板路由（审查第 3 轮 Important 1）：面板宿主产出的面板（按来源，见 TermPanelRouter）的选中 / 提交 / 确认发面板事件，
    // 其余面板照旧 runAction(面板 id, 值)。面板自带的键帽（keyCaps / Form.actions）一律发面板事件。
    fun pick(value: String) = panels.pick(model, value)
    val notices by panels.notices.collectAsState()
    val style = monoStyle(fontSp.sp, tokens)
    fun line(spans: List<Span>, bg: String? = null) = TermLine(listOf(Span(" ")) + spans, bg)
    fun close() = panels.close(model)

    Column(Modifier.fillMaxWidth().background(tokens.bg).testTag("overlay:${model.id}")) {
        // 顶部分隔线 + 标题（pi 选择器的 DynamicBorder）
        TermLineText(TermLine(listOf(Span("─".repeat(cols), "borderAccent"))), fontSp.sp, tokens)
        TermLineText(line(listOf(Span(model.title, "accent", bold = true))), fontSp.sp, tokens)
        // 面板宿主的提示（失败 / 校验 / 已保存）显示在面板里，面板不关、已填值不丢（审查第 3 轮 Important 2）
        notices[model.id]?.let { n ->
            Column(Modifier.testTag("overlay:${model.id}:notice")) {
                TermFormatter.wrapSpans(listOf(Span(n, "warning")), cols - 2).forEach { TermLineText(line(it), fontSp.sp, tokens) }
            }
        }
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
                                    pick(it.key)
                                }.testTag("overlay:${model.id}:item:${it.key}"),
                            )
                        }
                    }
                }
                // 面板键帽带 PANEL_CAP 前缀，与动作表键帽（selectorCaps）区分，不按 id 是否在动作表里判断；两者都带上高亮项。
                KeyCapRow(model.keyCaps.map { it.key + " " + it.label to PANEL_CAP + it.actionId } + selectorCaps(model.id, catalog), style, tokens, model.id) { id, _ ->
                    if (id.startsWith(PANEL_CAP)) panels.panelEvent(model.id, id.removePrefix(PANEL_CAP), model.selectedKey)
                    else onRun(id, model.selectedKey)
                }
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
                                // 密文字段（API Key）遮挡显示（契约 FormField.secret）
                                visualTransformation = if (f.secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                                textStyle = style.copy(color = tokens.fgOf("accent")), cursorBrush = SolidColor(tokens.fgOf("accent")),
                                modifier = Modifier.fillMaxWidth().testTag("overlay:${model.id}:field:${f.key}"),
                            )
                        }
                        f.help?.let { TermLineText(line(listOf(Span("  $it", "dim"))), fontSp.sp, tokens) }
                    }
                }
                // Form.actions（「测试」「获取模型」「账号登录」「清除」…）：面板事件，value = 表单值 JSON（契约 OverlayModel.Form.actions）
                KeyCapRow(
                    listOf("enter ${model.submitLabel ?: "保存"}" to "submit") +
                        model.actions.map { (if (it.key.isBlank()) "" else it.key + " ") + it.label to PANEL_CAP + it.actionId } +
                        selectorCaps(model.id, catalog),
                    style, tokens, model.id,
                ) { id, _ ->
                    when {
                        id == "submit" -> panels.submitForm(model)
                        id.startsWith(PANEL_CAP) -> panels.panelEvent(model.id, id.removePrefix(PANEL_CAP), TermInput.formJson(model.values))
                        else -> onRun(id, null)
                    }
                }
            }

            is OverlayModel.Text -> {
                Column(Modifier.heightIn(max = (fontSp * 1.35f * 14).dp).verticalScroll(rememberScrollState())) {
                    TermFormatter.wrapSpans(listOf(Span(model.text)), cols - 2).forEach { TermLineText(line(it), fontSp.sp, tokens) }
                }
                // 账号登录面板（ModelPanels 的 oauth:*）：「刷新」看下一步，失败时「重试」重新登录，都经面板事件回传
                val oauthCaps = TermInput.oauthCaps(model)
                KeyCapRow(oauthCaps.map { it.first to PANEL_CAP + it.second } + listOf("escape 关闭" to "close") + selectorCaps(model.id, catalog), style, tokens, model.id) { id, a ->
                    when {
                        id == "close" -> close()
                        id.startsWith(PANEL_CAP) -> panels.panelEvent(model.id, id.removePrefix(PANEL_CAP), null)
                        else -> onRun(id, a)
                    }
                }
            }

            is OverlayModel.Confirm -> {
                TermFormatter.wrapSpans(listOf(Span(model.message, if (model.dangerous) "warning" else null)), cols - 2).forEach { TermLineText(line(it), fontSp.sp, tokens) }
                KeyCapRow(listOf("enter ${model.confirmLabel}" to "confirm", "escape 取消" to "cancel"), style, tokens, model.id) { id, _ ->
                    panels.confirm(model, id == "confirm")
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
                                pick(n.id)
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

/** 面板自带键帽在 KeyCapRow 里的 id 前缀（与动作表键帽区分）。 */
private const val PANEL_CAP = "\u0000panel:"

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
fun overlayEnter(
    host: SessionHost, model: OverlayModel, scope: kotlinx.coroutines.CoroutineScope,
    panels: io.github.zr987459.pigo.feature.term.TermPanelRouter,
): Boolean {
    fun pick(v: String) = panels.pick(model, v)
    when (model) {
        is OverlayModel.SelectList -> {
            val q = model.query.lowercase()
            val visible = model.groups.flatMap { g -> g.items.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.key.lowercase().contains(q) || (it.detail?.lowercase()?.contains(q) == true) } }
                .filter { it.enabled }
            val chosen = visible.firstOrNull { it.key == model.selectedKey } ?: visible.firstOrNull() ?: return true
            host.overlay.replaceTop(model.copy(selectedKey = chosen.key))
            pick(chosen.key)
        }
        is OverlayModel.Form -> panels.submitForm(model)
        is OverlayModel.Confirm -> panels.confirm(model, true)
        // 账号登录失败时 Enter = 重试（与键帽「enter 重试」一致）；其他文字面板 Enter 关闭
        is OverlayModel.Text -> if (TermInput.oauthCaps(model).any { it.second == "retry" }) panels.panelEvent(model.id, "retry", null) else panels.close(model)
        // /tree：Enter 选中高亮节点（pi 树选择器同样）；没有高亮项时消费掉 Enter，不往草稿插换行（审查第 2 轮 Nit 3）
        is OverlayModel.Tree -> model.selectedId?.let { pick(it) }
        // 完整输出：Enter 关闭
        is OverlayModel.FullOutput -> host.overlay.pop()
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
