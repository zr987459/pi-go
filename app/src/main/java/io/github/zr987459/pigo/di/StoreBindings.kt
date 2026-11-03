package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.LaunchEnvProvider
import io.github.zr987459.pigo.contract.ProviderConfigStore
import io.github.zr987459.pigo.contract.SecretStore
import io.github.zr987459.pigo.contract.SessionRepository
import io.github.zr987459.pigo.contract.fake.FakeLaunchEnvProvider
import io.github.zr987459.pigo.contract.fake.FakeProviderConfigStore
import io.github.zr987459.pigo.contract.fake.FakeSecretStore
import io.github.zr987459.pigo.contract.fake.FakeSessionRepository

/** 存储与配置（归 mvp/sessions-settings）。scaffold 初版只含 fake 工厂。 */
object StoreBindings {
    fun secrets(mode: Mode, ctx: BindingContext): SecretStore = when (mode) {
        Mode.FAKE -> FakeSecretStore()
        Mode.REAL -> notWired("Store")
    }

    fun providerConfig(mode: Mode, ctx: BindingContext, secrets: SecretStore): ProviderConfigStore = when (mode) {
        Mode.FAKE -> FakeProviderConfigStore(secrets)
        Mode.REAL -> notWired("Store")
    }

    fun launchEnv(mode: Mode, ctx: BindingContext, secrets: SecretStore): LaunchEnvProvider = when (mode) {
        Mode.FAKE -> FakeLaunchEnvProvider()
        Mode.REAL -> notWired("Store")
    }

    fun sessions(mode: Mode, ctx: BindingContext): SessionRepository = when (mode) {
        Mode.FAKE -> FakeSessionRepository()
        Mode.REAL -> notWired("Store")
    }
}
