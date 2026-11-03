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
import io.github.zr987459.pigo.di.ChatBindings
import io.github.zr987459.pigo.di.EngineBindings
import io.github.zr987459.pigo.di.RuntimeBindings
import io.github.zr987459.pigo.di.StoreBindings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手写依赖图（DESIGN.md §2.3）：持有单例，从四个 `di/XxxBindings.kt` 取实现，
 * 每组用 Fake 还是 Real 由 [BindingMode] 决定。本文件不随工作流改动。
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

    // 聊天界面（W3）：界面侧的可替换依赖
    val chatDeps: ChatBindings.ChatDeps by lazy { ChatBindings.deps(BindingMode.chat, ctx, engine) }
}
