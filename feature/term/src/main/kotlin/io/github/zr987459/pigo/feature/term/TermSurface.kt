package io.github.zr987459.pigo.feature.term

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SlashCommand
import io.github.zr987459.pigo.contract.session.BashMode
import io.github.zr987459.pigo.contract.session.ScrollAnchor
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.contract.session.UiForm
import io.github.zr987459.pigo.feature.term.format.Footer
import io.github.zr987459.pigo.feature.term.format.FooterInput
import io.github.zr987459.pigo.feature.term.format.FormatOptions
import io.github.zr987459.pigo.feature.term.format.Span
import io.github.zr987459.pigo.feature.term.format.TermFormatter
import io.github.zr987459.pigo.feature.term.format.TermRow
import io.github.zr987459.pigo.feature.term.format.TermRowCache
import io.github.zr987459.pigo.feature.term.format.TermLine
import io.github.zr987459.pigo.feature.term.keys.KeyBindings
import io.github.zr987459.pigo.feature.term.keys.KeyChords
import io.github.zr987459.pigo.feature.term.text.CellWidth
import io.github.zr987459.pigo.feature.term.ui.LocalPiTokens
import io.github.zr987459.pigo.feature.term.ui.PiTokens
import io.github.zr987459.pigo.feature.term.ui.TermLineText
import io.github.zr987459.pigo.feature.term.ui.TermOverlay
import io.github.zr987459.pigo.feature.term.ui.monoStyle
import io.github.zr987459.pigo.feature.term.ui.rememberPiTokens
import io.github.zr987459.pigo.feature.term.ui.spansToAnnotated
import io.github.zr987459.pigo.feature.term.ui.overlayEnter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 终端形态（DESIGN-r3 §3）：用 Compose 画 pi 的 TUI，不跑第二个 pi。
 *
 * - **零业务状态**：聊天、草稿、overlay、阅读位置、本地块都读 [host]；动作一律经 `host.runAction`（不自己压栈）。
 *   这里只有纯视图状态（字号、Ctrl+O 展开、动画帧），切换形态丢掉也不影响功能（§5.1）。
 * - 形态切换（⇄UI 键、页脚 `[UI]`、`/ui`、外接键盘 Ctrl+Alt+T）只改 `host.form`，不发任何 RPC（§5.2、§5.3）。
 *
 * @param userKeybindingsJson `~/.pi/agent/keybindings.json` 的内容（由装配层读取；null = 用 pi 默认表）。
 * @param home 引擎里的家目录（页脚 `~` 缩写）；[cwd] 工作目录（页脚第一行）；[gitBranch] 当前分支。
 */
