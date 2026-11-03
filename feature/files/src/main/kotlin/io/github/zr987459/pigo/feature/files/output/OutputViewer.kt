package io.github.zr987459.pigo.feature.files.output

import io.github.zr987459.pigo.feature.files.ui.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏输出查看器（F5 第一层）：工具卡、`!` 命令的输出。等宽、ANSI 着色、可搜索（上一个 / 下一个，命中高亮）、
 * 复制全文、换行开关、跳到顶部 / 底部。解析在后台线程做；1 万行用 LazyColumn 只组合可见行。
 *
 * @param text 原始输出（可含 ANSI 转义）
 * @param live 仍在输出中（bash 流式）：新内容到达时如果用户在底部就跟随滚动
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutputViewerScreen(
    title: String,
    text: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    subtitle: String? = null,
) {
    BackHandler(onBack = onBack)
    val clipboard = LocalClipboardManager.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var wrap by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hitIndex by remember { mutableIntStateOf(0) }

    val lines by produceState<List<Ansi.Line>?>(null, text) { value = withContext(Dispatchers.Default) { Ansi.parse(text) } }
    val plain = remember(lines) { lines?.map { it.text }.orEmpty() }
    val hits by produceState(emptyList<OutputSearch.Hit>(), plain, query) { value = withContext(Dispatchers.Default) { OutputSearch.find(plain, query) } }
    val current by remember { derivedStateOf { hits.getOrNull(hitIndex.coerceIn(0, (hits.size - 1).coerceAtLeast(0))) } }

    LaunchedEffect(current) { current?.let { listState.animateScrollToItem(it.line) } }
    LaunchedEffect(lines?.size) {
        val n = lines?.size ?: 0
        if (live && n > 0) {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (last >= n - 3 || listState.layoutInfo.totalItemsCount == 0) listState.scrollToItem(n - 1)
        }
    }

    Scaffold(
        modifier = modifier.testTag("output.viewer"),
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            if (searching) {
                TopAppBar(
                    title = {
                        TextField(
                            value = query,
                            onValueChange = { query = it; hitIndex = 0 },
                            singleLine = true,
                            placeholder = { Text("搜索输出") },
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth().testTag("output.search"),
                        )
                    },
                    navigationIcon = { IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.Filled.Close, "关闭搜索") } },
                    actions = {
                        Text(if (query.isEmpty()) "" else if (hits.isEmpty()) "无结果" else "${hitIndex + 1}/${hits.size}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("output.hits"))
                        IconButton(onClick = { if (hits.isNotEmpty()) hitIndex = (hitIndex - 1 + hits.size) % hits.size }) { Icon(Icons.Filled.KeyboardArrowUp, "上一个") }
                        IconButton(onClick = { if (hits.isNotEmpty()) hitIndex = (hitIndex + 1) % hits.size }, modifier = Modifier.testTag("output.next")) { Icon(Icons.Filled.KeyboardArrowDown, "下一个") }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                            Text(subtitle ?: "${lines?.size ?: 0} 行" + if (live) " · 输出中…" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                    actions = {
                        IconButton(onClick = { searching = true }, modifier = Modifier.testTag("output.searchButton")) { Icon(Icons.Filled.Search, "搜索") }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("复制全文") }, onClick = {
                                menu = false; clipboard.setText(AnnotatedString(Ansi.strip(text))); scope.launch { snack.showSnackbar("已复制") }
                            })
                            DropdownMenuItem(text = { Text(if (wrap) "不自动换行" else "自动换行") }, onClick = { menu = false; wrap = !wrap })
                            DropdownMenuItem(text = { Text("跳到开头") }, onClick = { menu = false; scope.launch { listState.scrollToItem(0) } })
                            DropdownMenuItem(text = { Text("跳到末尾") }, onClick = { menu = false; scope.launch { listState.scrollToItem(((lines?.size ?: 1) - 1).coerceAtLeast(0)) } })
                        }
                    },
                )
            }
        },
    ) { pad ->
        val ls = lines
        if (ls == null) return@Scaffold
        val hScroll = rememberScrollState()
        val dark = MaterialTheme.colorScheme.background.luminanceApprox() < 0.5f
        val hitBg = MaterialTheme.colorScheme.tertiaryContainer
        val curBg = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
        val hitsByLine = remember(hits) { hits.groupBy { it.line } }
        val gutter = ls.size.toString().length
        SelectionContainer(Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = (if (wrap) Modifier.fillMaxSize() else Modifier.fillMaxSize().horizontalScroll(hScroll)).testTag("output.lines"),
            ) {
                itemsIndexed(ls, key = { i, _ -> i }) { i, line ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            (i + 1).toString().padStart(gutter),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                        Text(
                            styled(line, dark, hitsByLine[i], current?.takeIf { it.line == i }, hitBg, curBg),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            softWrap = wrap,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun Color.luminanceApprox(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private fun styled(line: Ansi.Line, dark: Boolean, hits: List<OutputSearch.Hit>?, cur: OutputSearch.Hit?, hitBg: Color, curBg: Color): AnnotatedString =
    buildAnnotatedString {
        append(line.text)
        for (sp in line.spans) {
            val st = sp.style
            var fg = st.fg?.let { Color(it) }
            var bg = st.bg?.let { Color(it) }
            if (st.inverse) { val t = fg; fg = bg ?: if (dark) Color.Black else Color.White; bg = t ?: if (dark) Color.White else Color.Black }
            if (st.dim && fg != null) fg = fg.copy(alpha = 0.6f)
            addStyle(
                SpanStyle(
                    color = fg ?: Color.Unspecified,
                    background = bg ?: Color.Unspecified,
                    fontWeight = if (st.bold) FontWeight.Bold else null,
                    fontStyle = if (st.italic) FontStyle.Italic else null,
                    textDecoration = if (st.underline) TextDecoration.Underline else null,
                ),
                sp.start,
                sp.end,
            )
        }
        hits?.forEach { h -> addStyle(SpanStyle(background = if (h == cur) curBg else hitBg), h.start.coerceAtMost(line.text.length), h.end.coerceAtMost(line.text.length)) }
    }
