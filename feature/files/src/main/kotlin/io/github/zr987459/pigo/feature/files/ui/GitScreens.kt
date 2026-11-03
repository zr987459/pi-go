package io.github.zr987459.pigo.feature.files.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zr987459.pigo.contract.DiffLineKind
import io.github.zr987459.pigo.contract.FileDiff
import io.github.zr987459.pigo.contract.GitFileChange
import io.github.zr987459.pigo.contract.GitStatus
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.WorkspaceFs
import io.github.zr987459.pigo.feature.files.browser.FilesController

/** 一个改动文件在列表里属于哪一组。 */
internal enum class ChangeGroup(val title: String) { CONFLICT("冲突"), STAGED("已暂存"), UNSTAGED("未暂存"), UNTRACKED("未跟踪") }

internal fun groupChanges(files: List<GitFileChange>): Map<ChangeGroup, List<GitFileChange>> {
    val m = LinkedHashMap<ChangeGroup, MutableList<GitFileChange>>()
    for (f in files) {
        val b = FilesController.badgeOf(f)
        when {
            b == io.github.zr987459.pigo.feature.files.browser.GitBadge.CONFLICT -> m.getOrPut(ChangeGroup.CONFLICT) { ArrayList() } += f
            f.untracked -> m.getOrPut(ChangeGroup.UNTRACKED) { ArrayList() } += f
            else -> {
                if (f.indexStatus != '.') m.getOrPut(ChangeGroup.STAGED) { ArrayList() } += f
                if (f.worktreeStatus != '.') m.getOrPut(ChangeGroup.UNSTAGED) { ArrayList() } += f
            }
        }
    }
    return ChangeGroup.entries.mapNotNull { g -> m[g]?.let { g to it.toList() } }.toMap()
}

/** Git 状态（F4）：分支、领先/落后、按「冲突 / 已暂存 / 未暂存 / 未跟踪」分组的改动列表；点开看 diff。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitStatusScreen(
    fs: WorkspaceFs,
    cwd: String,
    onOpenDiff: (path: String, staged: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var tick by remember { mutableIntStateOf(0) }
    val st by produceState<Outcome<GitStatus>?>(null, cwd, tick) { value = fs.gitStatus(cwd) }
    Scaffold(
        modifier = modifier.testTag("files.gitStatus"),
        topBar = {
            TopAppBar(
                title = { Text("Git 改动") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { IconButton(onClick = { tick++ }) { Icon(Icons.Filled.Refresh, "刷新") } },
            )
        },
    ) { pad ->
        when (val r = st) {
            null -> CenterMessage("", Modifier.padding(pad), loading = true)
            is Outcome.Err -> CenterMessage(r.message, Modifier.padding(pad))
            is Outcome.Ok -> {
                val g = r.value
                if (!g.isRepo) { CenterMessage("当前目录不是 Git 仓库", Modifier.padding(pad)); return@Scaffold }
                val groups = groupChanges(g.files)
                LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                    item {
                        Text(
                            "分支 ${g.branch ?: "?"}" + (g.upstream?.let { " → $it" } ?: "") + (if (g.ahead > 0) "  领先 ${g.ahead}" else "") + (if (g.behind > 0) "  落后 ${g.behind}" else ""),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    if (groups.isEmpty()) item { CenterMessage("工作区很干净，没有改动") }
                    groups.forEach { (grp, list) ->
                        item(key = "h:" + grp.name) {
                            Text("${grp.title}（${list.size}）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                        }
                        items(list, key = { grp.name + ":" + it.path }) { f ->
                            val b = FilesController.badgeOf(f)
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenDiff(f.path, grp == ChangeGroup.STAGED) }.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(b.label, color = badgeColor(b), fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(f.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        (f.origPath?.let { "$it → " } ?: "") + f.path,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 单个文件的 diff 页。[path] 为相对仓库根的路径或容器绝对路径。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffScreen(fs: WorkspaceFs, cwd: String, path: String, staged: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val d by produceState<Outcome<List<FileDiff>>?>(null, cwd, path, staged) { value = fs.gitDiff(cwd, path, staged) }
    Scaffold(
        modifier = modifier.testTag("files.diff"),
        topBar = {
            TopAppBar(
                title = { Text(path.substringAfterLast('/') + if (staged) "（已暂存）" else "", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { pad ->
        when (val r = d) {
            null -> CenterMessage("", Modifier.padding(pad), loading = true)
            is Outcome.Err -> CenterMessage(r.message, Modifier.padding(pad))
            is Outcome.Ok -> {
                val f = r.value.firstOrNull()
                if (f == null) CenterMessage("没有可显示的改动", Modifier.padding(pad)) else DiffView(f, Modifier.padding(pad).fillMaxSize())
            }
        }
    }
}

/** 统一 diff 视图：新旧行号两栏、增删着色、hunk 头。二进制文件只给提示。 */
@Composable
fun DiffView(d: FileDiff, modifier: Modifier = Modifier) {
    if (d.binary) { CenterMessage("二进制文件有改动，不能显示差异", modifier); return }
    val addBg = Color(0x332EA043)
    val delBg = Color(0x33F85149)
    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant
    val hScroll = rememberScrollState()
    Column(modifier) {
        Text(
            (d.oldPath?.let { "$it → " } ?: "") + d.path + "   +${d.added} −${d.deleted}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        HorizontalDivider()
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().horizontalScroll(hScroll).testTag("files.diffLines")) {
                d.hunks.forEach { h ->
                    item { Text(h.header, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 2.dp)) }
                    items(h.lines) { l ->
                        val bg = when (l.kind) { DiffLineKind.ADD -> addBg; DiffLineKind.DELETE -> delBg; else -> Color.Transparent }
                        val sign = when (l.kind) { DiffLineKind.ADD -> "+"; DiffLineKind.DELETE -> "−"; DiffLineKind.CONTEXT -> " "; DiffLineKind.META -> "" }
                        Row(Modifier.background(bg)) {
                            Text((l.oldNo?.toString() ?: "").padStart(5), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = metaColor)
                            Text((l.newNo?.toString() ?: "").padStart(5), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = metaColor)
                            Text(
                                " $sign ${l.text}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                softWrap = false,
                                color = if (l.kind == DiffLineKind.META) metaColor else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(end = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
