package io.github.zr987459.pigo.feature.chat.ui.calm

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SlashCommand
import io.github.zr987459.pigo.contract.actions.PiActionSpec
import io.github.zr987459.pigo.contract.actions.UiEntry
import io.github.zr987459.pigo.contract.session.FormField
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.contract.session.TreeFilter
import io.github.zr987459.pigo.contract.settings.PiSettingType
import io.github.zr987459.pigo.core.ui.calm.Calm
import io.github.zr987459.pigo.core.ui.calm.CalmIcons
import io.github.zr987459.pigo.core.ui.calm.CommandSearch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * `CalmSheet`（§4.5）：全部 [OverlayModel] 的底部面板渲染器。模型在 `host.overlay` 栈里，
 * 改搜索词 / 页签 / 已填值一律 `replaceTop`，所以切换形态后原样恢复（§5.3）。
 * 面板根节点 testTag = `overlay:<id>`（覆盖测试的结果标记，§7.1）。
 *
 * 选择约定（与终端形态一致，由共享层的 `run` 解释）：
 * - SelectList 点某项 → `runAction(<面板 id>, <项 key>)`；点星标 → `runAction(<面板 id>, "star:<key>")`；
 * - Form 提交 → `runAction(<面板 id>, "k=v" 每行一个)`；
 * - Confirm 确定 → `runAction(<面板 id>, "confirm")`；
 * - Tree 点节点 → `runAction("session.tree", <节点 id>)`；长按 → `runAction("tree.label", <节点 id>)`。
 */
@Composable
internal fun CalmSheet(host: SessionHost, model: OverlayModel, onAction: (String, String?) -> Unit, onDismiss: () -> Unit) {
    val c = Calm.colors
    val full = model.id == COMMANDS_OVERLAY_ID || model is OverlayModel.Tree || model is OverlayModel.FullOutput
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
            ) { if (model !is OverlayModel.ExtDialog) onDismiss() },
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .then(if (full) Modifier.fillMaxHeight(0.92f) else Modifier.heightIn(max = 560.dp))
                .background(c.background, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .border(1.dp, c.stroke, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .navigationBarsPadding().imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("overlay:${model.id}"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.title, style = Calm.type.title, color = c.text, modifier = Modifier.weight(1f))
                if (model !is OverlayModel.ExtDialog) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("overlay:close")) { Icon(CalmIcons.Close, contentDescription = "关闭", tint = c.secondary) }
                }
            }
            Spacer(Modifier.size(8.dp))
            when (model) {
                is OverlayModel.SelectList ->
                    if (model.id == COMMANDS_OVERLAY_ID) CommandCenter(host, model, onAction) else SelectListView(host, model, onAction)
                is OverlayModel.Form -> FormView(host, model, onAction)
                is OverlayModel.Text -> SelectionContainer {
                    Text(model.text, style = Calm.type.label, color = c.text, modifier = Modifier.verticalScroll(rememberScrollState()).testTag("overlayText:${model.id}"))
                }
                is OverlayModel.Confirm -> ConfirmView(host, model, onAction)
                is OverlayModel.Tree -> TreeView(host, model, onAction)
                is OverlayModel.ExtDialog -> ExtDialogView(host, model)
                is OverlayModel.FullOutput -> SelectionContainer {
                    Text("完整输出保存在：\n${model.path}", style = Calm.type.code, color = c.text, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, placeholder: String, tag: String, onChange: (String) -> Unit) {
    val c = Calm.colors
    Row(
        Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(12.dp)).border(1.dp, c.stroke, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CalmIcons.Search, contentDescription = null, tint = c.secondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = Calm.type.label, color = c.secondary)
            BasicTextField(value, onChange, singleLine = true, textStyle = Calm.type.label.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth().testTag(tag))
        }
    }
}

