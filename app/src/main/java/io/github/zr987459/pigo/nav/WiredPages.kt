package io.github.zr987459.pigo.nav

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import io.github.zr987459.pigo.feature.files.FilesDeps
import io.github.zr987459.pigo.feature.files.FilesHost
import io.github.zr987459.pigo.feature.files.attach.AttachmentController
import io.github.zr987459.pigo.feature.files.attach.AttachmentSheet
import io.github.zr987459.pigo.feature.files.ui.LocalFilesBackHandler
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.AppGraph
import io.github.zr987459.pigo.contract.AuthState
import io.github.zr987459.pigo.contract.ModelAvailability
import io.github.zr987459.pigo.contract.ModelRef
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.ProviderInfo
import io.github.zr987459.pigo.contract.TimelineExtra
import io.github.zr987459.pigo.core.ui.AppBackdrop
import io.github.zr987459.pigo.core.ui.AppShape
import io.github.zr987459.pigo.core.ui.GlassSurface
import io.github.zr987459.pigo.core.ui.GlassTier
import io.github.zr987459.pigo.feature.commands.skills.SkillsController
import io.github.zr987459.pigo.feature.commands.ui.SkillsContent
import io.github.zr987459.pigo.feature.models.CustomProviderScreen
import io.github.zr987459.pigo.feature.models.NetworkAndCostScreen
import io.github.zr987459.pigo.feature.models.ProviderConfigScreen
import io.github.zr987459.pigo.feature.models.ProviderListScreen
import io.github.zr987459.pigo.feature.usage.logic.SessionTreeController
import io.github.zr987459.pigo.feature.usage.logic.UsageController
import io.github.zr987459.pigo.feature.usage.ui.CompactionCard
import io.github.zr987459.pigo.feature.usage.ui.SessionTreeContent
import io.github.zr987459.pigo.feature.usage.ui.TurnSummaryRow
import io.github.zr987459.pigo.feature.usage.ui.UsageSheetContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * W5 接线：新页面的路由外壳（顶部「返回 + 标题」+ 页面内容）与时间线附加项的渲染。
 * 页面本身来自 feature/models（W2）、feature/commands、feature/usage（W4）；文件页等 W3 合入。
 */

/** 页面外壳：dsh-Go 的二级页——背景 + 顶部返回键与标题。 */
@Composable
internal fun SubPage(title: String, tag: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    AppBackdrop(Modifier.testTag("page:$tag")) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.testTag("page:back")) { Text("‹ 返回") }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

/**
 * 「回到聊天自动选中刚配置的模型」（feature-gap §3.2 W5 验收①）。
 * W2 的 ModelPicker 点未配置模型时只回调服务商 id（`onConfigure(provider)`），所以这里记下服务商；
 * 配置完成回到聊天时，若之前点的那个服务商已配置，就选它目录里第一个可用模型（缺口已写进 PR 描述）。
 */
object PendingModel {
    val provider = MutableStateFlow<String?>(null)
}

internal suspend fun applyPendingModel(graph: AppGraph): String? {
    val p = PendingModel.provider.value ?: return null
    val state = graph.models.auth.state(p)
    if (!state.configured) return null
    PendingModel.provider.value = null
    val entries = (graph.models.catalog.entries(p) as? Outcome.Ok)?.value.orEmpty()
    val ready = entries.firstOrNull { it.availability == ModelAvailability.READY } ?: entries.firstOrNull() ?: return null
    return when (val r = graph.engine.setModel(ready.model.provider, ready.model.id)) {
        is Outcome.Ok -> {
            graph.models.catalog.markUsed(ModelRef(ready.model.provider, ready.model.id))
            "已切换到 ${ready.model.name ?: ready.model.id}"
        }
        is Outcome.Err -> "切换模型失败：" + r.message
    }
}

/** 设置首页（W5）：服务商与登录（W2）、代理与费用显示、运行环境与高级（旧设置页）、导出（W3）。 */
@Composable
internal fun SettingsHub(onOpen: (String) -> Unit, onBack: () -> Unit) {
    SubPage("设置", "settingsHub", onBack) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HubRow("服务商与登录", "42 个服务商的 Key / OAuth、自定义端点", "hub:providers") { onOpen(Routes.PROVIDERS) }
            HubRow("代理、费用显示与会话默认", "HTTP 代理、人民币换算", "hub:network") { onOpen(Routes.NETWORK) }
            HubRow("运行环境与高级", "修复运行环境、旧版模型设置", "hub:advanced") { onOpen(Routes.ADVANCED) }
            HubRow("文件与导出", "浏览工作区、Git 改动；导出会话在会话列表长按或文件页", "hub:export") { onOpen(Routes.FILES) }
        }
    }
}

@Composable
private fun HubRow(title: String, subtitle: String, tag: String, onClick: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag), GlassTier.Chrome, AppShape.Lg) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ProvidersPage(graph: AppGraph, onOpen: (String) -> Unit, onAddCustom: () -> Unit, onBack: () -> Unit) {
    SubPage("服务商与登录", "providers", onBack) {
        ProviderListScreen(graph.models.catalog, graph.models.auth, onOpen = { onOpen(it.id) }, onAddCustom = onAddCustom)
    }
}

