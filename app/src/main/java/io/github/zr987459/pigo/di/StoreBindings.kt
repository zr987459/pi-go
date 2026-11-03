package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.LaunchEnvProvider
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
import io.github.zr987459.pigo.core.store.PiProviderConfigStore
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

    fun sessions(mode: Mode, ctx: BindingContext): SessionRepository = when (mode) {
        Mode.FAKE -> FakeSessionRepository()
        Mode.REAL -> FileSessionRepository(paths(ctx))
    }
}
