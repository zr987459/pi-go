package io.github.zr987459.pigo.contract.settings

import io.github.zr987459.pigo.contract.Outcome
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

/**
 * pi 自己的设置（`~/.pi/agent/settings.json`，DESIGN-r3 §2.10、§5.1、§5.8）。实现在 `core/session`（r3/session），
 * 两种形态共用：UI 形态 ⌘「pi 设置」、终端形态 `/settings`，控件按 [schema] 生成。
 *
 * 写入规则（§5.8，实现方必须遵守）：
 * - 读-改-写放在与 `AuthJsonLock` 同款的文件锁里；**只改目标键**，保留未知键与其他键；
 * - 写完调 `/pigo:reload` 让 pi 重新读取；
 * - [PiSettingSpec.restartsEngine] 为真的键**不在这里重启引擎**：调用方先确认，再走 §5.5 唯一的重启路径。
 */
interface PiSettings {
    val schema: PiSettingsSchema

    /** 当前文件里的值（点分键 → 值）；文件里没有的键不出现，界面用 [PiSettingSpec.default] 显示。 */
    val values: StateFlow<Map<String, JsonElement>>

    /** 重新读文件（例如 pi 自己写过 settings.json 之后）。 */
    suspend fun refresh(): Outcome<Unit>

    /** 写一个键；[value] 为 null 表示删除该键（回到默认）。未知键返回 `Err(RPC_REJECTED)`。 */
    suspend fun set(key: String, value: JsonElement?): Outcome<Unit>
}