/** ⌘ 命令中心（§4.3）：按组列出全部动作，中文 / 斜杠名 / 拼音首字母搜索，每项显示 pi 原命令名。 */
@Composable
private fun ColumnScope.CommandCenter(host: SessionHost, model: OverlayModel.SelectList, onAction: (String, String?) -> Unit) {
    val c = Calm.colors
    var ext by remember { mutableStateOf<List<SlashCommand>>(emptyList()) }
    LaunchedEffect(Unit) { (host.extras.commands() as? Outcome.Ok)?.let { ext = it.value } }
    val groups = remember(model.query, host.actions.all) { groupedCommands(host.actions.all, model.query) }
    val extHits = remember(model.query, ext) {
        CommandSearch.filter(ext, model.query) { CommandSearch.Entry(it.name, it.description ?: it.name, listOf(it.name)) }
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = model.scrollIndex)
    DisposableEffect(Unit) {
        onDispose {
            val top = host.overlay.top
            if (top is OverlayModel.SelectList && top.id == COMMANDS_OVERLAY_ID) host.overlay.replaceTop(top.copy(scrollIndex = list.firstVisibleItemIndex))
        }
    }
    SearchField(model.query, "搜索 pi 功能（中文 / 斜杠名 / 拼音首字母）", "commands:search") { q ->
        val top = host.overlay.top
        if (top is OverlayModel.SelectList && top.id == COMMANDS_OVERLAY_ID) host.overlay.replaceTop(top.copy(query = q, scrollIndex = 0))
    }
    Spacer(Modifier.size(8.dp))
    LazyColumn(state = list, modifier = Modifier.weight(1f, fill = false).fillMaxWidth().testTag("commands:list")) {
        for ((name, specs) in groups) {
            item(key = "g:$name") { Text(name, style = Calm.type.caption, color = c.secondary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            items(specs, key = { it.id }) { spec -> CommandRow(spec) { host.overlay.remove(COMMANDS_OVERLAY_ID); onAction(spec.id, null) } }
        }
        if (extHits.isNotEmpty()) {
            item(key = "g:ext") { Text("扩展命令", style = Calm.type.caption, color = c.secondary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            items(extHits, key = { "ext:" + it.name }) { cmd ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        host.overlay.remove(COMMANDS_OVERLAY_ID)
                        host.draft.value = host.draft.value.withText("/${cmd.name} ")
                    }.padding(vertical = 12.dp).testTag("extcmd:${cmd.name}"),
                ) {
                    Text(cmd.description ?: cmd.name, style = Calm.type.body, color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("/${cmd.name}", style = Calm.type.code, color = c.secondary)
                }
            }
        }
        if (groups.isEmpty() && extHits.isEmpty()) {
            item { Text("没有匹配的功能", style = Calm.type.label, color = c.secondary, modifier = Modifier.padding(vertical = 16.dp)) }
        }
    }
}

@Composable
private fun CommandRow(spec: PiActionSpec, onClick: () -> Unit) {
    val c = Calm.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp).testTag("action:${spec.id}"), verticalAlignment = Alignment.CenterVertically) {
        Text(spec.title, style = Calm.type.body, color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
        val hint = when {
            spec.slash.isNotEmpty() -> spec.slash.joinToString(" ") { "/$it" }
            spec.ui is UiEntry.Explain -> "说明"
            else -> ""
        }
        if (hint.isNotEmpty()) Text(hint, style = Calm.type.code, color = c.secondary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.SelectListView(host: SessionHost, model: OverlayModel.SelectList, onAction: (String, String?) -> Unit) {
    val c = Calm.colors
    fun update(f: (OverlayModel.SelectList) -> OverlayModel.SelectList) {
        val top = host.overlay.top
        if (top is OverlayModel.SelectList && top.id == model.id) host.overlay.replaceTop(f(top))
    }
    SearchField(model.query, "搜索", "overlay:${model.id}:search") { q -> update { it.copy(query = q) } }
    if (model.tabs.isNotEmpty()) {
        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in model.tabs) {
                val sel = t == (model.tab ?: model.tabs.first())
                Text(
                    t, style = Calm.type.label, color = if (sel) c.background else c.text,
                    modifier = Modifier.background(if (sel) c.accent else c.surface, RoundedCornerShape(12.dp)).border(1.dp, c.stroke, RoundedCornerShape(12.dp))
                        .clickable { update { it.copy(tab = t, scrollIndex = 0) } }.padding(horizontal = 12.dp, vertical = 6.dp).testTag("tab:$t"),
                )
            }
        }
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = model.scrollIndex)
    DisposableEffect(Unit) { onDispose { update { it.copy(scrollIndex = list.firstVisibleItemIndex) } } }
    val q = model.query.trim()
    LazyColumn(state = list, modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(top = 8.dp)) {
        for ((gi, g) in model.groups.withIndex()) {
            val visible = g.items.filter { q.isEmpty() || CommandSearch.score(CommandSearch.Entry(it.key, it.label, listOf(it.key)), q) > 0 }
            if (visible.isEmpty()) continue
            g.title?.let { t -> item(key = "g:$gi") { Text(t, style = Calm.type.caption, color = c.secondary, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) } }
            items(visible, key = { "i:$gi:" + it.key }) { item ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = item.enabled) {
                        update { m -> m.copy(selectedKey = item.key) }
                        onAction(model.id, item.key)
                    }.padding(vertical = 10.dp).testTag("item:${item.key}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.label, style = Calm.type.body, color = if (item.enabled) c.text else c.secondary, maxLines = 1)
                        item.detail?.let { d -> Text(d, style = Calm.type.caption, color = c.secondary, maxLines = 1) }
                    }
                    if (item.key == model.selectedKey) Text("✓", style = Calm.type.label, color = c.accent)
                    item.toggled?.let { on -> Switch(checked = on, onCheckedChange = { _ -> onAction(model.id, item.key) }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent)) }
                    item.starred?.let { s ->
                        IconButton(onClick = { onAction(model.id, "star:${item.key}") }, modifier = Modifier.size(32.dp)) {
                            Icon(if (s) CalmIcons.StarFilled else CalmIcons.Star, contentDescription = "常用", tint = if (s) c.accent else c.secondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
    if (model.keyCaps.isNotEmpty()) KeyCapRow(model.keyCaps.map { it.label to it.actionId }, onAction)
}

@Composable
private fun KeyCapRow(caps: List<Pair<String, String>>, onAction: (String, String?) -> Unit) {
    val c = Calm.colors
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, id) in caps) {
            Text(
                label, style = Calm.type.label, color = c.accent,
                modifier = Modifier.border(1.dp, c.stroke, RoundedCornerShape(12.dp)).clickable { onAction(id, null) }.padding(horizontal = 12.dp, vertical = 8.dp).testTag("keycap:$id"),
            )
        }
    }
}

/** pi 设置等表单：按 schema 生成控件（开关、枚举、数字、字符串、字符串列表），不提供自由 JSON 编辑（§6.5）。 */
@Composable
private fun FormView(host: SessionHost, model: OverlayModel.Form, onAction: (String, String?) -> Unit) {
    val c = Calm.colors
    fun set(k: String, v: String) {
        val top = host.overlay.top
        if (top is OverlayModel.Form && top.id == model.id) host.overlay.replaceTop(top.copy(values = top.values + (k to v)))
    }
    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        for (f in model.fields) FieldRow(f, model.values[f.key].orEmpty()) { set(f.key, it) }
    }
    Spacer(Modifier.size(12.dp))
    Text(
        model.submitLabel ?: "保存",
        style = Calm.type.body, color = c.background,
        modifier = Modifier.fillMaxWidth().background(c.accent, RoundedCornerShape(12.dp)).clickable {
            onAction(model.id, model.values.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }.padding(vertical = 12.dp).testTag("form:submit"),
    )
}

@Composable
private fun FieldRow(f: FormField, value: String, onChange: (String) -> Unit) {
    val c = Calm.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("field:${f.key}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.label, style = Calm.type.body, color = if (f.enabled) c.text else c.secondary)
                f.help?.let { Text(it, style = Calm.type.caption, color = c.secondary) }
            }
            if (f.type is PiSettingType.Bool) {
                Switch(checked = value == "true", enabled = f.enabled, onCheckedChange = { onChange(it.toString()) }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
            }
        }
        when (val t = f.type) {
            is PiSettingType.Enum -> Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (v in t.values) {
                    val sel = v == value
                    Text(
                        v, style = Calm.type.label, color = if (sel) c.background else c.text,
                        modifier = Modifier.background(if (sel) c.accent else c.surface, RoundedCornerShape(12.dp)).border(1.dp, c.stroke, RoundedCornerShape(12.dp))
                            .clickable(enabled = f.enabled) { onChange(v) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            is PiSettingType.Number -> InlineField(value, if (t.integer) KeyboardType.Number else KeyboardType.Decimal, f.enabled, onChange = { s ->
                if (s.isEmpty() || s.toDoubleOrNull() != null || s == "-") onChange(s)
            })
            PiSettingType.Str -> InlineField(value, KeyboardType.Text, f.enabled, onChange)
            PiSettingType.StrList -> InlineField(value, KeyboardType.Text, f.enabled, onChange, hint = "多个值用逗号分隔")
            PiSettingType.Bool -> Unit
        }
    }
}

@Composable
private fun InlineField(value: String, kb: KeyboardType, enabled: Boolean, onChange: (String) -> Unit, hint: String? = null) {
    val c = Calm.colors
    Box(Modifier.fillMaxWidth().padding(top = 6.dp).background(c.surface, RoundedCornerShape(12.dp)).border(1.dp, c.stroke, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (value.isEmpty() && hint != null) Text(hint, style = Calm.type.label, color = c.secondary)
        BasicTextField(value, onChange, enabled = enabled, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = kb), textStyle = Calm.type.code.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ConfirmView(host: SessionHost, model: OverlayModel.Confirm, onAction: (String, String?) -> Unit) {
    val c = Calm.colors
    Text(model.message, style = Calm.type.body, color = c.text)
    Spacer(Modifier.size(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("取消", style = Calm.type.body, color = c.text, modifier = Modifier.weight(1f).border(1.dp, c.stroke, RoundedCornerShape(12.dp)).clickable { host.overlay.remove(model.id) }.padding(12.dp).testTag("confirm:cancel"))
        Text(
            model.confirmLabel, style = Calm.type.body, color = c.background,
            modifier = Modifier.weight(1f).background(if (model.dangerous) c.error else c.accent, RoundedCornerShape(12.dp))
                .clickable { host.overlay.remove(model.id); onAction(model.id, "confirm") }.padding(12.dp).testTag("confirm:ok"),
        )
    }
}

/** 分支树（§4、清单 §5）：筛选胶囊、三角折叠、长按标签、显示时间开关。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.TreeView(host: SessionHost, model: OverlayModel.Tree, onAction: (String, String?) -> Unit) {
    val c = Calm.colors
    fun update(f: (OverlayModel.Tree) -> OverlayModel.Tree) {
        val top = host.overlay.top
        if (top is OverlayModel.Tree && top.id == model.id) host.overlay.replaceTop(f(top))
    }
    SearchField(model.query, "搜索分支", "tree:search") { q -> update { it.copy(query = q) } }
    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (f in TreeFilter.entries) {
            val sel = f == model.filter
            Text(
                f.piName, style = Calm.type.caption, color = if (sel) c.background else c.text,
                modifier = Modifier.background(if (sel) c.accent else c.surface, RoundedCornerShape(12.dp)).border(1.dp, c.stroke, RoundedCornerShape(12.dp))
                    .clickable { update { it.copy(filter = f) }; onAction("tree.filter", f.piName) }.padding(horizontal = 10.dp, vertical = 6.dp).testTag("treeFilter:${f.piName}"),
            )
        }
        Text("时间", style = Calm.type.caption, color = c.secondary, modifier = Modifier.padding(start = 8.dp))
        Switch(checked = model.showTime, onCheckedChange = { on -> update { it.copy(showTime = on) }; onAction("tree.time", on.toString()) }, modifier = Modifier.testTag("tree:time"), colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
    }
    val q = model.query.trim()
    val rows = visibleTree(model.nodes).filter { (n, _) -> q.isEmpty() || n.text.contains(q, ignoreCase = true) || (n.label?.contains(q, ignoreCase = true) == true) }
    LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().padding(top = 8.dp)) {
        items(rows, key = { it.first.id }) { (n, depth) ->
            val hasChildren = model.nodes.any { it.parentId == n.id }
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(onClick = { onAction("session.tree", n.id) }, onLongClick = { onAction("tree.label", n.id) })
                    .padding(start = (depth * 12).coerceAtMost(240).dp, top = 6.dp, bottom = 6.dp)
                    .testTag("treeNode:${n.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (!hasChildren) "  " else if (n.collapsed) "▸" else "▾",
                    style = Calm.type.code, color = c.secondary,
                    modifier = Modifier.clickable(enabled = hasChildren) {
                        update { m -> m.copy(nodes = m.nodes.map { if (it.id == n.id) it.copy(collapsed = !it.collapsed) else it }) }
                        onAction("tree.fold", n.id)
                    }.padding(end = 6.dp),
                )
                Text(if (n.role == "user") "›" else "·", style = Calm.type.code, color = if (n.id == model.selectedId) c.accent else c.secondary)
                Spacer(Modifier.width(6.dp))
                Text(n.text, style = Calm.type.label, color = if (n.isLeaf) c.accent else c.text, maxLines = 1, modifier = Modifier.weight(1f))
                n.label?.let { Text("[$it]", style = Calm.type.caption, color = c.accent, modifier = Modifier.padding(start = 6.dp)) }
                if (model.showTime) n.timestampMillis?.let { Text(timeOf(it), style = Calm.type.caption, color = c.secondary, modifier = Modifier.padding(start = 6.dp)) }
            }
        }
    }
}

private fun timeOf(ms: Long): String {
    val f = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return f.format(java.util.Date(ms))
}

/**
 * 扩展阻塞对话框（select / confirm / input / editor）：应答发回同一 request id；
 * 计时按 [OverlayModel.ExtDialog.shownAtMillis] 算，切换形态不重置、不回 cancelled（§5.3）。
 */
@Composable
private fun ExtDialogView(host: SessionHost, model: OverlayModel.ExtDialog) {
    val c = Calm.colors
    val scope = rememberCoroutineScope()
    val req = model.request
    fun answer(value: String? = null, confirmed: Boolean? = null, cancelled: Boolean = false) {
        host.overlay.remove(model.id)
        scope.launch { host.engine.answerUi(req.id, value = value, confirmed = confirmed, cancelled = cancelled) }
    }
    req.timeoutMs?.let { t ->
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(model.id) { while (true) { delay(500); now = System.currentTimeMillis() } }
        val left = if (model.shownAtMillis > 0) ((model.shownAtMillis + t - now) / 1000).coerceAtLeast(0) else t / 1000
        Text("$left 秒后自动取消", style = Calm.type.caption, color = c.secondary, modifier = Modifier.testTag("ext:timer"))
    }
    req.message?.let { Text(it, style = Calm.type.body, color = c.text, modifier = Modifier.padding(vertical = 6.dp)) }
    when (req.method) {
        "select" -> Column {
            for (o in req.options) {
                Text(o, style = Calm.type.body, color = c.text, modifier = Modifier.fillMaxWidth().clickable { answer(value = o) }.padding(vertical = 12.dp).testTag("ext:option:$o"))
            }
        }
        "input", "editor" -> {
            Box(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(12.dp)).border(1.dp, c.stroke, RoundedCornerShape(12.dp)).padding(12.dp)) {
                if (model.value.isEmpty()) Text(req.placeholder ?: "", style = Calm.type.body, color = c.secondary)
                BasicTextField(
                    model.value,
                    { v -> val top = host.overlay.top; if (top is OverlayModel.ExtDialog && top.id == model.id) host.overlay.replaceTop(top.copy(value = v)) },
                    singleLine = req.method == "input",
                    minLines = if (req.method == "editor") 6 else 1,
                    textStyle = (if (req.method == "editor") Calm.type.code else Calm.type.body).copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().testTag("ext:input"),
                )
            }
        }
        else -> Unit
    }
    Spacer(Modifier.size(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("取消", style = Calm.type.body, color = c.text, modifier = Modifier.weight(1f).border(1.dp, c.stroke, RoundedCornerShape(12.dp)).clickable { answer(cancelled = true) }.padding(12.dp).testTag("ext:cancel"))
        if (req.method != "select") {
            Text(
                "确定", style = Calm.type.body, color = c.background,
                modifier = Modifier.weight(1f).background(c.accent, RoundedCornerShape(12.dp)).clickable {
                    if (req.method == "confirm") answer(confirmed = true) else answer(value = model.value)
                }.padding(12.dp).testTag("ext:ok"),
            )
        }
    }
}
