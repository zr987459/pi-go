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

    val sessionHost: SessionHost by lazy {
        SessionBindings.host(BindingMode.session, ctx, FormPersistence.initial(formPrefs))
            .also { FormPersistence.bind(it.form, formPrefs, appScope) }
    }
}