@Composable
internal fun ProviderConfigPage(graph: AppGraph, providerId: String, onDone: () -> Unit, onBack: () -> Unit) {
    var provider by remember { mutableStateOf<ProviderInfo?>(null) }
    var missing by remember { mutableStateOf(false) }
    LaunchedEffect(providerId) {
        val list = (graph.models.catalog.providers() as? Outcome.Ok)?.value.orEmpty()
        provider = list.firstOrNull { it.id == providerId }
        missing = provider == null
    }
    val states by graph.models.auth.observe().collectAsState(initial = emptyMap())
    SubPage(provider?.displayName ?: providerId, "provider", onBack) {
        provider?.let { p ->
            ProviderConfigScreen(
                p,
                graph.models.auth,
                onChanged = { if ((states[p.id] ?: AuthState.None).configured || PendingModel.provider.value == p.id) onDone() },
                savedFields = graph.models.savedFields,
            )
        }
        if (missing) Text("找不到服务商 $providerId", Modifier.padding(16.dp))
    }
}

@Composable
internal fun CustomProviderPage(graph: AppGraph, onBack: () -> Unit) {
    SubPage("自定义端点", "customProvider", onBack) {
        CustomProviderScreen(graph.models.custom, existing = null, onSaved = onBack)
    }
}

@Composable
internal fun NetworkPage(graph: AppGraph, onBack: () -> Unit) {
    SubPage("代理与费用显示", "network", onBack) { NetworkAndCostScreen(graph.models.settings) }
}

@Composable
internal fun SessionTreePage(graph: AppGraph, onForked: () -> Unit, onError: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val controller = remember(graph) { SessionTreeController(graph.extras, graph.appScope, fork = { graph.chatDeps.controller.forkFrom(it) }) }
    LaunchedEffect(controller) { controller.load() }
    val state by controller.state.collectAsState()
    SubPage("会话树", "tree", onBack) {
        SessionTreeContent(
            state,
            onToggle = controller::toggle,
            onFork = { id ->
                scope.launch {
                    when (val r = controller.forkFrom(id)) {
                        is Outcome.Ok -> { runCatching { graph.sessions.refresh() }; onForked() }
                        is Outcome.Err -> onError("分叉失败：" + r.message)
                    }
                }
            },
            onClone = {
                scope.launch {
                    when (val r = controller.cloneSession()) {
                        is Outcome.Ok -> { runCatching { graph.sessions.refresh() }; onForked() }
                        is Outcome.Err -> onError("复制会话失败：" + r.message)
                    }
                }
            },
        )
    }
}

@Composable
internal fun SkillsPage(graph: AppGraph, onBack: () -> Unit) {
    val manager = graph.commands.skills
    SubPage("技能与模板", "skills", onBack) {
        if (manager == null) {
            Text("技能与模板需要运行环境（当前为演示模式）", Modifier.padding(16.dp))
            return@SubPage
        }
        val controller = remember(graph) { SkillsController(graph.extras, manager, graph.appScope) }
        LaunchedEffect(controller) { controller.load() }
        val state by controller.state.collectAsState()
        SkillsContent(
            state,
            onView = controller::view,
            onCloseView = controller::closeView,
            onToggle = controller::setEnabled,
            onCreate = { n, d, b -> controller.createTemplate(n, d, b) },
        )
    }
}

/** 用量（U1–U3）：抽屉进入时的整页版；聊天页点上下文小环时以底部弹层显示同一内容。 */
@Composable
internal fun UsageContent(graph: AppGraph, modifier: Modifier = Modifier) {
    val controller = remember(graph) { usageController(graph) }
    LaunchedEffect(controller) { controller.refresh() }
    val state by controller.state.collectAsState()
    UsageSheetContent(state, onCompact = controller::compact, onRefresh = controller::refresh, onCnyRate = controller::setCnyRate, modifier = modifier)
}

private var usageSingleton: Pair<AppGraph, UsageController>? = null

/** 与 App 同寿的用量控制器（弹层开关不重建，压缩进行中关掉弹层也不丢状态）。 */
internal fun usageController(graph: AppGraph): UsageController =
    usageSingleton?.takeIf { it.first === graph }?.second
        ?: UsageController(graph.extras, graph.appScope, graph.models.settings).also { usageSingleton = graph to it }

@Composable
internal fun UsagePage(graph: AppGraph, onBack: () -> Unit) {
    SubPage("用量", "usage", onBack) { UsageContent(graph, Modifier.verticalScroll(rememberScrollState())) }
}

