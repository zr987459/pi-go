package io.github.zr987459.pigo

import android.content.Context
import io.github.zr987459.pigo.contract.AppLog
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.LaunchEnvProvider
import io.github.zr987459.pigo.contract.ProviderConfigStore
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.SecretStore
import io.github.zr987459.pigo.contract.SessionRepository
import io.github.zr987459.pigo.di.BindingContext
import io.github.zr987459.pigo.di.BindingMode
import io.github.zr987459.pigo.di.Mode
import io.github.zr987459.pigo.di.ChatBindings
import io.github.zr987459.pigo.di.EngineBindings
import io.github.zr987459.pigo.di.RuntimeBindings
import io.github.zr987459.pigo.di.CommandsBindings
import io.github.zr987459.pigo.di.ExtrasBindings
import io.github.zr987459.pigo.di.ModelsBindings
import io.github.zr987459.pigo.di.StoreBindings
import io.github.zr987459.pigo.di.WorkspaceBindings
import io.github.zr987459.pigo.di.SessionBindings
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.contract.models.ModelPanels
import io.github.zr987459.pigo.contract.settings.PiSettings
import io.github.zr987459.pigo.core.session.SessionOps
import io.github.zr987459.pigo.di.AppSessionOps
import io.github.zr987459.pigo.di.AppFileSearch
import io.github.zr987459.pigo.di.ModelPanelEvents
import io.github.zr987459.pigo.feature.chat.ui.calm.PanelEventSink
import io.github.zr987459.pigo.feature.models.RealModelPanels
import io.github.zr987459.pigo.form.FormPersistence
import io.github.zr987459.pigo.form.FormPrefStore
import io.github.zr987459.pigo.form.SharedPrefsFormStore
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.feature.commands.CommandsGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手写依赖图（DESIGN.md §2.3）：持有单例，从四个 `di/XxxBindings.kt` 取实现，
 * 每组用 Fake 还是 Real 由 [BindingMode] 决定。W5（feat/wiring）装配 W1–W4 的新依赖。
 */
class AppGraph(context: Context) {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val ctx = BindingContext(appContext = context.applicationContext, appScope = appScope)

    val log: AppLog by lazy { EngineBindings.log(BindingMode.engine, ctx) }

    // 存储（W4）
    val secrets: SecretStore by lazy { StoreBindings.secrets(BindingMode.store, ctx) }
    val providerConfig: ProviderConfigStore by lazy { StoreBindings.providerConfig(BindingMode.store, ctx, secrets) }
    val launchEnv: LaunchEnvProvider by lazy { StoreBindings.launchEnv(BindingMode.store, ctx, secrets) }
    val sessions: SessionRepository by lazy { StoreBindings.sessions(BindingMode.store, ctx) }

    // 运行环境（W1）
    val runtime: RuntimeManager by lazy { RuntimeBindings.runtime(BindingMode.runtime, ctx, log) }

    // 引擎（W2）
    /** pi 进程实际的 HOME（终端形态把 cwd 显示成 `~/…` 用），真引擎取 EngineConfig.homeDir，Fake 用容器默认值。 */
    val homeDir: String get() = (((engine as? io.github.zr987459.pigo.di.GatedEngineClient)?.inner ?: engine) as? io.github.zr987459.pigo.core.engine.PiEngine)?.config?.homeDir ?: io.github.zr987459.pigo.core.engine.EngineConfig.GUEST_HOME

    val engine: EngineClient by lazy { EngineBindings.engine(BindingMode.engine, ctx, runtime, launchEnv, log) }

    // pi 其余 RPC（W1，PR #13 已合入：PiEngine 本身，套同一个预热闸门）
    val extras: EngineExtras by lazy { ExtrasBindings.extras(BindingMode.extras, ctx, engine) }

    /**
     * 真正能用的 extras：只有 [BindingMode.extras] 为 REAL 时才有，否则为 null（审查第 1 轮 Important 1）。
     * 生产路径（聊天控制器、时间线附加项、长按分叉、用量小环、会话树 / 技能 / 用量入口、M8 开关）一律只用它：
     * Fake 只给开发期的 W3/W4 内部图用，绝不能让「!命令」、带图发送、压缩、分叉落到 Fake 上（会静默丢消息）。
     */
    val extrasReady: Boolean get() = BindingMode.extras == Mode.REAL
    val liveExtras: EngineExtras? get() = if (extrasReady) extras else null

