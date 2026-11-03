package io.github.zr987459.pigo.nav

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
import io.github.zr987459.pigo.feature.chat.ui.ChatScreen
import io.github.zr987459.pigo.feature.onboarding.OnboardingScreen
import io.github.zr987459.pigo.feature.sessions.SessionsScreen
import io.github.zr987459.pigo.feature.settings.SettingsFocus
import io.github.zr987459.pigo.feature.settings.SettingsScreen
import kotlinx.coroutines.launch

/** 四个顶层目的地。MVP 期间 feature 只提供页面，导航只在 :app。 */
enum class Destination(val route: String, val label: String) {
    Onboarding("onboarding", "安装与运行环境"),
    Chat("chat", "聊天"),
    Sessions("sessions", "全部会话"),
    Settings("settings", "设置"),
}

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

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    fun go(d: Destination) {
        scope.launch { drawerState.close() }
        navController.navigateTopLevel(d)
    }
    val newChat: () -> Unit = {
        scope.launch {
            drawerState.close()
            when (val r = graph.engine.newSession()) {
                is Outcome.Ok -> navController.navigateTopLevel(Destination.Chat)
                is Outcome.Err -> toast(sessionErrorText("新建会话", r))
            }
        }
    }

    // dsh-Go 的外壳：没有底部导航栏，页面切换都在左侧抽屉里（手势从左边缘拉出，scrim 渐暗）。
    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = DshTheme.colors.scrim,
        drawerContent = {
            PiGoDrawer(
                current = current,
                onDestination = ::go,
                onNewChat = newChat,
                engineStatus = graph.engine.status.collectAsState().value,
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            NavHost(navController, startDestination = start.route, modifier = Modifier.fillMaxSize()) {
                composable(Destination.Onboarding.route) {
                    OnboardingScreen(graph.runtime, onDone = { navController.navigateTopLevel(Destination.Chat) })
                }
                composable(Destination.Chat.route) {
                    ChatPage(
                        graph,
                        onOpenModelSettings = { navController.openSettings(SettingsFocus.MODELS) },
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onNewChat = newChat,
                    )
                }
                composable(Destination.Sessions.route) {
                    SessionsPage(graph, onOpened = { navController.navigateTopLevel(Destination.Chat) }, onError = ::toast)
                }
                composable(
                    SETTINGS_PATTERN,
                    arguments = listOf(navArgument("focus") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { entry ->
                    SettingsPage(graph, focus = entry.arguments?.getString("focus"))
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
        }
    }
}

@Composable
private fun ChatPage(graph: AppGraph, onOpenModelSettings: () -> Unit, onOpenDrawer: () -> Unit, onNewChat: () -> Unit) {
    val chat by graph.engine.chat.collectAsState()
    var noModel by remember { mutableStateOf(false) }
    // 从设置层取「是否配置过模型」；模型变化（保存设置后引擎重启）时重新读取。
    LaunchedEffect(chat.model) {
        noModel = chat.model == null && runCatching { graph.providerConfig.configured() }.getOrDefault(emptyList()).isEmpty()
    }
    ChatScreen(
        graph.chatDeps.controller,
        Modifier.fillMaxSize(),
        onOpenModelSettings = onOpenModelSettings,
        onOpenDrawer = onOpenDrawer,
        onNewChat = onNewChat,
        // 「选择模型▾」「+」的完整功能由功能工作流接入：这里先用 ChatScreen 的默认行为（内置下拉 / 暂不支持提示）。
        onModelClick = null,
        onAttachClick = null,
        topBanner = if (noModel) {
            { NoModelBanner(onOpenModelSettings) }
        } else {
            null
        },
    )
}

/** 没配模型：输入条上方的 Chrome 胶囊横幅（dsh-Go 的横幅都是胶囊）。 */
@Composable
private fun NoModelBanner(onOpenModelSettings: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("chat:noModel"), GlassTier.Chrome, AppShape.Lg) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(StatusTone.Warn)
            Spacer(Modifier.width(10.dp))
            Text("还没有配置模型，pi 暂时无法回答。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenModelSettings, modifier = Modifier.testTag("chat:goSettings")) { Text("去设置模型") }
        }
    }
}

@Composable
private fun SessionsPage(graph: AppGraph, onOpened: () -> Unit, onError: (String) -> Unit) {
    val chat by graph.engine.chat.collectAsState()
    SessionsScreen(
        repository = graph.sessions,
        currentGuestPath = chat.sessionFile,
        onOpen = { s ->
            when (val r = graph.engine.openSession(s.guestPath, s.cwd)) {
                is Outcome.Ok -> onOpened()
                is Outcome.Err -> {
                    graph.log.w("nav", "打开会话失败：${r.code} ${r.message}")
                    onError(sessionErrorText("打开会话", r))
                }
            }
        },
        onNewSession = {
            when (val r = graph.engine.newSession()) {
                is Outcome.Ok -> onOpened()
                is Outcome.Err -> {
                    graph.log.w("nav", "新建会话失败：${r.code} ${r.message}")
                    onError(sessionErrorText("新建会话", r))
                }
            }
            graph.sessions.refresh()
        },
        onBeforeDeleteCurrent = {
            when (val r = graph.engine.newSession()) {
                is Outcome.Ok -> null
                is Outcome.Err -> "先新建会话失败：${r.message}"
            }
        },
    )
}

@Composable
private fun SettingsPage(graph: AppGraph, focus: String?) {
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
