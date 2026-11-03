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
import androidx.compose.runtime.produceState
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
import io.github.zr987459.pigo.feature.settings.session.SessionDefaultsController
import io.github.zr987459.pigo.feature.settings.session.SessionDefaultsScreen
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
 * 「回到聊天自动选中刚配置的模型」（feature-gap §3.2 W5 验收①，审查第 1 轮 Important 3）。
 * ModelPicker 点未配置模型时经 `onConfigureModel(ModelRef)` 回传**被点的那个模型**，这里记下；
 * 配置完成回到聊天时把引擎切到这个模型。服务商标题上的「去配置」没有具体模型（[ref] 为 null），
 * 此时才退回该服务商目录里第一个可用模型。
 */
object PendingModel {
    data class Target(val provider: String, val ref: ModelRef?)

    val target = MutableStateFlow<Target?>(null)
    val provider: String? get() = target.value?.provider

    fun clear() {
        target.value = null
    }
}

internal suspend fun applyPendingModel(graph: AppGraph): String? {
    val t = PendingModel.target.value ?: return null
    val state = graph.models.auth.state(t.provider)
    if (!state.configured) return null
    PendingModel.clear()
    val entries = (graph.models.catalog.entries(t.provider) as? Outcome.Ok)?.value.orEmpty()
    val chosen = PendingModelLogic.pick(t, entries) ?: return null
    return when (val r = graph.engine.setModel(chosen.provider, chosen.id)) {
        is Outcome.Ok -> {
            graph.models.catalog.markUsed(chosen)
            "已切换到 " + (entries.firstOrNull { it.model.ref == chosen }?.model?.name ?: chosen.id)
        }
        is Outcome.Err -> "切换模型失败：" + r.message
    }
}

/** 纯函数（便于单测）：选回用户点的模型；没有具体模型时取该服务商第一个可用的。 */
internal object PendingModelLogic {
    fun pick(t: PendingModel.Target, entries: List<io.github.zr987459.pigo.contract.CatalogEntry>): ModelRef? {
        t.ref?.let { return it }
        val ready = entries.firstOrNull { it.availability == ModelAvailability.READY } ?: entries.firstOrNull() ?: return null
        return ready.model.ref
    }

    /** 配置页只在「从弹层过来的那个服务商」且「确实已配置」时才算完成，回聊天（审查 Nit：点清除不算）。 */
    fun shouldReturn(pending: PendingModel.Target?, providerId: String, configured: Boolean): Boolean =
        pending?.provider == providerId && configured
}

/** 依赖 W1 extras 的页面在切 REAL 前的占位（深链接 / 测试直达时）。 */
@Composable
internal fun NeedsNewEnginePage(title: String, tag: String, onBack: () -> Unit) {
    SubPage(title, tag, onBack) {
        Text("需要新版引擎：$title 依赖 pi 引擎的扩展能力，当前版本尚未接入。", Modifier.padding(16.dp).testTag("page:needsEngine"))
    }
}

