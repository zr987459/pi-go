@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.zr987459.pigo.feature.chat.ui.calm

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SessionSummary
import io.github.zr987459.pigo.contract.session.PendingDelete
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.contract.session.SessionSort
import io.github.zr987459.pigo.core.ui.calm.Calm
import io.github.zr987459.pigo.core.ui.calm.CalmIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 会话列表（r5：照 OpenMinis 首页 `ui/sessions/SessionListScreen.kt`）：整页、分组底色；
 * 顶栏左 ⚙ 设置、标题「pi-go」、右侧「终端」入口与 ⋯（排序 / 只看已命名 / 显示路径 / 导入）；
 * 搜索框；按「今天 / 昨天 / 7 天内 / 更早」分节的会话行（44dp 圆形头像 + 标题 + 预览 + 右侧时间，当前会话高亮）；
 * 长按行 = 菜单（重命名 / 删除），左滑删除（进回收目录 + 5 秒撤销）；右下角米色圆形「新对话」按钮（长按：临时会话 / 在其他目录新建）。
 * 这是 `/resume` 的界面入口，数据与选项都在 `host.sessions`，本页只画。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CalmSessionList(
    host: SessionHost,
    onAction: (String, String?) -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    onToast: (String) -> Unit,
) {
    val c = Calm.colors
    val scope = rememberCoroutineScope()
    val sessions by host.sessions.sessions.collectAsState()
    val options by host.sessions.options.collectAsState()
    val chat by host.chat.collectAsState()
    var newMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingDelete?>(null) }
    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { host.sessions.refresh() }
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        delay((p.undoUntilMillis - System.currentTimeMillis()).coerceIn(0, 5_000))
        if (pending == p) pending = null
    }
    val delete: (SessionSummary) -> Unit = { s ->
        scope.launch {
            when (val r = host.sessions.delete(s.guestPath)) {
                is Outcome.Ok -> pending = r.value
                is Outcome.Err -> onToast(r.message)
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(c.groupedBg)
            // 吃掉空白处的点击，不漏到下面的聊天页
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .testTag("calm:drawer"),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // 顶栏（OpenMinis：navigationIcon = 设置，标题 20sp Bold，actions = 终端 + ⋯）
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("drawer:settings")) {
                    Icon(CalmIcons.Gear, contentDescription = "设置（模型）", tint = c.text)
                }
                Text("pi-go", style = Calm.type.largeTitle, color = c.text, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconButton(onClick = onOpenTerminal, modifier = Modifier.testTag("drawer:terminal")) {
                    Icon(CalmIcons.Terminal, contentDescription = "终端", tint = c.text)
                }
                Box {
                    IconButton(onClick = { moreMenu = true }, modifier = Modifier.testTag("drawer:more")) { Icon(CalmIcons.More, contentDescription = "列表选项", tint = c.text) }
                    MinisMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }, alignEnd = true) {
                        MinisMenuItem("按最近排序", onClick = { host.sessions.options.value = options.copy(sort = SessionSort.RECENT) },
                            trailing = if (options.sort == SessionSort.RECENT) "✓" else null, modifier = Modifier.testTag("drawer:sortRecent"))
                        MinisMenuItem("按名称排序", onClick = { host.sessions.options.value = options.copy(sort = SessionSort.NAME) },
                            trailing = if (options.sort == SessionSort.NAME) "✓" else null, modifier = Modifier.testTag("drawer:sortName"))
                        MinisMenuDivider()
                        MinisMenuItem("只看已命名", onClick = { host.sessions.options.value = options.copy(namedOnly = !options.namedOnly) },
                            trailing = if (options.namedOnly) "✓" else null, modifier = Modifier.testTag("drawer:namedOnly"))
                        MinisMenuItem("显示路径", onClick = { host.sessions.options.value = options.copy(showPath = !options.showPath) },
                            trailing = if (options.showPath) "✓" else null, modifier = Modifier.testTag("drawer:showPath"))
                        MinisMenuDivider()
                        MinisMenuItem("导入会话", icon = CalmIcons.Folder, onClick = { moreMenu = false; onClose(); onAction("session.import", null) }, modifier = Modifier.testTag("drawer:import"))
                        MinisMenuItem("终端", icon = CalmIcons.Terminal, onClick = { moreMenu = false; onOpenTerminal() }, modifier = Modifier.testTag("drawer:terminalMenu"))
                    }
                }
            }
            // 搜索框（iOS 式灰色圆角搜索栏）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(if (c.isDark) Color(0xFF1C1C1E) else Color(0x1F767680), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CalmIcons.Search, contentDescription = null, tint = c.secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (options.query.isEmpty()) Text("搜索", style = Calm.type.body, color = c.secondary)
                    BasicTextField(
                        options.query, { q -> host.sessions.options.value = host.sessions.options.value.copy(query = q) },
                        singleLine = true, textStyle = Calm.type.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth().testTag("drawer:search"),
                    )
                }
                if (options.query.isNotEmpty()) {
                    Icon(CalmIcons.Cancel, contentDescription = "清除搜索", tint = c.tertiary, modifier = Modifier.size(18.dp).clip(CircleShape)
                        .clickable { host.sessions.options.value = host.sessions.options.value.copy(query = "") })
                }
            }
            // 回到当前对话（pi 引擎里正开着的会话；OpenMinis 双栏模式下列表高亮当前会话，单栏用这一行代替）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onClose).padding(horizontal = 4.dp, vertical = 6.dp).testTag("drawer:back"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("继续当前对话：" + (chat.sessionName?.takeIf { it.isNotBlank() } ?: "新对话"), style = Calm.type.label, color = c.accent,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(CalmIcons.ChevronRight, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
            }
            val grouped = remember(sessions, options.sort) { groupByDate(sessions, options.sort == SessionSort.RECENT) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (sessions.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (options.query.isBlank()) "还没有会话。点右下角开始新对话。" else "没有匹配「${options.query}」的会话",
                            style = Calm.type.label, color = c.secondary,
                            modifier = Modifier.fillMaxWidth().padding(32.dp).testTag("drawer:empty"),
                        )
                    }
                }
                for ((section, list) in grouped) {
                    if (section != null) item(key = "section:$section") { SectionHeader(section) }
                    items(list, key = { it.guestPath }) { s ->
                        SessionRow(
                            s = s,
                            current = s.guestPath == chat.sessionFile,
                            showPath = options.showPath,
                            onOpen = { onClose(); scope.launch { host.sessions.open(s.guestPath) } },
                            onRename = { renaming = s; renameText = s.name ?: "" },
                            onDelete = { delete(s) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }

        // 撤销条（底部浮起的深色胶囊，OpenMinis Snackbar 样式）
        AnimatedVisibility(
            pending != null,
            enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 16.dp, end = 96.dp, bottom = 24.dp),
        ) {
            val p = pending
            Row(
                Modifier.fillMaxWidth().background(c.toastBg, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp).testTag("drawer:undoBar"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已移进回收目录", style = Calm.type.label, color = Color.White, modifier = Modifier.weight(1f))
                Text("撤销", style = Calm.type.label.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFF64A0FF), modifier = Modifier.clickable {
                    pending = null
                    if (p != null) scope.launch { host.sessions.undoDelete(p) }
                }.padding(4.dp).testTag("drawer:undo"))
            }
        }

        // 新对话 FAB（OpenMinis DualFabRow：56dp 米色圆形，4dp 阴影）
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 24.dp)) {
            Box(
                Modifier.size(56.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(c.fab)
                    .combinedClickable(onClick = { onClose(); onAction("session.new", null) }, onLongClick = { newMenu = true })
                    .testTag(persistentTag("session.new") ?: "persist:session.new"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CalmIcons.Compose, contentDescription = "新对话", tint = Color.White, modifier = Modifier.size(24.dp))
            }
            MinisMenu(expanded = newMenu, onDismissRequest = { newMenu = false }, alignEnd = true) {
                MinisMenuItem("临时会话（离开后不保留）", icon = CalmIcons.Sparkle, onClick = { newMenu = false; onClose(); onAction("session.newEphemeral", null) }, modifier = Modifier.testTag("new:ephemeral"))
                MinisMenuItem("在其他目录新建", icon = CalmIcons.Folder, onClick = { newMenu = false; onClose(); onAction("session.cwd", null) }, modifier = Modifier.testTag("new:cwd"))
            }
        }

        renaming?.let { s ->
            MinisDialog(
                title = "重命名会话",
                onDismiss = { renaming = null },
                confirmText = "保存",
                onConfirm = {
                    val name = renameText.trim(); renaming = null
                    if (name.isNotEmpty()) scope.launch { host.sessions.rename(s.guestPath, name) }
                },
                confirmTag = "drawer:renameOk",
                tag = "drawer:renameDialog",
            ) {
                BasicTextField(renameText, { renameText = it }, singleLine = true, textStyle = Calm.type.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().background(c.groupedBg, RoundedCornerShape(10.dp)).padding(12.dp).testTag("drawer:renameInput"))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Calm.colors.secondary,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
    )
}

/** 会话行（OpenMinis SessionRow）：分组卡片里的一行，16dp 圆角；头像颜色由会话标题稳定散列。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    s: SessionSummary,
    current: Boolean,
    showPath: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Calm.colors
    val haptics = LocalHapticFeedback.current
    var dx by remember { mutableFloatStateOf(0f) }
    val shownDx by animateFloatAsState(dx, tween(if (dx == 0f) 220 else 0), label = "swipe")
    var menu by remember { mutableStateOf(false) }
    val title = s.name?.takeIf { it.isNotBlank() } ?: s.preview.ifBlank { "（空会话）" }
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        if (shownDx < -8f) {
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(16.dp)).background(c.danger), contentAlignment = Alignment.CenterEnd) {
                Text("删除", style = Calm.type.label.copy(fontWeight = FontWeight.SemiBold), color = Color.White, modifier = Modifier.padding(end = 20.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().offset { IntOffset(shownDx.roundToInt(), 0) }
                .clip(RoundedCornerShape(16.dp))
                .background(if (current) c.accent.copy(alpha = if (c.isDark) 0.22f else 0.12f) else c.surface)
                .pointerInput(s.guestPath) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (dx < -160f) onDelete(); dx = 0f },
                        onDragCancel = { dx = 0f },
                    ) { _, d -> dx = (dx + d).coerceAtMost(0f) }
                }
                .combinedClickable(onClick = onOpen, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true })
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("session:${s.guestPath}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val hue = avatarColor(title)
            Box(Modifier.size(44.dp).background(hue.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                Text(title.trim().firstOrNull()?.uppercase() ?: "π", style = Calm.type.title, color = hue)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // 未命名会话显示第一条用户消息（与 pi 选择器一致，X6）
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(s.preview.takeIf { s.name != null && it.isNotBlank() }, if (showPath) s.cwd else null).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, fontSize = 14.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Text(relativeTime(s.updatedAt.toEpochMilli()), fontSize = 13.sp, color = c.tertiary, maxLines = 1)
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            MinisMenu(expanded = menu, onDismissRequest = { menu = false }, alignEnd = true) {
                MinisMenuItem("重命名", icon = CalmIcons.Pencil, onClick = { menu = false; onRename() }, modifier = Modifier.testTag("drawer:rename"))
                MinisMenuDivider()
                MinisMenuItem("删除", icon = CalmIcons.Trash, destructive = true, onClick = { menu = false; onDelete() }, modifier = Modifier.testTag("drawer:delete"))
            }
        }
    }
}

private val AVATAR_HUES = listOf(
    Color(0xFF528AD2), Color(0xFF34C759), Color(0xFFFF9500), Color(0xFFAF52DE),
    Color(0xFFFF2D55), Color(0xFF5AC8FA), Color(0xFFB7AF96), Color(0xFF30B0C7),
)

internal fun avatarColor(seed: String): Color = AVATAR_HUES[(seed.hashCode() and 0x7fffffff) % AVATAR_HUES.size]

/** 按 OpenMinis 的日期分节（今天 / 昨天 / 7 天内 / 30 天内 / 更早）；按名称排序时不分节。 */
internal fun groupByDate(list: List<SessionSummary>, byDate: Boolean, now: Long = System.currentTimeMillis()): List<Pair<String?, List<SessionSummary>>> {
    if (!byDate) return listOf(null to list)
    val zone = java.util.TimeZone.getDefault()
    val dayMs = 86_400_000L
    fun dayIndex(ms: Long) = Math.floorDiv(ms + zone.getOffset(ms), dayMs)
    val today = dayIndex(now)
    val out = LinkedHashMap<String, MutableList<SessionSummary>>()
    for (s in list) {
        val d = today - dayIndex(s.updatedAt.toEpochMilli())
        val key = when {
            d <= 0L -> "今天"
            d == 1L -> "昨天"
            d < 7L -> "7 天内"
            d < 30L -> "30 天内"
            else -> "更早"
        }
        out.getOrPut(key) { mutableListOf() } += s
    }
    return out.map { (k, v) -> k to v.toList() }
}

internal fun relativeTime(ms: Long, now: Long = System.currentTimeMillis()): String {
    val d = (now - ms).coerceAtLeast(0) / 1000
    return when {
        d < 60 -> "刚刚"
        d < 3600 -> "${d / 60} 分钟前"
        d < 86_400 -> "${d / 3600} 小时前"
        d < 30 * 86_400 -> "${d / 86_400} 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }
}
