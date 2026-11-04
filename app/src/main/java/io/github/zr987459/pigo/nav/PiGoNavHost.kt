package io.github.zr987459.pigo.nav

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import io.github.zr987459.pigo.attach.ImageAttach
import io.github.zr987459.pigo.settings.ModelsPageExtras
import io.github.zr987459.pigo.settings.ProxyLogic
import io.github.zr987459.pigo.contract.session.DraftImage
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import io.github.zr987459.pigo.core.ui.AppShape
import io.github.zr987459.pigo.core.ui.DshTheme
import io.github.zr987459.pigo.core.ui.GlassSurface
import io.github.zr987459.pigo.core.ui.GlassTier
import io.github.zr987459.pigo.core.ui.StatusDot
import io.github.zr987459.pigo.core.ui.StatusTone
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.zr987459.pigo.AppGraph
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.Phase
import io.github.zr987459.pigo.di.BindingContext
import io.github.zr987459.pigo.di.BindingMode
import io.github.zr987459.pigo.di.EngineBindings
import io.github.zr987459.pigo.di.StoreBindings
import io.github.zr987459.pigo.form.FormHost
import io.github.zr987459.pigo.feature.chat.ui.calm.CalmSurface
import io.github.zr987459.pigo.form.toggleForm
import io.github.zr987459.pigo.form.TermBack
import io.github.zr987459.pigo.feature.term.TermEnv
import io.github.zr987459.pigo.feature.term.TermSurface
import io.github.zr987459.pigo.feature.term.FileMatch as TermFileMatch
import io.github.zr987459.pigo.feature.onboarding.OnboardingScreen
import io.github.zr987459.pigo.feature.settings.SettingsFocus
import io.github.zr987459.pigo.feature.settings.SettingsScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import io.github.zr987459.pigo.contract.ModelRef

/**
 * 顶层目的地（DESIGN-r3 §8.5 r3/app）：安装 → 会话（`Crossfade(form)`）→ 设置 › 模型 → 关于（设置页末尾）。
 * 旧的抽屉、设置首页、文件 / 会话树 / 用量 / 回收站等页面已删除（PR 2）。
 */
enum class Destination(val route: String, val label: String) {
    Onboarding("onboarding", "安装与运行环境"),
    Chat("chat", "会话"),
    Settings("settings", "设置 › 模型"),
}

/** 设置 › 模型（CalmSurface 的「设置」入口、会话列表底部的设置）。 */
const val SETTINGS_FOCUS_MODELS = "models"

/** 设置 › 模型 › 关于（DESIGN-r3 §8.5）。与 ⌘ › 帮助 › 关于（PiActions `about`）同一句话，再加版本与许可。 */
internal fun aboutText(version: String?): String =
    "pi-go ${version.orEmpty()}".trim() + "\npi 1.5.0（@earendil-works/pi-coding-agent）的安卓客户端，引擎：pi --mode rpc。\n第三方许可见 THIRD_PARTY_NOTICES.md。"

/** 设置页带可选定位参数：`settings?focus=models`（见 [SettingsFocus]）。 */
private const val SETTINGS_PATTERN = "settings?focus={focus}"

/**
 * 装配层导航（mvp/integration）：把各 feature 的真实页面接到真实依赖上。
 * - 聊天页 ↔ 会话层：会话页拿引擎的「当前会话」（`ChatState.sessionFile`），打开/新建/删除当前会话都经引擎，
 *   之后回到聊天页，聊天页随引擎的 [io.github.zr987459.pigo.contract.EngineClient.chat] 自动更新。
 * - 聊天页 ↔ 设置层：没配模型时聊天页顶部提示并给「去设置模型」；错误卡/失败气泡的「去设置模型」
 *   跳到设置页「模型与 Key」区块；设置页保存后重启引擎，聊天页顶栏模型随之更新。
 */
