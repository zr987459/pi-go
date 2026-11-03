package io.github.zr987459.pigo.feature.sessions

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.core.ui.AppIcon
import io.github.zr987459.pigo.core.ui.AppIcons
import io.github.zr987459.pigo.core.ui.AppMotion
import io.github.zr987459.pigo.core.ui.AppType
import io.github.zr987459.pigo.core.ui.ChromeIconButton
import io.github.zr987459.pigo.core.ui.GlassTier
import io.github.zr987459.pigo.core.ui.DshTheme
import io.github.zr987459.pigo.core.ui.Glyph
import io.github.zr987459.pigo.contract.SessionRepository
import io.github.zr987459.pigo.contract.SessionSummary
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * 导航层（`:app` 的 `PiGoNavHost`）当前调用的入口：只有仓库，没有引擎，**也不知道当前会话路径**。
 * 因此这里不能删除「可能正在使用」的会话（最近修改的、10 分钟内改过的，见 [SessionFormat.deleteGuard]），
 * 确认框会说明原因；其他会话照常移入回收站。
 */
@Composable
fun SessionsPlaceholder(repository: SessionRepository, modifier: Modifier = Modifier) {
    SessionsScreen(repository = repository, modifier = modifier)
}

/**
 * 侧边抽屉里的会话面板（DESIGN.md §3.8、§5.6 L1、L4），布局照搬 dsh-Go
 * `app/lib/ui/shell/sessions_drawer.dart`：品牌行 + 实心「新对话」胶囊、胶囊搜索框、
 * 「全部 / 回收站」筛选胶囊、按 今天 / 昨天 / 本周 / 更早 分组的会话行（当前会话高亮加粗）、
 * 底部身份栏（本机 + 刷新 + 设置圆钮）。长按会话行弹出操作面板（删除）。
 *
 * 本组件**不画面板底色和圆角**：放进抽屉时由抽屉容器（core/ui / app 导航）提供玻璃面板，
 * 单独作为页面时透出页面背景。
 *
 * @param currentGuestPath 当前打开的会话；删除它时会先调用 [onBeforeDeleteCurrent]（引擎 `new_session`）。
 *   为 null 表示「不知道当前会话」，此时最近修改的会话与 10 分钟内改过的会话禁止删除（[SessionFormat.deleteGuard]）。
 * @param onOpen 点击会话（引擎 `openSession`）。为 null 时点击只提示。
 * @param onNewSession 新建会话；为 null 时不显示「新对话」按钮。
 * @param onBeforeDeleteCurrent 删除当前会话前调用；返回失败原因（null 表示成功），失败则不删除。为 null 时当前会话不能删除。
 * @param onOpenSettings 身份栏的「设置」圆钮；为 null 时不显示。
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
    onOpenSettings: (() -> Unit)? = null,
) {
    val sessions by repository.observe().collectAsState(initial = null)
    val trash by repository.observeTrash().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<SessionSummary?>(null) }
    var actionsFor by remember { mutableStateOf<SessionSummary?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(repository) { repository.refresh() }

    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    Box(modifier.fillMaxSize().testTag("page:sessions")) {
        Column(Modifier.fillMaxSize()) {
            BrandRow(onNewChat = onNewSession?.let { f -> { scope.launch { f() } } })
            SearchCapsule(query = query, onQuery = { query = it })
            FilterBar(
                tab = tab,
                trashCount = trash.size,
                onTab = { tab = it },
            )
            val now = Instant.now()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> {
                        val list = sessions
                        when {
                            list == null -> SkeletonList()
                            list.isEmpty() -> EmptyState("还没有对话", "点「新对话」或在聊天页发一条消息，就会自动创建第一个会话。")
                            else -> {
                                val shown = DrawerGrouping.filter(list, query)
                                if (shown.isEmpty()) {
                                    EmptyState("没有找到相关对话", "换个关键字试试。")
                                } else {
                                    val groups = DrawerGrouping.group(shown, now)
                                    LazyColumn(
                                        Modifier.fillMaxSize().testTag("sessions:list"),
                                        contentPadding = PaddingValues(start = DrawerStyle.contentEdge, end = DrawerStyle.contentEdge, bottom = 16.dp),
                                    ) {
                                        groups.forEach { g ->
                                            item(key = "h-${g.day}") { DrawerEyebrow(g.day.label) }
                                            items(g.sessions, key = { it.guestPath }) { s ->
                                                SessionTile(
                                                    title = SessionFormat.title(s),
                                                    current = s.guestPath == currentGuestPath,
                                                    onTap = {
                                                        val open = onOpen
                                                        if (open == null) toast("打开会话需要先启动引擎") else scope.launch { open(s) }
                                                    },
                                                    onLongPress = { if (!busy) actionsFor = s },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        val shown = DrawerGrouping.filter(trash, query)
                        if (trash.isEmpty()) {
                            EmptyState("回收站是空的", "删除的会话会先放在这里，可以随时恢复。")
                        } else {
                            LazyColumn(
                                Modifier.fillMaxSize().testTag("sessions:trash"),
                                contentPadding = PaddingValues(start = DrawerStyle.contentEdge, end = DrawerStyle.contentEdge, bottom = 16.dp),
                            ) {
                                item {
                                    Text(
                                        "这里的会话不会自动清空。点「恢复」放回会话列表。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = DshTheme.colors.textFaint,
                                        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 6.dp),
                                    )
                                }
                                items(SessionFormat.sorted(shown), key = { it.guestPath }) { s ->
                                    SessionTile(
                                        title = SessionFormat.title(s),
                                        current = false,
                                        onTap = null,
                                        onLongPress = null,
                                        trailing = {
                                            TextButton(
                                                enabled = !busy,
                                                onClick = {
                                                    busy = true
                                                    scope.launch {
                                                        when (val r = repository.restore(s.guestPath)) {
                                                            is Outcome.Ok -> toast("已恢复「${SessionFormat.title(s)}」")
                                                            is Outcome.Err -> toast(r.message)
                                                        }
                                                        busy = false
                                                    }
                                                },
                                            ) { Text("恢复") }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            IdentityBar(
                onRefresh = { scope.launch { repository.refresh() } },
                onSettings = onOpenSettings,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }

    actionsFor?.let { s ->
        ModalBottomSheet(onDismissRequest = { actionsFor = null }) {
            Column(Modifier.padding(bottom = 12.dp)) {
                Text(
                    SessionFormat.title(s),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
                )
                Text(
                    "${SessionFormat.cwd(s)} · ${SessionFormat.updated(s.updatedAt, Instant.now())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = DshTheme.colors.textFaint,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                )
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                ) {
                    ListItem(
                        headlineContent = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        supportingContent = { Text("移到回收站，之后可以恢复") },
                        leadingContent = { AppIcon(AppIcons.delete, null, size = 22.dp, tint = MaterialTheme.colorScheme.error) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .testTag("sessions:actionDelete")
                            .clickable {
                                actionsFor = null
                                pendingDelete = s
                            },
                    )
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
                ) { Text("删除", color = if (guard is SessionFormat.DeleteGuard.Blocked) Color.Unspecified else MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(if (guard is SessionFormat.DeleteGuard.Blocked) "知道了" else "取消")
                }
            },
        )
    }
}

/** 品牌行：dsh-Go 的 padding (20, 14, 10, 10)，左侧字标、右侧实心「新对话」胶囊。 */
@Composable
internal fun BrandRow(onNewChat: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, top = 14.dp, end = 10.dp, bottom = 10.dp).heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("pi-go", style = AppType.Wordmark, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (onNewChat != null) NewChatButton(onNewChat)
    }
}