@Composable
fun TermSurface(
    host: SessionHost,
    modifier: Modifier = Modifier,
    cwd: String = "~",
    home: String? = null,
    gitBranch: String? = null,
    piVersion: String = "1.5.0",
    userKeybindingsJson: String? = null,
    env: TermEnv = TermEnv(),
) {
    val settings by host.settings.values.collectAsState()
    val themeName = (settings["theme"] as? JsonPrimitive)?.contentOrNull
    val tokens = rememberPiTokens(themeName)
    val enabledModels = remember(settings) {
        (settings["enabledModels"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    }
    CompositionLocalProvider(LocalPiTokens provides tokens) {
        TermScreen(host, modifier, cwd, home, gitBranch, piVersion, userKeybindingsJson, settings.mapValues { (it.value as? JsonPrimitive)?.contentOrNull }, env, enabledModels)
    }
}

/**
 * 装配层（r3/app）注入的、[SessionHost] 契约里没有的终端形态数据（审查第 1 轮 Important 2、5，Nit 4）。全部有缺省值，
 * 不注入时功能退化但不出错。
 *
 * @param fileSearch `@` 文件模糊搜索：给查询词（`@` 之后的文字）返回候选，按 pi 的打分排好序。装配层用 r3/core 的
 *   `WorkspaceSearch.searchFiles(cwd, query)` 映射成 [FileMatch]；null = 只插入 `@`。
 * @param bashRunning `!` / `!!` 命令正在运行（引擎侧 `bash` 命令在途）；Esc 据此选 `bash.abort`。
 *   终端形态自己发出的 `!` 命令在 `runAction("send")` 返回前也视为运行中，未注入时也能停。
 * @param contextFiles 已加载的上下文文件（AGENTS.md / CLAUDE.md / SYSTEM.md 等，容器内路径），页眉 `[Context]` 段。
 * @param availableProviderCount 已配置的服务商数（页脚 `(provider)` 前缀只在多于 1 个时显示，同 pi）。
 * @param latestCacheHitRate 最后一条助手消息的缓存命中率（%），页脚 `CH%`。
 */
/** `@` 候选（与 r3/core `contract.workspace.FileMatch` 同义的最小子集，终端形态不依赖其契约版本）。 */
data class FileMatch(val path: String, val isDir: Boolean, val completion: String)

data class TermEnv(
    val fileSearch: (suspend (String) -> List<FileMatch>)? = null,
    val bashRunning: StateFlow<Boolean> = MutableStateFlow(false),
    val contextFiles: List<String> = emptyList(),
    val availableProviderCount: Int = 1,
    val latestCacheHitRate: Double? = null,
    /** 面板宿主：面板自带键帽的点击（key = KeyCap.actionId，value = 高亮项），装配层接到 `ModelPanels.onPanelEvent`。 */
    val onPanelEvent: (suspend (overlayId: String, key: String, value: String?) -> io.github.zr987459.pigo.contract.actions.ActionResult)? = null,
    /**
     * 面板来源（审查第 3 轮 Important 1）：包住交给 `PiActionsImpl` 的 ModelPanels，终端形态据此判断栈顶面板是不是它产出的，
     * 是则选中 / 提交 / 确认走面板事件。注入后 [onPanelEvent] 可省略（缺省用它的 `onPanelEvent`）。
     */
    val panelSource: PanelSourceTracker? = null,
    /**
     * 「＋ 附件 › 图片」（`attach.image`，外接键盘粘贴图片键同）：装配层打开系统照片选择器，选中的图片写进共享草稿
     * （与 UI 形态同一个入口，r3/app PR 2 最小改动）。null = 照旧交给动作表。
     */
    val onAttachImage: (() -> Unit)? = null,
)

private val SPINNER = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

@Composable
private fun TermScreen(
    host: SessionHost,
    modifier: Modifier,
    cwd: String,
    home: String?,
    gitBranch: String?,
    piVersion: String,
    userKeybindingsJson: String?,
    settings: Map<String, String?>,
    env: TermEnv,
    enabledModels: List<String>,
) {
    val tokens = LocalPiTokens.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 审查第 2 轮慢帧：整屏只订阅「去掉 items 的会话元数据」，流式 delta（只改 items）不再让页眉、编辑器、页脚、状态行重组；
    // items 只在时间线（下面 BoxWithConstraints 的子组合）里读。
    val chatFull = host.chat.collectAsState()
    val chat by remember { derivedStateOf(structuralEqualityPolicy()) { chatFull.value.copy(items = emptyList()) } }
    val draft by host.draft.collectAsState()
    val overlays by host.overlay.items.collectAsState()
    val localBlocks by host.localBlocks.collectAsState()
    val history by host.promptHistory.collectAsState()
    val surfaceExt by host.extras.extensionSurface.collectAsState()
    val usage by host.extras.usageState.collectAsState()
    val catalog = host.actions.all
    val catalogIds = remember(catalog) { catalog.map { it.id }.toSet() }
    val bashRunningExt by env.bashRunning.collectAsState()
    var localBashJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val bashActive = bashRunningExt || localBashJob?.isActive == true

    // ---- 纯视图状态 ----
    var fontSp by rememberSaveable { mutableFloatStateOf(13f) }
    var toolsExpanded by rememberSaveable { mutableStateOf(false) }
    var expandedKeys by remember { mutableStateOf(emptySet<String>()) }
    val historyNav = remember { HistoryNav() }
    var stripVisible by rememberSaveable { mutableStateOf(true) }
    var toast by remember { mutableStateOf<String?>(null) }
    var commands by remember { mutableStateOf(emptyList<SlashCommand>()) }
    var lastEsc by remember { mutableLongStateOf(0L) }
    val hideThinking = settings["hideThinkingBlock"] == "true"
    val maxVisible = settings["autocompleteMaxVisible"]?.toIntOrNull() ?: 5

    val bindings = remember(userKeybindingsJson) {
        KeyBindings.parseDefaults(context.assets.open("pi-default-keybindings.json").bufferedReader().use { it.readText() })
            .withUserOverrides(userKeybindingsJson)
    }
    val expandKey = bindings.keysOf("app.tools.expand").firstOrNull() ?: "ctrl+o"

    LaunchedEffect(Unit) { (host.extras.commands() as? Outcome.Ok)?.let { commands = it.value } }

    fun run(id: String, args: String? = null): kotlinx.coroutines.Job = scope.launch {
        if (id == "attach.image") env.onAttachImage?.let { it(); return@launch }
        when (val r = host.runAction(id, args)) {
            is io.github.zr987459.pigo.contract.actions.ActionResult.Toast -> toast = r.text
            else -> Unit
        }
    }

    // 面板路由与结果落地（审查第 3 轮 Important 1、2）：见 TermPanelRouter
    val panels = remember(host, env, catalogIds) {
        TermPanelRouter(host, env, catalogIds, scope, runAction = { id, a -> run(id, a) }, toast = { toast = it })
    }

    fun toggleForm() { host.form.value = if (host.form.value == UiForm.TERM) UiForm.CALM else UiForm.TERM }

    fun setDraftText(text: String, cursor: Int = text.length) {
        val mode = when {
            text.startsWith("!!") -> BashMode.EXCLUDE
            text.startsWith("!") -> BashMode.INCLUDE
            else -> BashMode.NONE
        }
        host.draft.value = host.draft.value.copy(text = text, selectionStart = cursor, selectionEnd = cursor, bashMode = mode)
    }

    // ---- @ 文件模糊搜索（pi-tui CombinedAutocompleteProvider 的 @ 前缀补全，§3.3；候选源由装配层注入） ----
    val atQuery = remember(draft.text, draft.safeSelectionStart) { TermInput.atQuery(draft.text, draft.safeSelectionStart) }
    // 结果与产生它的查询词绑在一起（审查第 2 轮 Nit 1）：防抖期间旧词的结果不可用
    var atResult by remember { mutableStateOf<Pair<TermInput.AtQuery?, List<FileMatch>>>(null to emptyList()) }
    LaunchedEffect(atQuery, env.fileSearch) {
        val q = atQuery
        val fs = env.fileSearch
        if (q == null || fs == null) { atResult = null to emptyList(); return@LaunchedEffect }
        delay(100)
        atResult = q to runCatching { fs(q.query) }.getOrDefault(emptyList())
    }
    val atMatches = TermInput.atMatchesFor(atQuery, atResult.first, atResult.second)
    /** 正在防抖 / 搜索中：有 @ 查询词、有候选源，但结果还不是这个词的。 */
    val atPending = atQuery != null && env.fileSearch != null && atResult.first != atQuery
    fun pickAt(m: FileMatch) {
        val d = host.draft.value
        val q = TermInput.atQuery(d.text, d.safeSelectionStart) ?: return
        // 文件补全后加空格（pi 同样）；目录保留结尾 `/` 继续往下找
        val ins = m.completion + if (m.isDir) "" else " "
        setDraftText(d.text.substring(0, q.start) + ins + d.text.substring(q.end), q.start + ins.length)
    }

    /** Enter / Tab 碰上防抖中的 @ 查询：等这个词的结果；有候选就插入第一个，没有就执行 [orElse]（Enter = 发送，Tab = 不动）。 */
    fun pickAtWhenReady(orElse: () -> Unit) {
        val fs = env.fileSearch ?: return orElse()
        val q = TermInput.atQuery(host.draft.value.text, host.draft.value.safeSelectionStart) ?: return orElse()
        scope.launch {
            val m = runCatching { fs(q.query) }.getOrDefault(emptyList())
            val still = TermInput.atQuery(host.draft.value.text, host.draft.value.safeSelectionStart)
            if (still == q && m.isNotEmpty()) pickAt(m.first()) else if (still == q) orElse()
        }
    }

    fun submit(followUp: Boolean = false) {
        val text = host.draft.value.text
        if (text.trim() == "/ui" || text.trim() == "/term") {
            setDraftText("")
            if (text.trim() == "/ui") host.form.value = UiForm.CALM else host.form.value = UiForm.TERM
            return
        }
        TermInput.resolveSlash(text, catalog)?.let { (id, args) ->
            if (id == "form.toggle") { setDraftText(""); toggleForm(); return }
            setDraftText("")
            run(id, args)
            return
        }
        historyNav.reset()
        val bash = host.draft.value.bashMode != BashMode.NONE
        val job = run(if (followUp) "send.followUp" else "send")
        if (bash) { localBashJob = job; job.invokeOnCompletion { if (localBashJob === job) localBashJob = null } }
    }

    fun onEsc() {
        val now = System.currentTimeMillis()
        val double = now - lastEsc < 400
        lastEsc = now
        when {
            host.overlay.top != null -> host.overlay.top?.let { top ->
                if (top is io.github.zr987459.pigo.contract.session.OverlayModel.ExtDialog) {
                    scope.launch { host.engine.answerUi(top.request.id, cancelled = true) }
                    host.overlay.pop()
                } else panels.close(top) // 账号登录关闭发 cancel / done（审查第 3 轮 Nit 2）
            }
            chat.running || chat.retry != null || bashActive -> TermInput.actionForKey("Esc", catalog, chat, bashActive)?.let { run(it) }
            double -> run("session.tree") // pi：空闲时双击 Esc 打开 /tree（doubleEscapeAction 默认 tree）
            else -> Unit
        }
    }

    /**
     * ↑↓ 翻历史（审查第 2 轮 Important 2，照 pi-tui）：开始翻时保存草稿；编辑调出的文字即退出浏览（见下方 LaunchedEffect）；
     * ↓ 越过最新一条恢复保存的草稿。[fromKeyboard] 为真时（外接键盘）只在草稿为空或正在浏览时翻，否则 ↑ 留给光标移动。
     */
    fun historyStep(up: Boolean, fromKeyboard: Boolean = false): Boolean {
        val text = host.draft.value.text
        historyNav.onDraftChanged(text) // 同步检查一次：编辑后立刻按键时 LaunchedEffect 可能还没跑
        if (fromKeyboard && text.isNotEmpty() && !historyNav.browsing) return false
        val next = if (up) historyNav.up(history, text) else historyNav.down(history)
        next?.let { setDraftText(it) }
        return next != null
    }

    // 草稿被编辑（本形态输入或另一形态写入）→ 与调出的历史条目不同即退出浏览，之后 ↑↓ 不再覆盖它。
    LaunchedEffect(draft.text) { historyNav.onDraftChanged(draft.text) }

    fun onStripKey(key: String) {
        when (key) {
            TermInput.KEY_FORM -> toggleForm()
            TermInput.KEY_TAB -> {
                val atPick = atMatches.firstOrNull()
                if (atPick != null) pickAt(atPick)
                else if (atPending) pickAtWhenReady {}
                else TermInput.slashQuery(host.draft.value.text)?.let { q ->
                    TermInput.slashCandidates(q, catalog, commands).firstOrNull()?.let { setDraftText("/${it.name} ") }
                }
            }
            // 「/」键：草稿为空时打开扩展命令面板（动作表 ext.commands 的终端入口是键条「/」，r3/session）；
            // 草稿非空时照常在光标处插入「/」。内置斜杠命令仍可直接在编辑器里输入「/」补全。
            TermInput.KEY_SLASH -> if (host.draft.value.text.isEmpty() && catalog.any { it.id == "ext.commands" }) run("ext.commands") else {
                val d = host.draft.value
                val at = d.safeSelectionStart
                setDraftText(d.text.substring(0, at) + key + d.text.substring(d.safeSelectionEnd), at + 1)
            }
            TermInput.KEY_AT, TermInput.KEY_BANG -> {
                val d = host.draft.value
                val at = d.safeSelectionStart
                setDraftText(d.text.substring(0, at) + key + d.text.substring(d.safeSelectionEnd), at + 1)
            }
            "Esc" -> onEsc()
            "⏎" -> submit()
            "⇣" -> submit(followUp = true)
            "↑↓" -> historyStep(up = true)
            "↑↓:down" -> historyStep(up = false)
            "⇞⇟" -> run("transcript.jump", "prev")
            "⇞⇟:next" -> run("transcript.jump", "next")
            "⌃O" -> { toolsExpanded = !toolsExpanded; run("tools.expandAll") }
            else -> TermInput.actionForKey(key, catalog, chat)?.let { run(it) }
        }
    }

    // ---- 阅读位置（§5.3）与贴底跟随：在下面的 BoxWithConstraints 里（要用按行拆分后的 rows） ----
    val listState = rememberLazyListState()
    val headerCount = 1
    var follow by remember { mutableStateOf(host.scrollAnchor.value?.followBottom ?: true) }
    val rowCache = remember { TermRowCache() }

    val focus = remember { FocusRequester() }
    // 面板关闭（栈由非空变空）后焦点回到编辑器：面板可能拿走过焦点，否则外接键盘的下一个快捷键（例如 Ctrl+L）无处投递
    // （PR #18 审查第 1 轮，模拟器 externalKeyboardMatchesPi）。只在「关闭」这一跳请求，初次进入不抢焦点、不弹输入法。
    var overlayWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(overlays.isEmpty()) {
        if (overlays.isEmpty() && overlayWasOpen) runCatching { focus.requestFocus() }
        overlayWasOpen = overlays.isNotEmpty()
    }

    Column(
        modifier
            .fillMaxSize()
            .background(tokens.bg)
            .imePadding()
            .testTag("term:surface")
            .onPreviewKeyEvent { ev ->
                val chord = KeyChords.chordOf(ev) ?: return@onPreviewKeyEvent false
                if (KeyBindings.normalize(chord) == KeyBindings.SWITCH_CHORD) { toggleForm(); return@onPreviewKeyEvent true }
                val ids = bindings.idsFor(chord)
                val top = host.overlay.top
                when {
                    "app.interrupt" in ids -> { onEsc(); true }
                    // overlay 打开时 Enter 提交 / 确认（Nit 7）
                    "tui.input.submit" in ids && top != null -> overlayEnter(host, top, scope, panels)
                    "tui.input.submit" in ids && atMatches.isNotEmpty() -> { pickAt(atMatches.first()); true }
                    "tui.input.submit" in ids && atPending -> { pickAtWhenReady { submit() }; true }
                    "tui.input.submit" in ids -> { submit(); true }
                    // 编辑器单行时 ↑ / ↓ 翻历史（Nit 2，pi 的 tui.editor.cursorUp 在首行时翻历史）
                    (chord == "up" || chord == "down") && top == null && !host.draft.value.text.contains('\n') &&
                        (host.draft.value.text.isEmpty() || historyNav.browsing) -> historyStep(up = chord == "up", fromKeyboard = true)
                    "app.message.followUp" in ids -> { submit(followUp = true); true }
                    "app.tools.expand" in ids && host.overlay.top == null -> { toolsExpanded = !toolsExpanded; run("tools.expandAll"); true }
                    else -> {
                        val hit = ids.firstNotNullOfOrNull { b -> KeyBindings.ACTION_FOR[b]?.let { it to KeyBindings.ACTION_ARGS[b] } }
                        if (hit != null && top == null) { run(hit.first, hit.second); true } else false
                    }
                }
            },
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val style = monoStyle(fontSp.sp, tokens)
            val charW = remember(fontSp, tokens) { measurer.measure("M", style).size.width.coerceAtLeast(1) }
            val cols = with(density) { (maxWidth.toPx() / charW).toInt() }.coerceAtLeast(20)
            val items = chatFull.value.items
            val opts = FormatOptions(width = cols, hideThinking = hideThinking, toolsExpanded = toolsExpanded, expandedKeys = expandedKeys, cwd = cwd, home = home, expandKey = expandKey)
            // 按行拆分（审查第 1 轮慢帧修复）：只重排变化的消息，LazyColumn 只组合可见行。
            val rows = remember(items, opts) { rowCache.rows(items, opts) }
            // 每行固定同一高度（= 行高）：空行与中英混排的行高度一致，锚点「行号 × 行高 + 行内偏移」才能精确还原（§5.3）。
            val rowHeight = with(density) { (fontSp * 1.35f).sp.toDp() }
            val localRows = remember(localBlocks, cols) {
                localBlocks.flatMap { b ->
                    (listOf(TermLine(emptyList())) + TermFormatter.wrapSpans(listOf(Span("[${b.title}] ", "customMessageLabel", bold = true), Span(b.text, "customMessageText")), cols - 2)
                        .map { TermLine(listOf(Span(" ")) + it, "customMessageBg") }).mapIndexed { i, l -> TermRow("local:${b.key}#$i", "local:${b.key}", i, l, null) }
                }
            }
            val rowsNow by rememberUpdatedState(rows)

            // 阅读位置：进入时恢复，用户滚动时写回 host.scrollAnchor（§5.3）。锚点仍是「消息 key + 消息内像素偏移」，与 calm 形态通用：
            // 行号 → 消息 key；消息内偏移 = 本消息在首个可见行之前的行数 × 行高 + 首个可见行内偏移。
            // followBottom 只由用户手势改写（手势结束时是否在底部）；程序触发的滚动不改它（审查第 1 轮 Important 3）。
            suspend fun scrollToBottom() {
                val last = (rowsNow.size + localRows.size + headerCount).coerceAtLeast(1) - 1
                // 偏移给极大值：LazyList 会夹到最大可滚动位置，停在末尾。
                listState.scrollToItem(last, Int.MAX_VALUE)
            }
            LaunchedEffect(Unit) {
                val anchor = host.scrollAnchor.value
                val idx = anchor?.let { a -> rows.indexOfFirst { it.itemKey == a.itemKey } }?.takeIf { it >= 0 }
                // 偏移可以超过首行高度：LazyList 会顺延到本消息后面的行。
                if (anchor == null || anchor.followBottom || idx == null) scrollToBottom()
                else listState.scrollToItem(idx + headerCount, anchor.offsetPx)
            }
            LaunchedEffect(listState) {
                snapshotFlow { listState.isScrollInProgress }.drop(1).collect { scrolling ->
                    if (!scrolling) follow = !listState.canScrollForward
                }
            }
            LaunchedEffect(listState) {
                snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, follow) }.collect { (i, off, f) ->
                    val rs = rowsNow
                    val row = rs.getOrNull(i - headerCount)
                    // 页眉 → 第一条消息、偏移 0（与原逻辑相同）；本地块（不在会话里）→ 最后一条消息。
                    val key = (if (i < headerCount) host.chat.value.items.firstOrNull()?.key else row?.itemKey ?: host.chat.value.items.lastOrNull()?.key)
                        ?: return@collect
                    val rowH = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }?.size ?: 0
                    val offPx = when {
                        i < headerCount -> 0
                        row == null -> off
                        else -> row.lineIndex * rowH + off
                    }
                    host.scrollAnchor.value = ScrollAnchor(key, offPx, followBottom = f)
                }
            }
            // 贴底跟随：行数变化且原来贴底时滚到底。每行固定同一高度，末行内容变化不改变内容总高度，
            // 所以同一行内的 delta 不触发滚动（审查第 2 轮慢帧：原来每个 delta 都 scrollToItem，多一次布局）。
            LaunchedEffect(rows.size, localRows.size) {
                if (follow && !listState.isScrollInProgress) scrollToBottom()
            }

            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("term:transcript")
                        .pointerInput(Unit) {
                            // 双指缩放 10–18sp（§3.1），不影响单指滚动。
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val e = awaitPointerEvent(PointerEventPass.Initial)
                                    if (e.changes.count { it.pressed } >= 2) {
                                        val z = e.calculateZoom()
                                        if (z != 1f) fontSp = (fontSp * z).coerceIn(10f, 18f)
                                        e.changes.forEach { it.consume() }
                                    }
                                } while (e.changes.any { it.pressed })
                            }
                        },
                ) {
                    item(key = "term:header") {
                        Header(piVersion, cols, fontSp, tokens, bindings, TermInput.startupResources(env.contextFiles, commands, enabledModels, home), quiet = settings["quietStartup"] == "true") { run("about") }
                    }
                    items(rows, key = { it.key }) { r ->
                        val l = r.line
                        val m = when {
                            l.action == "thinking.toggle" -> Modifier.clickable { run("thinking.toggle") }.testTag("term:keycap:message:思考块")
                            l.action?.startsWith("tool:") == true && r.toolKey != null -> Modifier.clickable {
                                val k = r.toolKey
                                expandedKeys = if (k in expandedKeys) expandedKeys - k else expandedKeys + k
                            }
                            else -> Modifier
                        }
                        // 思考块开关行已有自己的 testTag（键帽），其余行带所属消息的 tag。
                        TermLineText(l, fontSp.sp, tokens, (if (l.action == "thinking.toggle") m else m.testTag("term:item:${r.itemKey}")).height(rowHeight))
                    }
                    items(localRows, key = { it.key }) { r ->
                        TermLineText(r.line, fontSp.sp, tokens, Modifier.testTag("term:${r.itemKey}").height(rowHeight))
                    }
                }
            }

            // ---- 编辑器上方的固定区：状态行、队列、widget、overlay、补全 ----
            Column(Modifier.align(androidx.compose.ui.Alignment.BottomStart).fillMaxWidth()) {
                StatusLine(chat, cols, fontSp, tokens)
                if (!chat.queue.isEmpty) {
                    Column(Modifier.testTag("term:queue")) {
                        (chat.queue.steering.map { "Steering: $it" } + chat.queue.followUp.map { "Follow-up: $it" }).forEach {
                            TermLineText(TermLine(listOf(Span(" "), Span(CellWidth.truncate(it, cols - 2), "dim"))), fontSp.sp, tokens)
                        }
                        TermLineText(TermLine(listOf(Span(" "), Span("↳ ${bindings.keysOf("app.message.dequeue").firstOrNull() ?: "alt+up"} to edit all queued messages", "dim"))), fontSp.sp, tokens)
                    }
                }
                surfaceExt.widgets.filter { it.value.first != "belowEditor" }.toSortedMap().forEach { (k, v) ->
                    Column(Modifier.testTag("term:widget:$k")) { v.second.forEach { TermLineText(TermLine(listOf(Span(CellWidth.truncate(it, cols)))), fontSp.sp, tokens) } }
                }
                overlays.lastOrNull()?.let { top ->
                    TermOverlay(host, top, cols, fontSp, tokens, catalog, onRun = { id, a -> run(id, a) }, panels = panels)
                }
                if (atMatches.isNotEmpty() && overlays.isEmpty()) AtCompletion(atMatches.take(TermInput.visibleCount(maxVisible)), cols, fontSp, tokens, ::pickAt)
                TermInput.slashQuery(draft.text)?.let { q ->
                    val cands = TermInput.slashCandidates(q, catalog, commands).take(maxVisible)
                    if (cands.isNotEmpty() && overlays.isEmpty()) Completion(cands, cols, fontSp, tokens, catalog) { c ->
                        if (c.actionId != null && c.actionId == "form.toggle") { setDraftText(""); toggleForm() }
                        else if (c.actionId != null) { setDraftText(""); run(c.actionId) }
                        else setDraftText("/${c.name} ")
                    }
                }
            }
        }

        // ---- 编辑器 ----
        Editor(
            text = draft.text, selStart = draft.safeSelectionStart, selEnd = draft.safeSelectionEnd,
            thinkingLevel = chat.thinkingLevel, bash = draft.bashMode != BashMode.NONE,
            fontSp = fontSp, tokens = tokens, focus = focus,
            onChange = { v -> setDraftText(v.text, v.selection.start).also { host.draft.value = host.draft.value.copy(selectionStart = v.selection.start, selectionEnd = v.selection.end) } },
            onBorderTap = { run("thinking.cycle") },
            onBangCap = { run("bash") },
        )
        surfaceExt.widgets.filter { it.value.first == "belowEditor" }.toSortedMap().forEach { (k, v) ->
            Column(Modifier.testTag("term:widget:$k")) { v.second.forEach { TermLineText(TermLine(listOf(Span(CellWidth.truncate(it, 200)))), fontSp.sp, tokens) } }
        }

        // ---- 页脚（照 pi footer.js；cwd / 模型段可点，模型段左右滑循环；右端 [UI] 键帽） ----
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val charW = remember(fontSp, tokens) { measurer.measure("M", monoStyle(fontSp.sp, tokens)).size.width.coerceAtLeast(1) }
            val cols = with(density) { (maxWidth.toPx() / charW).toInt() }.coerceAtLeast(20)
            val tokensUsed = usage?.tokens
            val input = FooterInput(
                cwd = cwd, home = home, gitBranch = gitBranch, sessionName = chat.sessionName,
                input = tokensUsed?.input ?: 0, output = tokensUsed?.output ?: 0, cacheRead = tokensUsed?.cacheRead ?: 0, cacheWrite = tokensUsed?.cacheWrite ?: 0,
                cost = usage?.costUsd ?: 0.0,
                // 压缩后上下文未知：显示 `?`（Nit 4）
                contextPercent = usage?.context.let { c -> if (c != null) c.percent else chat.contextPercent },
                latestCacheHitRate = env.latestCacheHitRate,
                contextWindow = usage?.context?.contextWindow ?: chat.model?.contextWindow ?: 0,
                autoCompact = settings["compaction.enabled"] != "false",
                modelId = chat.model?.id, provider = chat.model?.provider, reasoning = chat.model?.reasoning == true,
                thinkingLevel = chat.thinkingLevel, availableProviderCount = env.availableProviderCount,
                extensionStatuses = surfaceExt.statuses,
            )
            val lines = Footer.render(input, cols, reserveFirstLine = 5)
            Column(Modifier.testTag("term:footer")) {
                lines.forEachIndexed { i, l ->
                    Row {
                        for (s in l) {
                            val m = when (s.segment) {
                                "cwd" -> Modifier.clickable { TermInput.actionForFooter("cwd", catalog)?.let { run(it) } }.testTag("term:footer:cwd")
                                "model" -> Modifier
                                    .clickable { run("model.select") }
                                    .pointerInput(Unit) {
                                        // 左右滑循环模型（pi Ctrl+P / Shift+Ctrl+P）：按整次拖动的累计位移判断，避免分段位移都小于阈值。
                                        var total = 0f
                                        detectHorizontalDragGestures(
                                            onDragStart = { total = 0f },
                                            // 左滑 = Ctrl+P（下一个），右滑 = Shift+Ctrl+P（上一个）（§3.4，Nit 1）
                                            onDragEnd = { if (kotlin.math.abs(total) > 12.dp.toPx()) run("model.cycle", if (total > 0) "backward" else null) },
                                        ) { change, d -> total += d; change.consume() }
                                    }
                                    .testTag("term:footer:model")
                                else -> Modifier
                            }
                            Text(spansToAnnotated(listOf(s), tokens), style = monoStyle(fontSp.sp, tokens), softWrap = false, maxLines = 1, modifier = m)
                        }
                        if (i == 0) Text(
                            spansToAnnotated(listOf(Span(" ".repeat((cols - 4 - CellWidth.width(l.joinToString("") { it.text })).coerceAtLeast(1)) + "[UI]", "accent", bold = true)), tokens), style = monoStyle(fontSp.sp, tokens),
                            modifier = Modifier.clickable { toggleForm() }.testTag("term:footer:ui"),
                        )
                    }
                }
            }
        }

        // ---- 触屏键条（接外接键盘时可收起） ----
        if (stripVisible) KeyStrip(TermInput.stripKeys(catalog), fontSp, tokens, ::onStripKey, onCollapse = { stripVisible = false })
        else Text("⌨", modifier = Modifier.clickable { stripVisible = true }.padding(4.dp).testTag("term:strip:show"), color = tokens.fgOf("dim"))

        toast?.let { t ->
            LaunchedEffect(t) { delay(2500); toast = null }
            TermLineText(TermLine(listOf(Span(" "), Span(t, "warning"))), fontSp.sp, tokens, Modifier.testTag("term:toast"))
        }
    }
}

