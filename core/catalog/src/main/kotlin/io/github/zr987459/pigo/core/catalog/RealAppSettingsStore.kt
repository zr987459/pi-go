package io.github.zr987459.pigo.core.catalog

import io.github.zr987459.pigo.contract.AppSettingsStore
import io.github.zr987459.pigo.contract.CostDisplaySettings
import io.github.zr987459.pigo.contract.NetworkSettings
import io.github.zr987459.pigo.core.store.ModelPrefsStore
import io.github.zr987459.pigo.core.store.PiProviderConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [AppSettingsStore]：代理（M9，存 Keystore，引擎启动时注入 `HTTP(S)_PROXY`）。
 * 费用一律照 pi 显示美元（DESIGN-r3 X2）：[costDisplay] 恒为默认值，[setCostDisplay] 不再保存任何东西。
 * 契约里的换算字段待 r3/core 删除（见 PR 描述的契约变更请求）。
 * 代理改动后需要重启引擎才生效，由调用方（W5）决定时机。
 */
class RealAppSettingsStore(
    private val config: PiProviderConfigStore,
    @Suppress("unused") private val prefs: ModelPrefsStore,
    scope: CoroutineScope,
) : AppSettingsStore {
    private val _network = MutableStateFlow(NetworkSettings())
    override val network: StateFlow<NetworkSettings> = _network.asStateFlow()

    override val costDisplay: StateFlow<CostDisplaySettings> = MutableStateFlow(CostDisplaySettings()).asStateFlow()

    init {
        scope.launch { _network.value = config.proxy()?.let { NetworkSettings(it.url, it.noProxy) } ?: NetworkSettings() }
    }

    override suspend fun setNetwork(value: NetworkSettings) {
        config.setProxy(value.proxyUrl, value.noProxy)
        _network.value = config.proxy()?.let { NetworkSettings(it.url, it.noProxy) } ?: NetworkSettings()
    }

    /** 美元是唯一显示方式，忽略写入。 */
    override suspend fun setCostDisplay(value: CostDisplaySettings) = Unit
}