@Composable
fun PiGoNavHost(graph: AppGraph, navController: NavHostController = rememberNavController()) {
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route?.substringBefore('?')
    val runtimeStatus by graph.runtime.status.collectAsState()
    // 运行环境已装好就直接进聊天页；否则先进安装向导。只在首次组合时决定起点。
    val start = remember { if (graph.runtime.status.value.phase == Phase.READY) Destination.Chat else Destination.Onboarding }

    val app = LocalContext.current.applicationContext
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    // 运行环境就绪后预先拉起引擎（握手、冷启动回到上次会话 L5），聊天页一打开就有模型与会话信息。
    // 跑在 appScope 里且只做一次：旋转、切换深色模式等 Activity 重建不会取消或重复启动。
    LaunchedEffect(runtimeStatus.phase) {
        if (runtimeStatus.phase == Phase.READY) {
            EngineBindings.warmUpOnce(graph.engine, BindingContext(app, graph.appScope), graph.runtime, graph.sessions, graph.log)
        }
    }

    // 「立即重启 pi」比较用：每次 pi 启动（Starting）时记下当时已保存的代理 = 这个进程实际注入的代理。
    LaunchedEffect(runtimeStatus.phase) {
        if (runtimeStatus.phase != Phase.READY) return@LaunchedEffect
        graph.engine.status.collect { st ->
            val net = graph.models.settings.network.value
            if (st is io.github.zr987459.pigo.contract.EngineStatus.Starting || graph.effectiveProxy.value == null && st !is io.github.zr987459.pigo.contract.EngineStatus.Stopped)
                graph.effectiveProxy.value = net
        }
    }

    // r3：会话列表、⌘ 命令中心都在 UI 形态（CalmSurface）里，外壳不再有旧抽屉（PiGoDrawer，PR 2 删除文件）。
    val currentSession by remember(graph) { graph.engine.chat.map { it.sessionFile }.distinctUntilChanged() }
        .collectAsState(initial = graph.engine.chat.value.sessionFile)
    LaunchedEffect(currentSession, runtimeStatus.phase) {
        if (runtimeStatus.phase == Phase.READY) runCatching { graph.sessions.refresh() }
    }

    Box(Modifier.fillMaxSize()) {
            NavHost(navController, startDestination = start.route, modifier = Modifier.fillMaxSize()) {
                composable(Destination.Onboarding.route) {
                    OnboardingScreen(
                        graph.runtime,
                        onDone = { navController.navigateTopLevel(Destination.Chat) },
                        // 已装好（从抽屉进来）时左上角是返回；首次安装时引导页是起点，给「≡」打开抽屉。
                        onBack = if (runtimeStatus.phase == Phase.READY) {
                            { navController.navigateTopLevel(Destination.Chat) }
                        } else {
                            null
                        },
                        onOpenMenu = null,
                    )
                }
                composable(Destination.Chat.route) {
                    // r3：会话页 = FormHost（DESIGN-r3 §5.2）。UI 形态 = CalmSurface（r3/calm），读写同一个 SessionHostImpl；
                    // 终端形态在 r3/term 合入前是占位。切换只改 sessionHost.form，引擎与会话不受影响。
                    // 「＋ › 图片」：系统照片选择器 → 草稿图片（两形态共用 host.draft），发送时 imageLoader 读成 base64（审查 Important 1）。
                    val activity = LocalContext.current as? android.app.Activity
                    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ImageAttach.MAX_PICK)) { uris ->
                        if (uris.isEmpty()) return@rememberLauncherForActivityResult
                        val cr = app.contentResolver
                        val imgs = uris.map { u ->
                            // 照片选择器的 URI 默认只在本进程存活期间可读；能持久化就持久化（发送前 App 被回收也不丢）。
                            runCatching { cr.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            DraftImage(uri = u.toString(), mimeType = runCatching { cr.getType(u) }.getOrNull())
                        }
                        graph.sessionHost.draft.value = ImageAttach.add(graph.sessionHost.draft.value, imgs)
                    }
                    FormHost(
                        graph.sessionHost,
                        calm = {
                            CalmSurface(
                                graph.sessionHost,
                                onOpenSettings = { navController.openSettings(SETTINGS_FOCUS_MODELS) },
                                panelEvents = graph.modelPanelEvents,
                                fileSearch = graph.fileSearch?.asLambda,
                                onAttachImage = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                backHandler = { enabled, onBack -> BackHandler(enabled, onBack) },
                            )
                        },
                        term = {
                            // 终端形态（r3/term）：同一个 SessionHost（会话、引擎、草稿、overlay 栈共用），切换只改 form。
                            val host = graph.sessionHost
                            val overlays by host.overlay.items.collectAsState()
                            val closable = TermBack.topClosable(overlays)
                            // 返回键：先关最上层可关的面板（扩展对话框固定在顶，不拦截，与 UI 形态一致）；都没有时退到后台。
                            BackHandler(enabled = closable != null) {
                                closable?.let { m -> TermBack.close(host, m) { id, k -> scope.launch { runCatching { graph.modelPanelEvents.onEvent(id, k, null) } } } }
                            }
                            val search = graph.fileSearch
                            val env = remember(graph) {
                                TermEnv(
                                    fileSearch = search?.let { fs -> val f: suspend (String) -> List<TermFileMatch> = { q -> fs.suggest(q).map { TermFileMatch(it.path, it.isDir, it.completion) } }; f },
                                    panelSource = graph.panelSource,
                                    onPanelEvent = { id, k, v -> graph.modelPanelEvents.onEvent(id, k, v) },
                                    onAttachImage = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                )
                            }
                            val cwd = remember(currentSession) { io.github.zr987459.pigo.di.AppFileSearch.cwdOf(graph.engine)() ?: "~" }
                            TermSurface(host, cwd = cwd, home = "/root", env = env)
                        },
                        // 各层都关着时按返回：退到后台，不结束 Activity、不打断生成（N4；终端形态同理）。
                        onBackground = { activity?.moveTaskToBack(true) },
                    )
                }
                composable(
                    SETTINGS_PATTERN,
                    arguments = listOf(navArgument("focus") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { entry ->
                    SettingsPage(graph, focus = entry.arguments?.getString("focus"), onBack = { if (!navController.popBackStack()) navController.navigateTopLevel(Destination.Chat) })
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
    }
}

@Composable
private fun SettingsPage(graph: AppGraph, focus: String?, onBack: () -> Unit) {
    val runtimeStatus by graph.runtime.status.collectAsState()
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext
    val ctx = remember(graph) { BindingContext(app, graph.appScope) }
    SettingsScreen(
        providers = graph.providerConfig,
        secrets = graph.secrets,
        engine = graph.engine,
        runtimeStatus = runtimeStatus,
        onRepairRuntime = { scope.launch { graph.runtime.repair() } },
        loadCustom = StoreBindings.customPrefill(BindingMode.store, ctx, graph.secrets),
        focus = focus,
        discoverModels = StoreBindings.discoverModels(BindingMode.store, graph.secrets),
        testConnection = StoreBindings.testConnection(BindingMode.store, ctx, graph.secrets, graph.runtime),
        onBack = onBack,
        // r3：设置只留模型配置（DESIGN-r3 §6.1）：按 ModelPanels 逐行渲染，与 /model、/login 同一份实现。
        panels = graph.modelPanels,
        piSettings = graph.piSettings,
        // 设置只留模型配置；网络代理、运行环境修复、关于作为本页末尾的行（PR #21 审查第 1 轮 Important 2、Nit 3）。
        extraContent = {
            ModelsPageExtras(
                settings = graph.models.settings,
                onRepairRuntime = {
                    when (val r = graph.runtime.repair()) {
                        is Outcome.Ok -> "运行环境已修复"
                        is Outcome.Err -> "修复失败：${r.message}"
                    }
                },
                effective = graph.effectiveProxy,
                aboutText = aboutText(remember(app) { runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() }),
                // 复用已有的重启引擎动作（与凭据变化后 onCredentialsChanged 相同的 engine.restart），PR #21 审查第 2 轮 Nit 4。
                onRestartEngine = {
                    when (val r = graph.engine.restart("网络代理已更新")) {
                        is Outcome.Ok -> ProxyLogic.restartMessage(true)
                        is Outcome.Err -> ProxyLogic.restartMessage(false, r.message)
                    }
                },
            )
        },
    )
}

/** 打开 / 新建会话失败时给用户看的中文原因（审查 R1 #5：失败不能毫无反应）。 */
internal fun sessionErrorText(action: String, e: Outcome.Err): String {
    val reason = when (e.code) {
        ErrCode.ENGINE_START, ErrCode.ENGINE_EXIT -> "引擎没有在运行，请稍后再试或在聊天页重启引擎"
        ErrCode.RPC_TIMEOUT -> "pi 没有及时回应，请稍后再试"
        ErrCode.CANCELLED -> "被扩展取消了"
        ErrCode.OOM -> "内存不足，pi 被系统结束"
        else -> e.message.trim().ifBlank { "未知错误" }
    }
    return "${action}失败：$reason"
}

/** 顶层切换：回到起点再进入，不叠加返回栈；保存/恢复各页状态。 */
fun NavHostController.navigateTopLevel(d: Destination) {
    navigate(d.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** 跳到设置页的指定区块（不恢复旧状态，保证定位参数生效）。 */
fun NavHostController.openSettings(focus: String) {
    navigate("settings?focus=$focus") {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
    }
}