@Composable
private fun Header(
    version: String,
    cols: Int,
    fontSp: Float,
    tokens: PiTokens,
    bindings: KeyBindings,
    resources: List<TermInput.ResourceSection>,
    quiet: Boolean,
    onVersion: () -> Unit,
) {
    fun k(id: String, fallback: String) = bindings.keysOf(id).firstOrNull() ?: fallback
    val hints = listOf(
        "${k("app.interrupt", "escape")} to interrupt", "${k("app.clear", "ctrl+c")} to clear", "${k("app.thinking.cycle", "shift+tab")} to cycle thinking",
        "${k("app.model.select", "ctrl+l")} to select model", "${k("app.tools.expand", "ctrl+o")} to expand tools", "/ for commands", "! to run bash",
    )
    // pi 启动区：已加载的上下文文件、技能、提示模板、扩展、模型范围；quietStartup 时默认收起，点标题行展开 / 收起。
    var expanded by rememberSaveable(quiet) { mutableStateOf(!quiet) }
    Column(Modifier.testTag("term:header")) {
        TermLineText(TermLine(emptyList()), fontSp.sp, tokens)
        TermLineText(
            TermLine(listOf(Span(" "), Span("pi", "accent", bold = true), Span(" v$version", "dim"))), fontSp.sp, tokens,
            Modifier.clickable(onClick = onVersion).testTag("term:keycap:header:版本号"),
        )
        if (!quiet) for (l in TermFormatter.wrapSpans(listOf(Span(hints.joinToString(" · "), "dim")), cols - 2)) TermLineText(TermLine(listOf(Span(" ")) + l), fontSp.sp, tokens)
        if (resources.isNotEmpty()) {
            val summary = resources.joinToString(", ") { "${it.items.size} ${it.title.lowercase()}" }
            TermLineText(
                TermLine(listOf(Span(" "), Span(if (expanded) "▾ " else "▸ ", "dim"), Span(if (expanded) "Loaded" else "Loaded: $summary", "dim"))), fontSp.sp, tokens,
                Modifier.clickable { expanded = !expanded }.testTag("term:header:resources"),
            )
            if (expanded) for (sec in resources) {
                TermLineText(TermLine(listOf(Span(" "), Span("[${sec.title}]", "mdHeading", bold = true))), fontSp.sp, tokens, Modifier.testTag("term:header:section:${sec.title}"))
                for (item in sec.items) TermLineText(TermLine(listOf(Span("   "), Span(CellWidth.truncate(item, cols - 4), "dim"))), fontSp.sp, tokens)
            }
        }
    }
}