/** 「新对话」：抽屉里唯一的实心主色元素，36dp 胶囊（NewChatButton）。 */
@Composable
internal fun NewChatButton(onTap: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onTap,
        shape = CircleShape,
        color = scheme.primary,
        contentColor = scheme.onPrimary,
        modifier = Modifier.heightIn(min = 36.dp).testTag("sessions:new"),
    ) {
        Row(
            Modifier.heightIn(min = 36.dp).padding(start = 12.dp, end = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(AppIcons.newChat, null, size = 20.dp)
            Spacer(Modifier.width(4.dp))
            Text("新对话", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 搜索胶囊（SessionSearchBar）：padding (10, 2, 10, 6)，40dp 高，玻璃胶囊。dsh-Go 点开是搜索页，这里就地筛选。 */
@Composable
internal fun SearchCapsule(query: String, onQuery: (String) -> Unit) {
    val tertiary = DshTheme.colors.textFaint
    Row(
        Modifier
            .padding(start = 10.dp, top = 2.dp, end = 10.dp, bottom = 6.dp)
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(DshTheme.colors.pill.fill)
            .border(0.5.dp, DshTheme.colors.pill.hairline, CircleShape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(AppIcons.search, null, size = 20.dp, tint = tertiary)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("搜索对话", style = MaterialTheme.typography.bodyMedium, color = tertiary, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().testTag("sessions:search"),
            )
        }
        if (query.isNotEmpty()) {
            AppIcon(
                AppIcons.clear,
                "清除搜索",
                Modifier.clip(CircleShape).clickable { onQuery("") },
                size = 20.dp,
                tint = tertiary,
            )
        }
    }
}

/** 筛选胶囊（DrawerFilterBar + CapsuleChoiceChip）：padding (10, 2, 10, 4)，间距 8。 */
@Composable
internal fun FilterBar(tab: Int, trashCount: Int, onTab: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, top = 2.dp, end = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CapsuleChip("全部", selected = tab == 0, onClick = { onTab(0) })
        CapsuleChip(
            if (trashCount == 0) "回收站" else "回收站 $trashCount",
            selected = tab == 1,
            onClick = { onTab(1) },
            modifier = Modifier.testTag("sessions:trashTab"),
        )
    }
}

@Composable
internal fun CapsuleChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) scheme.onSurface else DrawerStyle.chipFill(), tween(AppMotion.Quick), label = "chip")
    val fg by animateColorAsState(if (selected) scheme.surface else scheme.onSurfaceVariant, tween(AppMotion.Quick), label = "chipText")
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = bg,
        contentColor = fg,
        modifier = modifier.semantics { this.selected = selected },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** 分组小标题（DrawerEyebrow）：padding (10, 16, 10, 6)。 */
@Composable
internal fun DrawerEyebrow(text: String) {
    Text(
        text,
        style = AppType.Eyebrow, color = DshTheme.colors.textFaint,
        modifier = Modifier.padding(start = 10.dp, top = 16.dp, end = 10.dp, bottom = 6.dp).semantics { heading() },
    )
}

/**
 * 会话行（SessionTile）：44dp 高，14dp 圆角，左右 10dp；当前会话铺 primary 9%（深色 18%）的底并加粗，
 * 底色淡入淡出 160ms。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionTile(
    title: String,
    current: Boolean,
    onTap: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        if (current) DrawerStyle.selection() else DrawerStyle.selection().copy(alpha = 0f),
        tween(AppMotion.Quick, easing = FastOutSlowInEasing),
        label = "tileFill",
    )
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(DrawerStyle.rowHeight)
            .clip(shape)
            .background(fill, shape)
            .let {
                if (onTap != null || onLongPress != null) {
                    it.combinedClickable(
                        role = Role.Button,
                        onClick = { onTap?.invoke() },
                        onLongClick = onLongPress,
                        onLongClickLabel = if (onLongPress != null) "更多操作" else null,
                    )
                } else {
                    it
                }
            }
            .semantics { selected = current }
            .padding(start = 10.dp, end = if (trailing != null) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
            color = scheme.onSurface,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** 底部身份栏（IdentityBar）：padding (10, 9, 10, 13)；左「本机」，右 44dp 圆钮。 */
@Composable
internal fun IdentityBar(onRefresh: () -> Unit, onSettings: (() -> Unit)?) {
    val (bg, fg) = DrawerStyle.identity()
    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, top = 9.dp, end = 10.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = DrawerStyle.identityKey).padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                AppIcon(AppIcons.thisPhone, null, size = 19.dp, tint = fg)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("本机", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text("会话保存在本机", style = MaterialTheme.typography.bodySmall, color = DshTheme.colors.textFaint, maxLines = 1)
            }
        }
        Spacer(Modifier.width(8.dp))
        RoundKey(AppIcons.refresh, "刷新", onRefresh, Modifier.testTag("sessions:refresh"))
        if (onSettings != null) {
            Spacer(Modifier.width(8.dp))
            RoundKey(AppIcons.settings, "设置", onSettings, Modifier.testTag("sessions:settings"))
        }
    }
}