/** 设置首页（W5）：服务商与登录（W2）、代理与费用显示、运行环境与高级（旧设置页）、导出（W3）。 */
@Composable
internal fun SettingsHub(onOpen: (String) -> Unit, onBack: () -> Unit, extrasReady: Boolean = false) {
    SubPage("设置", "settingsHub", onBack) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HubRow("服务商与登录", "42 个服务商的 Key / OAuth、自定义端点", "hub:providers") { onOpen(Routes.PROVIDERS) }
            HubRow("代理与费用显示", "HTTP 代理、人民币换算", "hub:network") { onOpen(Routes.NETWORK) }
            // M8 依赖 W1 的 set_auto_* / set_*_mode：真实 extras 才显示（审查第 1 轮 Important 4）。
            if (extrasReady) {
                HubRow("会话默认", "自动压缩、自动重试、插话与排队方式", "hub:sessionDefaults") { onOpen(Routes.SESSION_DEFAULTS) }
            }
            HubRow("运行环境与高级", "修复运行环境、旧版模型设置", "hub:advanced") { onOpen(Routes.ADVANCED) }
            HubRow("文件与导出", "浏览工作区、Git 改动；导出会话：在会话列表里长按会话", "hub:export") { onOpen(Routes.FILES) }
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
    val scope = rememberCoroutineScope()
    // 不管怎么离开配置页（返回键、左上角返回），都清掉「去配置」留下的待选模型：之后从设置首页配置这个服务商
    // 不会被意外带回聊天并切模型（审查 Nit）。配好回聊天的那次在 onDone 之前已由 ChatPage 读取并清空。
    val back = {
        PendingModel.clear()
        onBack()
    }
    BackHandler(onBack = back)
    SubPage(provider?.displayName ?: providerId, "provider", back) {
        provider?.let { p ->
            ProviderConfigScreen(
                p,
                graph.models.auth,
                // 只有「从模型弹层过来的这个服务商」且状态真的变成已配置时才回聊天；点「清除」等不算。
                onChanged = {
                    scope.launch {
                        val configured = runCatching { graph.models.auth.state(p.id).configured }.getOrDefault(false)
                        if (PendingModelLogic.shouldReturn(PendingModel.target.value, p.id, configured)) onDone()
                    }
                },
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
    // r3/models：NetworkAndCostScreen 已删（DESIGN-r3 X2/X14）；本路由由 r3/app 删除。
    SubPage("代理与费用显示", "network", onBack) { Text("此设置已移除：费用照 pi 显示美元。", Modifier.padding(16.dp)) }
}

/** M8 会话默认（只在真实 extras 时有入口）。 */
@Composable
internal fun SessionDefaultsPage(graph: AppGraph, onBack: () -> Unit) {
    val extras = graph.liveExtras
    SubPage("会话默认", "sessionDefaults", onBack) {
        if (extras == null) {
            Text("需要新版引擎：会话默认设置依赖 pi 引擎的扩展能力，当前版本尚未接入。", Modifier.padding(16.dp).testTag("page:needsEngine"))
        } else {
            val controller = remember(graph) { SessionDefaultsController(extras, graph.appScope) }
            SessionDefaultsScreen(controller)
        }
    }
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
            // 在 IO 线程按 96dp 采样解码（审查 Nit：不在组合阶段、主线程解整张位图）。
            val px = with(androidx.compose.ui.platform.LocalDensity.current) { 96.dp.roundToPx() }
            val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, img.base64Data, px) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Thumbnails.decode(img.base64Data, px)?.asImageBitmap() }
            }
            val b = bmp
            if (b != null) {
                Image(b, img.displayName ?: "图片", Modifier.padding(start = 6.dp).size(96.dp), contentScale = ContentScale.Crop)
            } else {
                Text("[图片]", Modifier.padding(start = 6.dp))
            }
        }
        Spacer(Modifier.size(0.dp))
    }
}

/** 缩略图解码：先读尺寸，再按目标边长取 2 的幂 inSampleSize。 */
internal object Thumbnails {
    fun sampleSize(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        var n = 1
        while (width / (n * 2) >= target && height / (n * 2) >= target) n *= 2
        return n
    }

    fun decode(base64: String, target: Int): android.graphics.Bitmap? = runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, target) }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()
}

/** 新路由（W5）。 */
object Routes {
    const val SETTINGS_HUB = "settingsHub"
    const val PROVIDERS = "providers"
    const val PROVIDER = "provider/{id}"
    const val CUSTOM_PROVIDER = "customProvider"
    const val NETWORK = "network"
    const val SESSION_DEFAULTS = "sessionDefaults"
    const val ADVANCED = "settings"
    const val TREE = "tree"
    const val SKILLS = "skills"
    const val USAGE = "usage"
    const val FILES = "files"
    const val FILES_PATTERN = "files?path={path}"
    fun provider(id: String) = "provider/$id"
    fun files(path: String?) = if (path == null) FILES else "files?path=" + android.net.Uri.encode(path)
}
