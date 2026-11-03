package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.AppSettingsStore
import io.github.zr987459.pigo.contract.AuthManager
import io.github.zr987459.pigo.contract.CustomProviderStore
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.ModelCatalog
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.SecretStore
import io.github.zr987459.pigo.contract.fake.FakeAppSettingsStore
import io.github.zr987459.pigo.contract.fake.FakeAuthManager
import io.github.zr987459.pigo.contract.fake.FakeCustomProviderStore
import io.github.zr987459.pigo.contract.fake.FakeModelCatalog
import io.github.zr987459.pigo.core.catalog.CatalogGraph
import io.github.zr987459.pigo.core.catalog.RealAuthManager

/**
 * 模型目录、凭据与 OAuth、自定义端点、代理与费用显示（W2 feat/models，docs/feature-gap.md M1–M9）。
 * 真实实现在 `:core:catalog` 的 [CatalogGraph]，界面在 `:feature:models`。
 */
object ModelsBindings {
    class Models(
        val catalog: ModelCatalog,
        val auth: AuthManager,
        val custom: CustomProviderStore,
        val settings: AppSettingsStore,
        /** 服务商表单回填已保存的非密文字段（区域、项目、API 版本…）；Fake 时读不到。 */
        val savedFields: suspend (String) -> Map<String, String> = { emptyMap() },
    )

    fun models(mode: Mode, ctx: BindingContext, runtime: RuntimeManager, secrets: SecretStore, engine: EngineClient): Models = when (mode) {
        Mode.FAKE -> fake()
        Mode.REAL -> real(ctx, runtime, secrets, engine)
    }

    fun fake(): Models {
        val auth = FakeAuthManager()
        return Models(FakeModelCatalog(auth), auth, FakeCustomProviderStore(), FakeAppSettingsStore())
    }

    fun real(ctx: BindingContext, runtime: RuntimeManager, secrets: SecretStore, engine: EngineClient): Models {
        val g = CatalogGraph(
            ctx.appContext,
            runtime,
            secrets,
            StoreBindings.piConfig(ctx, secrets),
            ctx.appScope,
            runtimeModels = { (engine.models() as? Outcome.Ok)?.value },
            onCredentialsChanged = { reason -> engine.restart(reason.ifBlank { "凭据已更新" }); Unit },
        )
        val auth: RealAuthManager = g.auth
        return Models(g.catalog, auth, g.custom, g.settings, savedFields = { id -> auth.savedFields(id) })
    }
}