@Composable
private fun AtCompletion(matches: List<FileMatch>, cols: Int, fontSp: Float, tokens: PiTokens, onPick: (FileMatch) -> Unit) {
    Column(Modifier.background(tokens.bg).testTag("term:at")) {
        matches.forEachIndexed { i, m ->
            val label = if (m.isDir) m.path + "/" else m.path
            TermLineText(
                TermLine(listOf(Span(" "), Span(CellWidth.truncate(label, cols - 2), if (i == 0) "accent" else null, bold = i == 0)), if (i == 0) "selectedBg" else null),
                fontSp.sp, tokens,
                Modifier.clickable { onPick(m) }.testTag("term:at:${m.path}"),
            )
        }
    }
}

@Composable
private fun StatusLine(chat: io.github.zr987459.pigo.contract.ChatState, cols: Int, fontSp: Float, tokens: PiTokens) {
    // 计时只在这里读（审查第 1 轮 Nit 6：动画帧不再让整屏重组）
    var tick by remember { mutableStateOf(0) }
    var runStart by remember { mutableLongStateOf(0L) }
    val retry = chat.retry
    val retryStart = remember(retry) { System.currentTimeMillis() }
    // 由下方计时循环每 80ms 更新，倒计时随之重组（只在本状态行内）
    var nowMs by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(chat.running, retry) {
        if (chat.running || retry != null) {
            if (chat.running && runStart == 0L) runStart = System.currentTimeMillis()
            while (true) { delay(80); tick++; nowMs = System.currentTimeMillis() }
        } else runStart = 0L
    }
    val text: List<Span>? = when {
        retry != null -> listOf(
            Span(SPINNER[tick % SPINNER.size] + " ", "warning"),
            // 倒计时（Nit 5）：从横幅出现起倒数，向上取整，同 pi 的 `Retrying (n/m) in Ns`
            Span("Retrying (${retry.attempt}/${retry.maxAttempts}) in ${TermInput.retrySecondsLeft(retry.delayMs, nowMs - retryStart)}s", "warning"),
            Span(retry.errorMessage?.let { " · $it" } ?: "", "dim"),
            Span(" (escape to cancel)", "dim"),
        )
        chat.running -> listOf(
            Span(SPINNER[tick % SPINNER.size] + " ", "accent"),
            Span(chat.statusLine ?: "Working...", "muted"),
            Span(" ${((System.currentTimeMillis() - runStart) / 1000)}s", "dim"),
            Span(" (escape to interrupt)", "dim"),
        )
        chat.statusLine != null -> listOf(Span(chat.statusLine!!, "muted"))
        else -> null
    }
    if (text != null) Column(Modifier.testTag("term:status")) {
        TermLineText(TermLine(emptyList()), fontSp.sp, tokens)
        TermFormatter.wrapSpans(text, cols - 2).take(2).forEach { TermLineText(TermLine(listOf(Span(" ")) + it), fontSp.sp, tokens) }
    }
}

