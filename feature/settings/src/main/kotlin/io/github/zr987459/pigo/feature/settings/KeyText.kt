package io.github.zr987459.pigo.feature.settings

import io.github.zr987459.pigo.contract.sanitizeApiKey

/**
 * 设置页里与 Key 相关的显示规则（纯函数，JVM 单测覆盖）。
 * 别名约定与 `:core:store` 的实现、`:core:contract` 的 Fake 一致：`key.<provider>`、`key.custom.<id>`。
 */
object KeyText {
    private const val DOTS = "••••"

    fun builtInAlias(provider: String) = "key.$provider"
    fun customAlias(id: String) = "key.custom.$id"

    /** 与存储层相同的清洗：去首尾空白、去开头 `Bearer `；只剩 `Bearer` 视为空。 */
    fun clean(raw: String): String = sanitizeApiKey(raw).let { if (it.equals("Bearer", ignoreCase = true)) "" else it }

    /** 只显示末 4 位；Key 少于 8 位时一位都不显示。 */
    fun mask(key: String?): String = when {
        key.isNullOrEmpty() -> "未设置"
        key.length < 8 -> DOTS
        else -> DOTS + key.takeLast(4)
    }

    /** 由 `SecretStore.last4()` 的结果显示：只拿末 4 位，明文不进界面层；短 Key（last4 为 null）只显示圆点。 */
    fun maskLast4(last4: String?): String = if (last4.isNullOrEmpty()) DOTS else DOTS + last4

    /** 输入框下方的「将保存为 ••••abcd」；没有输入时为 null。 */
    fun preview(raw: String): String? = clean(raw).takeIf { it.isNotEmpty() }?.let(::mask)

    /** 与存储层一致的环境变量名，用于提示。 */
    fun envVarFor(rawId: String): String {
        val id = rawId.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-')
        return if (id.isEmpty()) "PIGO_KEY_<ID>" else "PIGO_KEY_" + id.uppercase().replace(Regex("[^A-Z0-9]"), "_")
    }

    /** 模型 id：逗号（含中文逗号）、空白、换行分隔，去重。 */
    fun splitModels(raw: String): List<String> =
        raw.split(Regex("[,，;；\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** pi 1.5.0 `settings.md` 的 `defaultThinkingLevel` 取值（常用的几档）。 */
    val THINKING_LEVELS: List<Pair<String, String>> = listOf(
        "off" to "关闭",
        "low" to "低",
        "medium" to "中",
        "high" to "高",
        "xhigh" to "很高",
    )
}
