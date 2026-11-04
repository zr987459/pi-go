package io.github.zr987459.pigo.feature.chat.ui.calm

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
 * ☰ 会话列表（`/resume` 的 UI 形态，§4.3）：＋新对话（长按：临时会话 / 在其他目录新建）、搜索、
 * ⋯ 菜单（排序：最近 / 名称、只看已命名、显示路径、导入会话）、左滑删除（进回收目录 + 5 秒撤销）、长按重命名；
 * 底部齿轮 = 设置（只有「模型」）。数据与选项都在 `host.sessions`，本面板只画。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CalmSessionList(
    host: SessionHost,
    onAction: (String, String?) -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
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

    Column(
        Modifier.fillMaxHeight().width(320.dp).background(c.background).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp).testTag("calm:drawer"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                Text(
                    "＋ 新对话",
                    style = Calm.type.body, color = c.text,
                    modifier = Modifier.border(1.dp, c.stroke, RoundedCornerShape(12.dp))
                        .combinedClickable(onClick = { onClose(); onAction("session.new", null) }, onLongClick = { newMenu = true })
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag(persistentTag("session.new") ?: "persist:session.new"),
                )
                DropdownMenu(expanded = newMenu, onDismissRequest = { newMenu = false }) {
                    DropdownMenuItem(text = { Text("临时会话（离开后不保留）") }, onClick = { newMenu = false; onClose(); onAction("session.newEphemeral", null) }, modifier = Modifier.testTag("new:ephemeral"))
                    DropdownMenuItem(text = { Text("在其他目录新建") }, onClick = { newMenu = false; onClose(); onAction("session.cwd", null) }, modifier = Modifier.testTag("new:cwd"))
                }
            }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { moreMenu = true }, modifier = Modifier.testTag("drawer:more")) { Icon(CalmIcons.More, contentDescription = "列表选项", tint = c.text) }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (options.sort == SessionSort.RECENT) "排序：最近 ✓" else "排序：最近") },
                        onClick = { host.sessions.options.value = options.copy(sort = SessionSort.RECENT) },
                        modifier = Modifier.testTag("drawer:sortRecent"),
                    )
                    DropdownMenuItem(
                        text = { Text(if (options.sort == SessionSort.NAME) "排序：名称 ✓" else "排序：名称") },
                        onClick = { host.sessions.options.value = options.copy(sort = SessionSort.NAME) },
                        modifier = Modifier.testTag("drawer:sortName"),
                    )
                    DropdownMenuItem(
                        text = { Text(if (options.namedOnly) "只看已命名 ✓" else "只看已命名") },
                        onClick = { host.sessions.options.value = options.copy(namedOnly = !options.namedOnly) },
                        modifier = Modifier.testTag("drawer:namedOnly"),
                    )
                    DropdownMenuItem(
                        text = { Text(if (options.showPath) "显示路径 ✓" else "显示路径") },
                        onClick = { host.sessions.options.value = options.copy(showPath = !options.showPath) },
                        modifier = Modifier.testTag("drawer:showPath"),
                    )
                    DropdownMenuItem(text = { Text("导入会话") }, onClick = { moreMenu = false; onClose(); onAction("session.import", null) }, modifier = Modifier.testTag("drawer:import"))
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        Row(
            Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(12.dp)).border(1.dp, c.stroke, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(CalmIcons.Search, contentDescription = null, tint = c.secondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (options.query.isEmpty()) Text("搜索会话", style = Calm.type.label, color = c.secondary)
                BasicTextField(
                    options.query, { q -> host.sessions.options.value = host.sessions.options.value.copy(query = q) },
                    singleLine = true, textStyle = Calm.type.label.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().testTag("drawer:search"),
                )
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            items(sessions, key = { it.guestPath }) { s ->
                SessionRow(
                    s = s,
                    current = s.guestPath == chat.sessionFile,
                    showPath = options.showPath,
                    onOpen = { onClose(); scope.launch { host.sessions.open(s.guestPath) } },
                    onRename = { renaming = s; renameText = s.name ?: "" },
                    onDelete = {
                        scope.launch {
                            when (val r = host.sessions.delete(s.guestPath)) {
                                is Outcome.Ok -> pending = r.value
                                is Outcome.Err -> onToast(r.message)
                            }
                        }
                    },
                )
            }
        }
        pending?.let { p ->
            Row(
                Modifier.fillMaxWidth().background(c.text, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp).testTag("drawer:undoBar"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已移进回收目录", style = Calm.type.label, color = c.background, modifier = Modifier.weight(1f))
                Text("撤销", style = Calm.type.label, color = c.accent, modifier = Modifier.clickable {
                    pending = null
                    scope.launch { host.sessions.undoDelete(p) }
                }.padding(4.dp).testTag("drawer:undo"))
            }
            Spacer(Modifier.size(8.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("drawer:settings")) { Icon(CalmIcons.Gear, contentDescription = "设置（模型）", tint = c.secondary) }
            Text("设置", style = Calm.type.label, color = c.secondary)
        }
    }

    renaming?.let { s ->
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.background(c.surface, RoundedCornerShape(16.dp)).border(1.dp, c.stroke, RoundedCornerShape(16.dp)).padding(16.dp).testTag("drawer:renameDialog")) {
                Text("重命名会话", style = Calm.type.title, color = c.text)
                Spacer(Modifier.size(8.dp))
                BasicTextField(renameText, { renameText = it }, singleLine = true, textStyle = Calm.type.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().border(1.dp, c.stroke, RoundedCornerShape(12.dp)).padding(12.dp).testTag("drawer:renameInput"))
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("取消", style = Calm.type.label, color = c.text, modifier = Modifier.clickable { renaming = null }.padding(8.dp))
                    Text("保存", style = Calm.type.label, color = c.accent, modifier = Modifier.clickable {
                        val name = renameText.trim(); renaming = null
                        if (name.isNotEmpty()) scope.launch { host.sessions.rename(s.guestPath, name) }
                    }.padding(8.dp).testTag("drawer:renameOk"))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(s: SessionSummary, current: Boolean, showPath: Boolean, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val c = Calm.colors
    var dx by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxWidth()) {
        if (dx < -8f) {
            Text("删除", style = Calm.type.label, color = c.error, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
        }
        Column(
            Modifier.fillMaxWidth().offset { IntOffset(dx.roundToInt(), 0) }
                .background(if (current) c.userBlock else c.background, RoundedCornerShape(12.dp))
                .pointerInput(s.guestPath) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (dx < -160f) onDelete(); dx = 0f },
                        onDragCancel = { dx = 0f },
                    ) { _, d -> dx = (dx + d).coerceAtMost(0f) }
                }
                .combinedClickable(onClick = onOpen, onLongClick = onRename)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("session:${s.guestPath}"),
        ) {
            // 未命名会话显示第一条用户消息（与 pi 选择器一致，X6）
            Text(s.name?.takeIf { it.isNotBlank() } ?: s.preview.ifBlank { "（空会话）" }, style = Calm.type.body, color = c.text, maxLines = 1)
            Text(
                listOfNotNull(relativeTime(s.updatedAt.toEpochMilli()), if (showPath) s.cwd else null).joinToString(" · "),
                style = Calm.type.caption, color = c.secondary, maxLines = 1,
            )
        }
    }
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