@Composable
private fun Completion(cands: List<TermInput.Candidate>, cols: Int, fontSp: Float, tokens: PiTokens, catalog: List<io.github.zr987459.pigo.contract.actions.PiActionSpec>, onPick: (TermInput.Candidate) -> Unit) {
    Column(Modifier.background(tokens.bg).testTag("term:completion")) {
        cands.forEachIndexed { i, c ->
            val name = "/${c.name}"
            val desc = c.description?.let { "  $it" } ?: ""
            TermLineText(
                TermLine(listOf(Span(" "), Span(name, if (i == 0) "accent" else null, bold = i == 0), Span(CellWidth.truncate(desc, (cols - 2 - CellWidth.width(name)).coerceAtLeast(0)), "dim")), if (i == 0) "selectedBg" else null),
                fontSp.sp, tokens,
                Modifier.clickable { onPick(c) }.testTag("term:slash:${c.name}"),
            )
        }
        // 补全浮层底部的键帽（技能 / 模板入口，§3.4）
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TermInput.keyCapsFor("slash", catalog).forEach { (key, id) ->
                Text(" [$key] ", style = monoStyle(fontSp.sp, tokens).copy(color = tokens.fgOf("accent")), modifier = Modifier.testTag("term:keycap:slash:$key").clickable { onPick(TermInput.Candidate(key, null, id)) })
            }
        }
    }
}

