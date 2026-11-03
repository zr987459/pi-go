package io.github.zr987459.pigo.core.store

/**
 * Key 的显示与脱敏（DESIGN.md §3.10、§5.2 C5）。
 *
 * - 界面只显示末 4 位：[mask]；Key 太短（< 8 位）时连末 4 位也不显示，避免等于明文。
 * - 日志 / 诊断导出：[redact] 把已知 Key 与常见 Key 形态（`sk-…`、`Bearer …`）替换成 `***`。
 */
object KeyRedaction {
    const val DOTS = "••••"

    fun last4(key: String?): String? {
        if (key.isNullOrEmpty() || key.length < 8) return null
        return key.takeLast(4)
    }

    fun mask(key: String?): String {
        if (key.isNullOrEmpty()) return "未设置"
        val l4 = last4(key) ?: return DOTS
        return "$DOTS$l4"
    }

    private val patterns = listOf(
        Regex("sk-[A-Za-z0-9_\\-]{6,}"),
        Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{6,}"),
        // OAuth / GitHub 令牌形态（W2）：gho_/ghu_/ghp_/ghs_、JWT
        Regex("gh[opsu]_[A-Za-z0-9]{8,}"),
        Regex("eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{4,}(\\.[A-Za-z0-9_\\-]+)?"),
        Regex("(?i)\"(access|refresh)(_token)?\"\\s*:\\s*\"[^\"]{8,}\""),
    )

    /** 去掉代理地址里的用户名密码：`user:pass@` 换成三个星号加 `@`。 */
    fun redactUrlCredentials(url: String): String = url.replace(Regex("(?<=://)[^/@\\s]+@"), "***@")

    fun redact(text: String, knownKeys: Collection<String> = emptyList()): String {
        var out = text
        knownKeys.filter { it.length >= 4 }.sortedByDescending { it.length }.forEach { k ->
            out = out.replace(k, "***")
        }
        return patterns.fold(out) { acc, r -> r.replace(acc, "***") }
    }
}