    // 模型目录、服务商与登录、代理与费用显示（W2）
    val models: ModelsBindings.Models by lazy { ModelsBindings.models(BindingMode.models, ctx, runtime, secrets, engine) }

    // 文件、附件、导出（W3）
    val workspace: WorkspaceBindings.Workspace by lazy { WorkspaceBindings.workspace(BindingMode.workspace, ctx, runtime, engine, extras) }

    // 会话索引、草稿、自动命名、技能与模板（W4）
    val commands: CommandsGraph by lazy { CommandsBindings.graph(BindingMode.commands, ctx, runtime, engine, extras, launchEnv) }

    // 聊天界面：界面侧的可替换依赖（控制器带上 W1 extras 与 W4 草稿）
    val chatDeps: ChatBindings.ChatDeps by lazy { ChatBindings.deps(BindingMode.chat, ctx, engine, liveExtras, commands.drafts) }

    // r3：两种形态共用的会话宿主（DESIGN-r3 §5.1）。形态偏好存 App 设置，启动时读回、变化时写回。
    val formPrefs: FormPrefStore by lazy { SharedPrefsFormStore(ctx.appContext) }

    // r3：pi 的 settings.json（设置 › 模型 的默认模型 / 常用模型 / 思考档，与 /settings 共用）。
    val piSettings: PiSettings by lazy { SessionBindings.piSettings(runtime) }

    // r3：模型配置面板（r3/models）。/model、/login、设置 › 模型 共用同一个实例。
    // 包一层来源记录（r3/term PanelSourceTracker）：交给 PiActionsImpl 的就是这一个，终端形态据此认出 ModelPanels 产出的面板。
    val panelSource: io.github.zr987459.pigo.feature.term.PanelSourceTracker by lazy {
        io.github.zr987459.pigo.feature.term.PanelSourceTracker(
            RealModelPanels(models.catalog, models.auth, models.custom, setModelSignal, appScope, piSettings),
        )
    }
    val modelPanels: ModelPanels get() = panelSource

    /** 两种形态里模型面板的点击、键帽、表单、确认框 → [ModelPanels.onPanelEvent]；选中模型成功后关掉选择面板（[PickClose]）。 */
    val modelPanelEvents: PanelEventSink by lazy {
        ModelPanelEvents(modelPanels, switchCount = { setModelSignal.count }) { id, _, r, switched ->
            io.github.zr987459.pigo.di.PickClose.after(sessionHost.overlay, id, r, switched)
        }
    }

    /** 交给模型面板的引擎（同一个 [engine]，只多数 set_model 成功次数，PickClose 的结构化信号）。 */
    val setModelSignal: io.github.zr987459.pigo.di.SetModelSignal by lazy { io.github.zr987459.pigo.di.SetModelSignal(engine) }

    /** 当前 pi 进程启动时注入的代理（设置 › 模型「立即重启 pi」比较用）；引擎每次启动时由导航层记下。 */
    val effectiveProxy = kotlinx.coroutines.flow.MutableStateFlow<io.github.zr987459.pigo.contract.NetworkSettings?>(null)

    /** SessionOps 全部走真实引擎：PiBridge（pigo-bridge）与 PiEngine 就是 [engine] 里那一个对象，不另起引擎。 */
    val sessionOps: SessionOps by lazy { AppSessionOps.forEngine(engine, runtime, ctx.appContext.cacheDir) }

    /** `@文件` 候选（core/workspace FileSearch，根 = 当前会话 cwd）；Fake 工作区时为 null。 */
    val fileSearch: AppFileSearch? by lazy { workspace.search?.let { AppFileSearch(it, AppFileSearch.cwdOf(engine)) } }

    val sessionHost: SessionHost by lazy {
        SessionBindings.host(BindingMode.session, ctx, FormPersistence.initial(formPrefs)) {
            SessionBindings.Deps(
                engine = engine,
                extras = extras,
                controller = chatDeps.controller,
                settings = piSettings,
                repo = sessions,
                panels = modelPanels,
                ops = sessionOps,
                imageLoader = SessionBindings.imageLoader(ctx.appContext),
            )
        }.also { FormPersistence.bind(it.form, formPrefs, appScope) }
    }
}