@Composable
private fun Editor(
    text: String,
    selStart: Int,
    selEnd: Int,
    thinkingLevel: String?,
    bash: Boolean,
    fontSp: Float,
    tokens: PiTokens,
    focus: FocusRequester,
    onChange: (TextFieldValue) -> Unit,
    onBorderTap: () -> Unit,
    onBangCap: () -> Unit,
) {
    val border = if (bash) tokens.fgOf("bashMode") else tokens.thinkingBorder(thinkingLevel)
    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        BasicTextField(
            value = TextFieldValue(text, TextRange(selStart, selEnd)),
            onValueChange = onChange,
            textStyle = monoStyle(fontSp.sp, tokens),
            cursorBrush = SolidColor(tokens.fgOf("accent")),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .drawBehind {
                    // 框线用 drawBehind 画（§3.1），中英混排不错位。
                    drawRoundRect(color = border, topLeft = Offset(0f, 0f), size = Size(size.width, size.height), cornerRadius = CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx()))
                }
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .testTag("term:editor"),
        )
        Row(Modifier.padding(start = 8.dp)) {
            Text(
                " ${thinkingLevel ?: "off"} ", style = monoStyle((fontSp - 3).coerceAtLeast(9f).sp, tokens).copy(color = border),
                modifier = Modifier.background(tokens.bg).clickable(onClick = onBorderTap).testTag("term:editor:border"),
            )
            Text(
                " ! ", style = monoStyle((fontSp - 3).coerceAtLeast(9f).sp, tokens).copy(color = tokens.fgOf("bashMode")),
                modifier = Modifier.background(tokens.bg).clickable(onClick = onBangCap).testTag("term:keycap:editor:!"),
            )
        }
    }
}