@Composable
private fun RoundKey(icon: Glyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ChromeIconButton(icon, label, onClick, modifier, size = DrawerStyle.identityKey, iconSize = 22.dp, tier = GlassTier.Pill)
}

/** 读取中的骨架（_SkeletonList）：一条 28×8 的组标题 + 五条宽度不一的 10dp 胶囊，2.4s 呼吸（0.55↔1）。 */
@Composable
internal fun SkeletonList() {
    val breath = rememberInfiniteTransition(label = "skeleton")
    val a by breath.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(AppMotion.Breath / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val fill = MaterialTheme.colorScheme.onSurface.copy(alpha = if (DshTheme.isDark) 0.09f else 0.07f)
    Column(
        Modifier.fillMaxSize().alpha(a).padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 16.dp)
            .semantics { contentDescription = "正在读取会话" },
    ) {
        Box(Modifier.padding(top = 19.dp, bottom = 9.dp).size(28.dp, 8.dp).clip(CircleShape).background(fill))
        listOf(0.72f, 0.5f, 0.64f, 0.42f, 0.58f).forEach { w ->
            Box(Modifier.height(44.dp).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth(w).height(10.dp).clip(CircleShape).background(fill))
            }
        }
    }
}

/** 空列表（EmptyView compact）：居中偏上（Alignment(0, -0.4)）。 */
@Composable
internal fun EmptyState(title: String, body: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp), contentAlignment = androidx.compose.ui.BiasAlignment(0f, -0.4f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodySmall, color = DshTheme.colors.textFaint, textAlign = TextAlign.Center)
        }
    }
}
