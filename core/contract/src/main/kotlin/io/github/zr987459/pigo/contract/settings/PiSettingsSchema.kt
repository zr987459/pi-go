package io.github.zr987459.pigo.contract.settings

import kotlinx.serialization.json.JsonElement

/**
 * pi `settings.json` 的键描述（DESIGN-r3 §2.10、§5.8）。schema 本身由 r3/session 的 PiSettings 提供，
 * 须覆盖清单 §8 全部键（PiSettingsSchemaCoverageTest）；两种形态按它生成控件。
 */
interface PiSettingsSchema {
    val specs: List<PiSettingSpec>
}

data class PiSettingSpec(
    /** 点分路径，如 "compaction.enabled"。 */
    val key: String,
    /** 分组，如「压缩」「队列」「网络」。 */
    val group: String,
    val label: String,
    val type: PiSettingType,
    val default: JsonElement? = null,
    val help: String? = null,
    /** 进程环境变量类设置：改了要重启引擎（先确认，§5.5）。 */
    val restartsEngine: Boolean = false,
    /** 对应清单行，如 "§8 compaction.enabled"。 */
    val featureRef: String? = null,
)

sealed interface PiSettingType {
    data object Bool : PiSettingType
    data class Enum(val values: List<String>) : PiSettingType
    data class Number(val min: Double? = null, val max: Double? = null, val integer: Boolean = false) : PiSettingType
    data object Str : PiSettingType
    data object StrList : PiSettingType
}