@Composable
private fun KeyStrip(keys: List<TermInput.StripKey>, fontSp: Float, tokens: PiTokens, onKey: (String) -> Unit, onCollapse: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("term:strip")) {
        keys.forEach { k ->
            // ↑↓、⇞⇟ 拆成两个键帽（Nit 2、3）：第一个沿用原测试标签（上 / 上一条），第二个加 `:down` / `:next`
            val second = when (k.key) { "↑↓" -> "↓" to "↑↓:down"; "⇞⇟" -> "⇟" to "⇞⇟:next"; else -> null }
            Text(
                if (second != null) k.label.take(1) else k.label,
                style = monoStyle(fontSp.sp, tokens).copy(color = tokens.fgOf(if (k.key == "⏎") "accent" else "muted")),
                modifier = Modifier.clickable { onKey(k.key) }.padding(horizontal = 10.dp, vertical = 8.dp).testTag(if (k.key == TermInput.KEY_FORM) "form:toggle" else "term:key:${k.key}"),
            )
            if (second != null) Text(
                second.first,
                style = monoStyle(fontSp.sp, tokens).copy(color = tokens.fgOf("muted")),
                modifier = Modifier.clickable { onKey(second.second) }.padding(horizontal = 10.dp, vertical = 8.dp).testTag("term:key:${second.second}"),
            )
        }
        Text("▾", color = tokens.fgOf("dim"), modifier = Modifier.clickable(onClick = onCollapse).padding(horizontal = 10.dp, vertical = 8.dp).testTag("term:strip:hide"))
    }
}
