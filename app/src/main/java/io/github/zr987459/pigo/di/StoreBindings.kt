package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.LaunchEnvProvider
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.PresetKind
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.ProviderConfigStore
import io.github.zr987459.pigo.contract.SecretStore
import io.github.zr987459.pigo.contract.SessionRepository
import io.github.zr987459.pigo.contract.fake.FakeLaunchEnvProvider
import io.github.zr987459.pigo.contract.fake.FakeProviderConfigStore
import io.github.zr987459.pigo.contract.fake.FakeSecretStore
import io.github.zr987459.pigo.contract.fake.FakeSessionRepository
import io.github.zr987459.pigo.core.store.FileSessionRepository
import io.github.zr987459.pigo.core.store.GuestPaths
import io.github.zr987459.pigo.core.store.KeystoreSecretStore
import io.github.zr987459.pigo.core.store.ModelDiscovery
import io.github.zr987459.pigo.core.store.PiProviderConfigStore
import io.github.zr987459.pigo.core.store.ProviderPresets
import io.github.zr987459.pigo.core.store.ProviderTester
import io.github.zr987459.pigo.feature.settings.ConnectionCheck
import io.github.zr987459.pigo.feature.settings.ConnectionTarget
import io.github.zr987459.pigo.feature.settings.CustomEndpointPrefill
import java.io.File

/**
 * 存储与配置（归 mvp/sessions-settings）。
 *
 * real()：rootfs 固定在 `files/rootfs`（与 `RuntimeManager.hostFile` 同一规则：`/root/x` → `files/rootfs/root/x`）。
 * [PiProviderConfigStore] 同时实现 `ProviderConfigStore` 与 `LaunchEnvProvider`，两者共用一个实例。
 */
object StoreBindings {
    private val lock = Any()
    @Volatile private var realConfig: PiProviderConfigStore? = null

    private fun paths(ctx: BindingContext) = GuestPaths(File(ctx.appContext.filesDir, "rootfs"))

    private fun realConfig(ctx: BindingContext, secrets: SecretStore): PiProviderConfigStore =
        realConfig ?: synchronized(lock) {
            realConfig ?: PiProviderConfigStore(secrets, paths(ctx)).also { realConfig = it }
        }

    /** W2 的 CatalogGraph 与 W4 用同一个 [PiProviderConfigStore] 实例（feat/wiring）。 */
    fun piConfig(ctx: BindingContext, secrets: SecretStore): PiProviderConfigStore = realConfig(ctx, secrets)

    fun secrets(mode: Mode, ctx: BindingContext): SecretStore = when (mode) {
        Mode.FAKE -> FakeSecretStore()
        Mode.REAL -> KeystoreSecretStore(ctx.appContext)
    }

    fun providerConfig(mode: Mode, ctx: BindingContext, secrets: SecretStore): ProviderConfigStore = when (mode) {
        Mode.FAKE -> FakeProviderConfigStore(secrets)
        Mode.REAL -> realConfig(ctx, secrets)
    }

    fun launchEnv(mode: Mode, ctx: BindingContext, secrets: SecretStore): LaunchEnvProvider = when (mode) {
        Mode.FAKE -> FakeLaunchEnvProvider()
        Mode.REAL -> realConfig(ctx, secrets)
    }

    /**
     * 设置页编辑已有自定义端点时的预填（`SettingsScreen(loadCustom = …)`）。FAKE 模式没有 models.json，返回 null。
     * 需要 mvp/integration 在 PiGoNavHost 里传给 SettingsScreen。
     */
    fun customPrefill(
        mode: Mode,
        ctx: BindingContext,
        secrets: SecretStore,
    ): (suspend (String) -> CustomEndpointPrefill?)? = when (mode) {
        Mode.FAKE -> null
        Mode.REAL -> { id ->
            realConfig(ctx, secrets).customProviders()[id]?.let { CustomEndpointPrefill(it.baseUrl, it.modelIds) }
        }
    }

    fun sessions(mode: Mode, ctx: BindingContext): SessionRepository = when (mode) {
        Mode.FAKE -> FakeSessionRepository()
        Mode.REAL -> FileSessionRepository(paths(ctx))
    }

    /**
     * 设置页「获取模型列表」（自定义 OpenAI 兼容端点，piweb-study G2）：宿主侧直接请求上游 `/models`，Key 不经容器。
     * rawKey 为空时用已保存的 Key（编辑已有端点）。FAKE 模式不提供。
     */
    fun discoverModels(
        mode: Mode,
        secrets: SecretStore,
    ): (suspend (id: String, baseUrl: String, rawKey: String) -> Outcome<List<String>>)? = when (mode) {
        Mode.FAKE -> null
        Mode.REAL -> { id, baseUrl, rawKey ->
            val key = rawKey.ifBlank { secrets.get(ProviderPresets.customAlias(ProviderPresets.normalizeCustomId(id))).orEmpty() }
            if (key.isBlank()) Outcome.Err(ErrCode.PROVIDER, "请先填写 API Key")
            else ModelDiscovery.discover(baseUrl, key)
        }
    }

    /**
     * 设置页「测试连接」（piweb-study G3）：
     * - 自定义端点：宿主侧发一次 16 token 的 chat/completions（20 s）；
     * - 内置 Provider：在容器里 `pi --print --no-session --provider <id>`，Key 只经环境变量（需要运行环境已就绪）。
     * Key 留空时用已保存的 Key。FAKE 模式不提供。
     */
    fun testConnection(
        mode: Mode,
        ctx: BindingContext,
        secrets: SecretStore,
        runtime: RuntimeManager,
    ): (suspend (ConnectionTarget) -> ConnectionCheck)? = when (mode) {
        Mode.FAKE -> null
        Mode.REAL -> { t ->
            val config = realConfig(ctx, secrets)
            val preset = config.presets().firstOrNull { it.provider == t.provider }
            val custom = t.custom || preset?.kind == PresetKind.CUSTOM_OPENAI || preset == null
            val r = if (custom) {
                val id = ProviderPresets.normalizeCustomId(t.provider)
                val saved = config.customProviders()[id]
                val baseUrl = t.baseUrl?.takeIf { it.isNotBlank() } ?: saved?.baseUrl ?: preset?.defaultBaseUrl.orEmpty()
                val key = t.rawKey.ifBlank { secrets.get(ProviderPresets.customAlias(id)).orEmpty() }
                val model = t.model?.takeIf { it.isNotBlank() } ?: saved?.modelIds?.firstOrNull().orEmpty()
                ProviderTester.testOpenAiCompatible(baseUrl, key, model)
            } else {
                val envVar = preset?.envVar
                if (envVar == null) {
                    io.github.zr987459.pigo.core.store.ProbeResult(false, "这个服务不支持测试")
                } else {
                    val key = t.rawKey.ifBlank { secrets.get(ProviderPresets.builtInAlias(t.provider)).orEmpty() }
                    ProviderTester.testBuiltIn(runtime, t.provider, envVar, key, t.model)
                }
            }
            ConnectionCheck(r.ok, r.message)
        }
    }
}
