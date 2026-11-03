package io.github.zr987459.pigo.feature.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SessionRepository
import io.github.zr987459.pigo.contract.SessionSummary
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * 导航层（`:app` 的 `PiGoNavHost`）当前调用的入口：只有仓库，没有引擎，**也不知道当前会话路径**。
 * 因此这里不能删除「可能正在使用」的会话（最近修改的、10 分钟内改过的，见 [SessionFormat.deleteGuard]），
 * 确认框会说明原因；其他会话照常移入回收站。
 * 打开 / 新建 / 删除当前会话前先 `new_session` 需要引擎，由 mvp/integration 改为直接调用 [SessionsScreen]
 * 并传入 `currentGuestPath`、`onOpen`、`onNewSession`、`onBeforeDeleteCurrent`。
 */
@Composable
fun SessionsPlaceholder(repository: SessionRepository, modifier: Modifier = Modifier) {
    SessionsScreen(repository = repository, modifier = modifier)
}

/**
 * 会话列表 + 回收站（DESIGN.md §3.8、§5.6 L1、L4）。
 *
 * @param currentGuestPath 当前打开的会话；删除它时会先调用 [onBeforeDeleteCurrent]（引擎 `new_session`）。
 *   为 null 表示「不知道当前会话」，此时最近修改的会话与 10 分钟内改过的会话禁止删除（[SessionFormat.deleteGuard]）。
 * @param onOpen 点击会话（引擎 `openSession`，同 cwd 切换、不同 cwd 重启）。为 null 时点击只提示。
 * @param onNewSession 新建会话；为 null 时不显示「新建」按钮。
 * @param onBeforeDeleteCurrent 删除当前会话前调用；返回失败原因（null 表示成功），失败则不删除。
 *   为 null 时当前会话不能删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    repository: SessionRepository,
    modifier: Modifier = Modifier,
    currentGuestPath: String? = null,
    onOpen: (suspend (SessionSummary) -> Unit)? = null,
    onNewSession: (suspend () -> Unit)? = null,
    onBeforeDeleteCurrent: (suspend () -> String?)? = null,
) {
    val sessions by repository.observe().collectAsState(initial = null)
    val trash by repository.observeTrash().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<SessionSummary?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(repository) { repository.refresh() }

    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    Scaffold(
        modifier = modifier.testTag("page:sessions"),
        topBar = {
            TopAppBar(
                title = { Text("会话") },
                actions = {
                    if (onNewSession != null) {
                        IconButton(
                            onClick = { scope.launch { onNewSession() } },
                            modifier = Modifier.testTag("sessions:new"),
                        ) { Icon(Icons.Filled.Add, contentDescription = "新建会话") }
                    }
                    IconButton(onClick = { scope.launch { repository.refresh() } }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("全部会话") })
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(if (trash.isEmpty()) "回收站" else "回收站（${trash.size}）") },
                    modifier = Modifier.testTag("sessions:trashTab"),
                )
            }
            val now = Instant.now()
            when (tab) {
                0 -> {
                    val list = sessions
                    when {
                        list == null -> Empty("正在读取会话…")
                        list.isEmpty() -> Empty("还没有会话。\n去「聊天」页发一条消息，就会自动创建第一个会话。")
                        else -> LazyColumn(
                            Modifier.fillMaxSize().testTag("sessions:list"),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        ) {
                            items(SessionFormat.sorted(list), key = { it.guestPath }) { s ->
                                SessionRow(
                                    s = s,
                                    now = now,
                                    isCurrent = s.guestPath == currentGuestPath,
                                    onClick = {
                                        val open = onOpen
                                        if (open == null) toast("打开会话需要先启动引擎") else scope.launch { open(s) }
                                    },
                                    trailing = {
                                        IconButton(onClick = { pendingDelete = s }, enabled = !busy) {
                                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                else -> {
                    if (trash.isEmpty()) {
                        Empty("回收站是空的。\n删除的会话会先放在这里，可以随时恢复。")
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize().testTag("sessions:trash"),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        ) {
                            item {
                                Text(
                                    "这里的会话不会自动清空。点「恢复」放回会话列表。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            items(SessionFormat.sorted(trash), key = { it.guestPath }) { s ->
                                SessionRow(s = s, now = now, isCurrent = false, onClick = null, trailing = {
                                    TextButton(enabled = !busy, onClick = {
                                        busy = true
                                        scope.launch {
                                            when (val r = repository.restore(s.guestPath)) {
                                                is Outcome.Ok -> toast("已恢复「${SessionFormat.title(s)}」")
                                                is Outcome.Err -> toast(r.message)
                                            }
                                            busy = false
                                        }
                                    }) { Text("恢复") }
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { s ->
        val guard = SessionFormat.deleteGuard(
            target = s,
            all = sessions.orEmpty(),
            currentGuestPath = currentGuestPath,
            canStartNewSession = onBeforeDeleteCurrent != null,
            now = Instant.now(),
        )
        val isCurrent = guard is SessionFormat.DeleteGuard.NewSessionFirst
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (guard is SessionFormat.DeleteGuard.Blocked) "暂时不能删除" else "删除这个会话？") },
            text = {
                Text(
                    when (guard) {
                        is SessionFormat.DeleteGuard.Blocked -> guard.reason
                        else -> "「${SessionFormat.title(s)}」会移到回收站，之后可以在「回收站」里恢复。" +
                            if (isCurrent) "\n\n这是正在使用的会话，删除前会先为你新建一个空会话。" else ""
                    },
                    modifier = Modifier.testTag("sessions:deleteMessage"),
                )
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("sessions:confirmDelete"),
                    enabled = guard !is SessionFormat.DeleteGuard.Blocked,
                    onClick = {
                        pendingDelete = null
                        busy = true
                        scope.launch {
                            try {
                                if (isCurrent) {
                                    val before = onBeforeDeleteCurrent ?: return@launch // deleteGuard 已保证非 null
                                    val err = before()
                                    if (err != null) {
                                        toast("没能先新建会话：$err")
                                        return@launch
                                    }
                                }
                                when (val r = repository.moveToTrash(s.guestPath)) {
                                    is Outcome.Ok -> toast("已移到回收站")
                                    is Outcome.Err -> toast(r.message)
                                }
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(if (guard is SessionFormat.DeleteGuard.Blocked) "知道了" else "取消")
                }
            },
        )
    }
}

@Composable
private fun SessionRow(
    s: SessionSummary,
    now: Instant,
    isCurrent: Boolean,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
        colors = if (isCurrent) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    (if (isCurrent) "【当前】" else "") + SessionFormat.title(s),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                SessionFormat.subtitlePreview(s)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "${SessionFormat.cwd(s)} · ${SessionFormat.updated(s.updatedAt, now)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing()
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
