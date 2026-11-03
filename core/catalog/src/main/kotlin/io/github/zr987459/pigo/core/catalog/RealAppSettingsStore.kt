package io.github.zr987459.pigo.core.catalog

import io.github.zr987459.pigo.contract.AppSettingsStore
import io.github.zr987459.pigo.contract.CostDisplaySettings
import io.github.zr987459.pigo.contract.NetworkSettings
import io.github.zr987459.pigo.core.store.ModelPrefsStore
import io.github.zr987459.pigo.core.store.PiProviderConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * [AppSettingsStore]：代理（M9，存 Keystore，引擎启动时注入 `HTTP(S)_PROXY`）与费用显示（决策 3：默认美元，可选同时显示人民币，汇率默认 7.1）。
 * 代理改动后需要重启引擎才生效，由调用方（W5）决定时机。
 */
class RealAppSettingsStore(
    private val config: PiProviderConfigStore,
    private val prefs: ModelPrefsStore,
    scope: CoroutineScope,
) : AppSettingsStore {
    private val _network = MutableStateFlow(NetworkSettings())
    override val network: StateFlow<NetworkSettings> = _network.asStateFlow()

    override val costDisplay: StateFlow<CostDisplaySettings> = prefs.prefs
        .map { CostDisplaySettings(it.showCny, it.cnyPerUsd) }
        .stateIn(scope, SharingStarted.Eagerly, CostDisplaySettings(prefs.prefs.value.showCny, prefs.prefs.value.cnyPerUsd))

    init {
        scope.launch { _network.value = config.proxy()?.let { NetworkSettings(it.url, it.noProxy) } ?: NetworkSettings() }
    }

    override suspend fun setNetwork(value: NetworkSettings) {
        config.setProxy(value.proxyUrl, value.noProxy)
        _network.value = config.proxy()?.let { NetworkSettings(it.url, it.noProxy) } ?: NetworkSettings()
    }

    override suspend fun setCostDisplay(value: CostDisplaySettings) {
        prefs.setRate(value.usdToCny)
        prefs.setShowCny(value.showCny)
    }
}
