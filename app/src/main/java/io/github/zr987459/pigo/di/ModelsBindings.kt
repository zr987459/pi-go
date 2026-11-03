package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.AppSettingsStore
import io.github.zr987459.pigo.contract.AuthManager
import io.github.zr987459.pigo.contract.CustomProviderStore
import io.github.zr987459.pigo.contract.ModelCatalog
import io.github.zr987459.pigo.contract.fake.FakeAppSettingsStore
import io.github.zr987459.pigo.contract.fake.FakeAuthManager
import io.github.zr987459.pigo.contract.fake.FakeCustomProviderStore
import io.github.zr987459.pigo.contract.fake.FakeModelCatalog

/**
 * 模型目录、凭据与 OAuth、自定义端点、代理与费用显示（W2 feat/models，docs/feature-gap.md M1–M9）。
 *
 * 真实实现在 `:core:catalog` 的 `CatalogGraph`（`RealModelCatalog`、`RealAuthManager`、`RealCustomProviderStore`、
 * `RealAppSettingsStore`），界面在 `:feature:models`。`app/build.gradle.kts` 目前还没有依赖这两个模块
 * （构建文件归 scaffold 维护者），所以本文件暂时只提供 Fake；W5（feat/wiring）加上
 * `implementation(project(":core:catalog"))`、`implementation(project(":feature:models"))` 后，[real] 改为：
 *
 * ```
 * val g = CatalogGraph(ctx.appContext, runtime, secrets, StoreBindings 的 PiProviderConfigStore, ctx.appScope,
 *     runtimeModels = { engine.models().getOrNull() }, onCredentialsChanged = { engine.restart("凭据已更新") })
 * Models(g.catalog, g.auth, g.custom, g.settings)
 * ```
 */
object ModelsBindings {
    class Models(
        val catalog: ModelCatalog,
        val auth: AuthManager,
        val custom: CustomProviderStore,
        val settings: AppSettingsStore,
    )

    fun fake(): Models {
        val auth = FakeAuthManager()
        return Models(FakeModelCatalog(auth), auth, FakeCustomProviderStore(), FakeAppSettingsStore())
    }
}