/** 文件页（W3 FilesHost）：从当前会话的 cwd 开始；[openPath] 直接打开某个文件（轮末汇总里点文件）。 */
@Composable
internal fun FilesPage(graph: AppGraph, openPath: String?, onNewSessionHere: (String) -> Unit, onMention: (String) -> Unit, onBack: () -> Unit) {
    val sessionFile by remember(graph) { graph.engine.chat.map { it.sessionFile }.distinctUntilChanged() }
        .collectAsState(initial = graph.engine.chat.value.sessionFile)
    val sessions by graph.sessions.observe().collectAsState(initial = emptyList())
    val cwd = sessions.firstOrNull { it.guestPath == sessionFile }?.cwd?.ifBlank { null } ?: FilesDeps.DEFAULT_CWD
    AppBackdrop(Modifier.testTag("page:files")) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            CompositionLocalProvider(LocalFilesBackHandler provides { enabled, cb -> BackHandler(enabled, cb) }) {
                FilesHost(
                    graph.workspace.deps,
                    sessionCwd = cwd,
                    onClose = onBack,
                    openPath = openPath,
                    onNewSessionHere = onNewSessionHere,
                    onMention = { node -> onMention(node.guestPath) },
                )
            }
        }
    }
}

/** 与 App 同寿的附件控制器（输入框「+」）。 */
private var attachSingleton: Pair<AppGraph, AttachmentController>? = null

internal fun attachmentController(graph: AppGraph): AttachmentController =
    attachSingleton?.takeIf { it.first === graph }?.second
        ?: AttachmentController(
            graph.workspace.attachments,
            graph.appScope,
            cwd = { FilesDeps.DEFAULT_CWD },
            supportsImages = { graph.engine.chat.value.model?.let { m -> (m.raw["input"] as? kotlinx.serialization.json.JsonArray)?.any { (it as? kotlinx.serialization.json.JsonPrimitive)?.content == "image" } ?: true } ?: true },
        ).also { attachSingleton = graph to it }

/**
 * 输入框「+」→ W3 附件弹层。关闭弹层时把处理好的附件交给聊天控制器：图片进待发列表，文件以引用文字追加到草稿。
 */
@Composable
internal fun AttachSheetBridge(graph: AppGraph, onDone: () -> Unit) {
    val att = remember(graph) { attachmentController(graph) }
    AttachmentSheet(att) {
        val (refs, images) = att.compose("")
        val chat = graph.chatDeps.controller
        if (images.isNotEmpty()) chat.addImages(images)
        if (refs.isNotBlank()) {
            val d = chat.state.value.composer.draft
            chat.setDraft(if (d.isBlank()) refs.trim() + " " else d.trimEnd() + "\n" + refs.trim() + " ")
        }
        att.clear()
        onDone()
    }
}

// ---------------- 时间线附加项 ----------------

@Composable
internal fun TimelineExtraView(extra: TimelineExtra, onOpenFile: (String) -> Unit, modifier: Modifier) {
    when (extra) {
        is TimelineExtra.Compaction -> CompactionCard(extra, modifier.testTag("extra:compaction"))
        is TimelineExtra.TurnSummary -> TurnSummaryRow(extra, onOpenFile, modifier)
        is TimelineExtra.Bash -> BashCard(extra, modifier)
        is TimelineExtra.UserImages -> UserImagesRow(extra, modifier)
    }
}

@Composable
private fun BashCard(extra: TimelineExtra.Bash, modifier: Modifier) {
    GlassSurface(modifier.fillMaxWidth().testTag("extra:bash"), GlassTier.Chrome, AppShape.Md) {
        Column(Modifier.padding(12.dp)) {
            Text("$ " + extra.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            if (extra.output.isNotBlank()) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(extra.output.takeLast(4000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 30)
            }
            val tail = when {
                extra.running -> "运行中…"
                extra.exitCode != null && extra.exitCode != 0 -> "退出码 ${extra.exitCode}"
                else -> null
            }
            tail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (extra.excludedFromContext) Text("（不进入上下文）", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun UserImagesRow(extra: TimelineExtra.UserImages, modifier: Modifier) {
    Row(modifier.fillMaxWidth().testTag("extra:images"), horizontalArrangement = Arrangement.End) {
        for (img in extra.images.take(4)) {
            val bmp = remember(img.base64Data) {
                runCatching {
                    val bytes = Base64.decode(img.base64Data, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
            if (bmp != null) {
                Image(bmp, img.displayName ?: "图片", Modifier.padding(start = 6.dp).size(96.dp), contentScale = ContentScale.Crop)
            } else {
                Text("[图片]", Modifier.padding(start = 6.dp))
            }
        }
        Spacer(Modifier.size(0.dp))
    }
}

/** 新路由（W5）。 */
object Routes {
    const val SETTINGS_HUB = "settingsHub"
    const val PROVIDERS = "providers"
    const val PROVIDER = "provider/{id}"
    const val CUSTOM_PROVIDER = "customProvider"
    const val NETWORK = "network"
    const val ADVANCED = "settings"
    const val TREE = "tree"
    const val SKILLS = "skills"
    const val USAGE = "usage"
    const val FILES = "files"
    const val FILES_PATTERN = "files?path={path}"
    fun provider(id: String) = "provider/$id"
    fun files(path: String?) = if (path == null) FILES else "files?path=" + android.net.Uri.encode(path)
}
