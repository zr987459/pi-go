package io.github.zr987459.pigo.feature.files.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.FileNode
import io.github.zr987459.pigo.feature.files.browser.FilesController
import io.github.zr987459.pigo.feature.files.platform.FileShare
import io.github.zr987459.pigo.feature.files.platform.Pickers
import kotlinx.coroutines.launch

/**
 * 文件页（F1、F2、F3）：根目录切换（会话 cwd、/root）、面包屑、目录列表（Git 状态标记、本轮改动高亮）、
 * 新建文件夹、从手机导入、显示隐藏文件；长按目录「在此新建会话」（F2），长按文件「在对话中引用」。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
@Deprecated("r3: r3/app 删除（DESIGN-r3 X4），新代码不得引用")
fun FilesBrowserScreen(
    controller: FilesController,
    onOpenFile: (FileNode) -> Unit,
    onOpenGit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Set<String> = emptySet(),
    onNewSessionHere: ((String) -> Unit)? = null,
    onMention: ((FileNode) -> Unit)? = null,
) {
    val s by controller.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var longPressed by remember { mutableStateOf<FileNode?>(null) }

    LaunchedEffect(s.message) {
        s.message?.let { snack.showSnackbar(it); controller.consumeMessage() }
    }
    BackHandler { if (!controller.up()) onClose() }

    Scaffold(
        modifier = modifier.testTag("files.browser"),
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text("文件", maxLines = 1) },
                navigationIcon = { IconButton(onClick = { if (!controller.up()) onClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (s.git?.isRepo == true) {
                        TextButton(onClick = onOpenGit, modifier = Modifier.testTag("files.git")) {
                            Text("Git" + (s.git?.files?.size?.takeIf { it > 0 }?.let { " · $it" } ?: ""))
                        }
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val uris = Pickers.pickDocuments(ctx)
                            if (uris.isNotEmpty()) controller.import(uris.map { u ->
                                (FileShare.displayName(ctx, u).first ?: "file") to { ctx.contentResolver.openInputStream(u) }
                            })
                        }
                    }, modifier = Modifier.testTag("files.import")) { Icon(Icons.Filled.Add, "从手机导入") }
                    IconButton(onClick = controller::refresh) { Icon(Icons.Filled.Refresh, "刷新") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("新建文件夹") }, onClick = { menu = false; newFolder = true })
                            DropdownMenuItem(text = { Text(if (s.showHidden) "不显示隐藏文件" else "显示隐藏文件") }, onClick = { menu = false; controller.toggleHidden() })
                            if (onNewSessionHere != null) {
                                DropdownMenuItem(text = { Text("在当前目录新建会话") }, onClick = { menu = false; onNewSessionHere(s.dir) })
                            }
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (s.roots.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.roots.forEach { r ->
                        val selected = s.dir == r.guestPath || s.dir.startsWith(r.guestPath + "/")
                        FilterChip(selected = selected, onClick = { controller.open(r.guestPath) }, label = {
                            Text(if (r.guestPath == controller.sessionCwd) "会话目录" else r.guestPath)
                        })
                    }
                }
            }
            Breadcrumbs(s.dir, s.roots.map { it.guestPath }, onClick = controller::open)
            s.git?.takeIf { it.isRepo }?.let { g ->
                Text(
                    "分支 ${g.branch ?: "?"}" + (if (g.ahead > 0) " ↑${g.ahead}" else "") + (if (g.behind > 0) " ↓${g.behind}" else "") +
                        (if (g.files.isEmpty()) " · 没有改动" else " · ${g.files.size} 个改动"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            HorizontalDivider()
            when {
                s.loading && s.entries.isEmpty() -> CenterMessage("", loading = true)
                s.error != null -> CenterMessage(s.error!!)
                s.entries.isEmpty() -> CenterMessage("空文件夹")
                else -> LazyColumn(Modifier.fillMaxSize().testTag("files.list")) {
                    items(s.entries, key = { it.guestPath }) { n ->
                        FileRow(
                            n,
                            badge = s.gitBadges[n.guestPath]?.let { it to badgeColor(it) },
                            highlighted = n.guestPath in highlight || highlight.any { it.startsWith(n.guestPath + "/") },
                            onClick = { if (n.isDir) controller.open(n.guestPath) else onOpenFile(n) },
                            onLongClick = { longPressed = n },
                        )
                    }
                }
            }
        }
    }

    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text("新建文件夹") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("名称") }) },
            confirmButton = { TextButton(onClick = { newFolder = false; controller.mkdir(name) }) { Text("新建") } },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text("取消") } },
        )
    }
    longPressed?.let { n ->
        AlertDialog(
            onDismissRequest = { longPressed = null },
            title = { Text(n.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text(n.guestPath + if (!n.isDir) "\n" + Fmt.size(n.sizeBytes) + "  " + Fmt.time(n.modifiedAt) else "") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (n.isDir && onNewSessionHere != null) TextButton(onClick = { longPressed = null; onNewSessionHere(n.guestPath) }) { Text("在此新建会话") }
                    if (onMention != null) TextButton(onClick = { longPressed = null; onMention(n) }) { Text("在对话中引用") }
                    if (!n.isDir) TextButton(onClick = { longPressed = null; onOpenFile(n) }) { Text("打开") }
                }
            },
            dismissButton = { TextButton(onClick = { longPressed = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun Breadcrumbs(dir: String, roots: List<String>, onClick: (String) -> Unit) {
    val root = roots.filter { dir == it || dir.startsWith("$it/") }.maxByOrNull { it.length } ?: "/"
    val rest = dir.removePrefix(root).trim('/').split('/').filter { it.isNotEmpty() }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb(root, onClick = { onClick(root) })
        var acc = root
        rest.forEach { seg ->
            acc = FilesController.child(acc, seg)
            val target = acc
            Text(" › ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Crumb(seg, onClick = { onClick(target) })
        }
    }
}

@Composable
private fun Crumb(label: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.clickable(onClick = onClick)) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileRow(
    n: FileNode,
    badge: Pair<io.github.zr987459.pigo.feature.files.browser.GitBadge, androidx.compose.ui.graphics.Color>?,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bg = if (highlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else androidx.compose.ui.graphics.Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(bg).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 10.dp).testTag("files.row:" + n.name),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Fmt.glyph(n), modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(
                n.name + if (n.isSymlink) " →" else "",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (highlighted) FontWeight.SemiBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (n.hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (!n.isDir) {
                Text(Fmt.size(n.sizeBytes) + "  " + Fmt.time(n.modifiedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (highlighted) AssistChip(onClick = onClick, label = { Text("本轮改动") }, modifier = Modifier.padding(start = 4.dp))
        badge?.let { (b, c) ->
            Spacer(Modifier.width(6.dp))
            Text(b.label, color = c, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}
