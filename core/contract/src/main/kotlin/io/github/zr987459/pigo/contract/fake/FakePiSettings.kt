package io.github.zr987459.pigo.contract.fake

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.settings.PiSettingSpec
import io.github.zr987459.pigo.contract.settings.PiSettingType
import io.github.zr987459.pigo.contract.settings.PiSettings
import io.github.zr987459.pigo.contract.settings.PiSettingsSchema
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** 内存版 [PiSettings]：只认 [schema] 里的键；每次写入记进 [writes]（`key=value`，删除记 `key=null`）。 */
class FakePiSettings(
    override val schema: PiSettingsSchema = FIXTURE_SCHEMA,
    initial: Map<String, JsonElement> = emptyMap(),
) : PiSettings {
    private val _values = MutableStateFlow(initial)
    override val values: StateFlow<Map<String, JsonElement>> = _values.asStateFlow()

    val writes = mutableListOf<String>()

    override suspend fun refresh(): Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun set(key: String, value: JsonElement?): Outcome<Unit> {
        if (schema.specs.none { it.key == key }) return Outcome.Err(ErrCode.RPC_REJECTED, "未知设置键：$key")
        writes += "$key=$value"
        _values.update { if (value == null) it - key else it + (key to value) }
        return Outcome.Ok(Unit)
    }

    companion object {
        /** 夹具：几个有代表性的键（五种类型各一，含一个要重启引擎的）。真实 schema 由 r3/session 提供。 */
        val FIXTURE_SCHEMA: PiSettingsSchema = object : PiSettingsSchema {
            override val specs = listOf(
                PiSettingSpec("compaction.enabled", "压缩", "自动压缩", PiSettingType.Bool, JsonPrimitive(true), featureRef = "§8 compaction.enabled"),
                PiSettingSpec("steeringMode", "队列", "插话投递方式", PiSettingType.Enum(listOf("all", "one-at-a-time")), JsonPrimitive("one-at-a-time"), featureRef = "§8 steeringMode"),
                PiSettingSpec("retry.maxRetries", "重试", "最大重试次数", PiSettingType.Number(min = 0.0, integer = true), JsonPrimitive(3), featureRef = "§8 retry.maxRetries"),
                PiSettingSpec("httpProxy", "网络", "HTTP 代理", PiSettingType.Str, restartsEngine = true, featureRef = "§8 httpProxy"),
                PiSettingSpec("enabledModels", "模型", "常用模型", PiSettingType.StrList, featureRef = "§8 enabledModels"),
            )
        }
    }
}
