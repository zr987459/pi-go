package io.github.zr987459.pigo.feature.files.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown
import io.github.zr987459.pigo.contract.FileDiff
import io.github.zr987459.pigo.contract.FileNode
import io.github.zr987459.pigo.contract.FilePreview
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.WorkspaceFs
import io.github.zr987459.pigo.feature.files.platform.FileShare
import io.github.zr987459.pigo.feature.files.platform.Pickers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文件预览（F1）：文本带行号与代码高亮、Markdown 渲染 / 源码切换、图片、其他类型交给系统应用打开；
 * 大文件只读前 1 MB 并提示。菜单：复制全文、分享、另存到手机（F3）、在对话中引用、查看改动（F4）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(
    fs: WorkspaceFs,
    path: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    gitCwd: String? = null,
    onMention: ((FileNode) -> Unit)? = null,
    onOpenDir: ((String) -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    var showDiff by remember { mutableStateOf(false) }
    val preview by produceState<Outcome<FilePreview>?>(null, path) { value = fs.preview(path) }
    val diff by produceState<FileDiff?>(null, path, gitCwd) {
        value = if (gitCwd == null) null else (fs.gitDiff(gitCwd, path) as? Outcome.Ok)?.value?.firstOrNull()?.takeIf { it.hunks.isNotEmpty() }
    }
    val name = path.substringAfterLast('/')

    fun toast(m: String) { scope.launch { snack.showSnackbar(m) } }

    suspend fun staged(): java.io.File? = when (val r = fs.hostFile(path)) {
        is Outcome.Ok -> withContext(Dispatchers.IO) { runCatching { FileShare.stage(ctx, r.value, name) }.getOrNull() }
        is Outcome.Err -> { toast(r.message); null }
    }

    Scaffold(
        modifier = modifier.testTag("files.preview"),
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val f = staged() ?: return@launch
                            if (!FileShare.share(ctx, f, (preview as? Outcome.Ok)?.value?.node?.mimeType ?: "*/*")) toast("没有可以分享的应用")
                        }
                    }) { Icon(Icons.Filled.Share, "分享") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            val p = (preview as? Outcome.Ok)?.value
                            if (p is FilePreview.Text) DropdownMenuItem(text = { Text("复制全文") }, onClick = {
                                menu = false; clipboard.setText(AnnotatedString(p.chunk.text)); toast(if (p.chunk.truncated) "已复制前 1 MB" else "已复制")
                            })
                            DropdownMenuItem(text = { Text("另存到手机…") }, onClick = {
                                menu = false
                                scope.launch {
                                    val target = Pickers.createDocument(ctx, name, p?.node?.mimeType ?: "application/octet-stream") ?: return@launch
                                    val r = withContext(Dispatchers.IO) {
                                        runCatching { ctx.contentResolver.openOutputStream(target, "wt")!!.use { fs.exportTo(path, it) } }
                                            .getOrElse { Outcome.Err(io.github.zr987459.pigo.contract.ErrCode.UNKNOWN, it.message ?: "保存失败") }
                                    }
                                    toast(if (r is Outcome.Ok) "已保存（${Fmt.size(r.value)}）" else (r as Outcome.Err).message)
                                }
                            })
                            if (p != null && onMention != null) DropdownMenuItem(text = { Text("在对话中引用") }, onClick = { menu = false; onMention(p.node) })
                            if (onOpenDir != null) DropdownMenuItem(text = { Text("打开所在文件夹") }, onClick = { menu = false; onOpenDir(path.substringBeforeLast('/').ifEmpty { "/" }) })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (diff != null) {
                TabRow(selectedTabIndex = if (showDiff) 1 else 0) {
                    Tab(selected = !showDiff, onClick = { showDiff = false }, text = { Text("内容") })
                    Tab(selected = showDiff, onClick = { showDiff = true }, text = { Text("改动 +${diff!!.added} −${diff!!.deleted}") })
                }
            }
            if (showDiff && diff != null) {
                DiffView(diff!!, Modifier.fillMaxSize())
                return@Column
            }
            when (val r = preview) {
                null -> CenterMessage("", loading = true)
                is Outcome.Err -> CenterMessage(r.message)
                is Outcome.Ok -> when (val p = r.value) {
                    is FilePreview.Text -> TextPreview(p)
                    is FilePreview.Image -> AsyncImage(
                        model = p.hostFile,
                        contentDescription = p.node.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).testTag("files.image"),
                    )
                    is FilePreview.External -> ExternalPreview(p.node) {
                        scope.launch {
                            val f = staged() ?: return@launch
                            if (!FileShare.openWith(ctx, f, p.node.mimeType ?: "*/*")) toast("没有能打开这种文件的应用")
                        }
                    }
                    is FilePreview.Binary -> CenterMessage("二进制文件（${Fmt.size(p.node.sizeBytes)}），不能预览。可分享或另存到手机。")
                    is FilePreview.Directory -> CenterMessage("这是一个文件夹")
                }
            }
        }
    }
}

@Composable
private fun ExternalPreview(n: FileNode, onOpen: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Text(Fmt.glyph(n), style = MaterialTheme.typography.displayMedium)
        Text(n.name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(Fmt.size(n.sizeBytes), style = MaterialTheme.typography.bodySmall)
        Button(onClick = onOpen, modifier = Modifier.padding(top = 16.dp)) { Text("用其他应用打开") }
    }
}

@Composable
private fun TextPreview(p: FilePreview.Text) {
    var rendered by remember(p.node.guestPath) { mutableStateOf(p.markdown) }
    Column(Modifier.fillMaxSize()) {
        if (p.chunk.truncated) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(
                    "文件较大（${Fmt.size(p.chunk.totalBytes)}），只显示前 ${Fmt.size(p.chunk.text.toByteArray().size.toLong())}。完整内容可分享或另存到手机。",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
        if (p.markdown) {
            TabRow(selectedTabIndex = if (rendered) 0 else 1) {
                Tab(selected = rendered, onClick = { rendered = true }, text = { Text("预览") })
                Tab(selected = !rendered, onClick = { rendered = false }, text = { Text("源码") })
            }
        }
        if (p.markdown && rendered) {
            SelectionContainer {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("files.markdown")) {
                    Markdown(content = p.chunk.text)
                }
            }
        } else {
            CodeLines(p.chunk.text, p.chunk.language)
        }
    }
}

/** 带行号的等宽文本；高亮在后台算好再替换，长文件也不卡主线程。 */
@Composable
internal fun CodeLines(text: String, language: String?, modifier: Modifier = Modifier) {
    val lines = remember(text) { text.split('\n').let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it } }
    val dark = isSystemInDarkTheme()
    val highlighted by produceState<List<AnnotatedString>?>(null, text, language, dark) {
        value = withContext(Dispatchers.Default) { Highlighter(language, dark).let { h -> lines.map(h::line) } }
    }
    val gutter = lines.size.toString().length
    val hScroll = rememberScrollState()
    SelectionContainer(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().horizontalScroll(hScroll).testTag("files.text")) {
            itemsIndexed(lines) { i, l ->
                Row {
                    Text(
                        (i + 1).toString().padStart(gutter),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                    )
                    Text(highlighted?.getOrNull(i) ?: AnnotatedString(l), fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false, modifier = Modifier.padding(end = 16.dp))
                }
            }
        }
    }
}
