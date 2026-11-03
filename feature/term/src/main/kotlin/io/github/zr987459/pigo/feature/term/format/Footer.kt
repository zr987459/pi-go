package io.github.zr987459.pigo.feature.term.format

import io.github.zr987459.pigo.feature.term.text.CellWidth
import java.util.Locale

/** 一段带 pi 主题 token 的文字；[segment] 非空表示可点（页脚段 id，见 PiActionCatalog 的 `TermEntry.Footer`）。 */
data class Span(val text: String, val token: String? = null, val bold: Boolean = false, val italic: Boolean = false, val segment: String? = null)

typealias Line = List<Span>

fun Line.plain(): String = joinToString("") { it.text }

/** 页脚输入，字段对应 pi 1.5.0 `footer.js` 读取的会话状态。 */
data class FooterInput(
    val cwd: String,
    val home: String? = null,
    val gitBranch: String? = null,
    val sessionName: String? = null,
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val cost: Double = 0.0,
    /** 最后一条助手消息的缓存命中率（%）；没有时 null。 */
    val latestCacheHitRate: Double? = null,
    val usingSubscription: Boolean = false,
    /** 上下文占用百分比；压缩后未知时 null（显示 `?`）。 */
    val contextPercent: Double? = 0.0,
    val contextWindow: Long = 0,
    val autoCompact: Boolean = true,
    val modelId: String? = null,
    val provider: String? = null,
    val reasoning: Boolean = false,
    val thinkingLevel: String? = null,
    val availableProviderCount: Int = 1,
    /** 扩展状态（setStatus），按 key 排序后一行显示。 */
    val extensionStatuses: Map<String, String> = emptyMap(),
)

/**
 * 页脚（DESIGN-r3 §3.1、§7.3 FooterSnapshotTest）：逐行移植 pi 1.5.0 `modes/interactive/components/footer.js` 的 `render(width)`，
 * 排版、数字格式、截断顺序一致；另外给 cwd 段与模型段打上可点标记（`cwd` / `model`），并在右端加 `[UI]` 键帽（形态切换，§5.2）。
 * `[UI]` 键帽不计入 pi 的排版宽度：调用方把 [width] 设为可用列数减去键帽宽度。
 */
object Footer {
    fun formatTokens(count: Long): String = when {
        count < 1000 -> count.toString()
        count < 10000 -> "%.1fk".format(Locale.ROOT, count / 1000.0)
        count < 1000000 -> "${jsRound(count / 1000.0)}k"
        count < 10000000 -> "%.1fM".format(Locale.ROOT, count / 1000000.0)
        else -> "${jsRound(count / 1000000.0)}M"
    }

    private fun jsRound(v: Double) = kotlin.math.floor(v + 0.5).toLong()

    /** JS `Number.prototype.toFixed`（四舍五入到 [digits] 位）。 */
    private fun fixed(v: Double, digits: Int) = java.math.BigDecimal(v).setScale(digits, java.math.RoundingMode.HALF_UP).toPlainString()

    fun formatCwd(cwd: String, home: String?): String {
        if (home.isNullOrEmpty()) return cwd
        val c = cwd.trimEnd('/').ifEmpty { "/" }
        val h = home.trimEnd('/').ifEmpty { "/" }
        return when {
            c == h -> "~"
            c.startsWith("$h/") -> "~/" + c.removePrefix("$h/")
            else -> cwd
        }
    }

    private fun sanitize(t: String) = t.replace(Regex("[\r\n\t]"), " ").replace(Regex(" +"), " ").trim()

    fun render(i: FooterInput, width: Int): List<Line> {
        var pwd = formatCwd(i.cwd, i.home)
        if (!i.gitBranch.isNullOrEmpty()) pwd = "$pwd (${i.gitBranch})"
        if (!i.sessionName.isNullOrEmpty()) pwd = "$pwd • ${i.sessionName}"

        val stats = mutableListOf<String>()
        if (i.input != 0L) stats += "↑${formatTokens(i.input)}"
        if (i.output != 0L) stats += "↓${formatTokens(i.output)}"
        if (i.cacheRead != 0L) stats += "R${formatTokens(i.cacheRead)}"
        if (i.cacheWrite != 0L) stats += "W${formatTokens(i.cacheWrite)}"
        if ((i.cacheRead > 0 || i.cacheWrite > 0) && i.latestCacheHitRate != null) stats += "CH${fixed(i.latestCacheHitRate, 1)}%"
        val usingSub = i.modelId != null && (i.provider == "kimi-coding" || i.usingSubscription)
        if (i.cost != 0.0 || usingSub) stats += "$${fixed(i.cost, 3)}${if (usingSub) " (sub)" else ""}"
        val pctValue = i.contextPercent ?: 0.0
        val auto = if (i.autoCompact) " (auto)" else ""
        val ctx = if (i.contextPercent == null) "?/${formatTokens(i.contextWindow)}$auto" else "${fixed(pctValue, 1)}%/${formatTokens(i.contextWindow)}$auto"
        val ctxToken = when { pctValue > 90 -> "error"; pctValue > 70 -> "warning"; else -> null }
        val prefix = (if (stats.isEmpty()) "" else stats.joinToString(" ") + " ")

        var statsLeft = prefix + ctx
        var statsLeftWidth = CellWidth.width(statsLeft)
        var truncatedLeft = false
        if (statsLeftWidth > width) {
            statsLeft = CellWidth.truncate(statsLeft, width, "...")
            statsLeftWidth = CellWidth.width(statsLeft)
            truncatedLeft = true
        }
        val modelName = i.modelId ?: "no-model"
        var rightNoProvider = modelName
        if (i.reasoning) {
            val lvl = i.thinkingLevel ?: "off"
            rightNoProvider = if (lvl == "off") "$modelName • thinking off" else "$modelName • $lvl"
        }
        var right = rightNoProvider
        if (i.availableProviderCount > 1 && i.modelId != null) {
            right = "(${i.provider}) $rightNoProvider"
            if (statsLeftWidth + 2 + CellWidth.width(right) > width) right = rightNoProvider
        }
        val rightWidth = CellWidth.width(right)
        val (padding, rightShown) = if (statsLeftWidth + 2 + rightWidth <= width) {
            " ".repeat(width - statsLeftWidth - rightWidth) to right
        } else {
            val avail = width - statsLeftWidth - 2
            if (avail > 0) {
                val t = CellWidth.truncate(right, avail, "")
                " ".repeat((width - statsLeftWidth - CellWidth.width(t)).coerceAtLeast(0)) to t
            } else "" to ""
        }

        val line2 = mutableListOf<Span>()
        if (truncatedLeft) line2 += Span(statsLeft, "dim")
        else {
            if (prefix.isNotEmpty()) line2 += Span(prefix, "dim")
            line2 += Span(ctx, ctxToken ?: "dim")
        }
        if (padding.isNotEmpty()) line2 += Span(padding, "dim")
        if (rightShown.isNotEmpty()) line2 += Span(rightShown, "dim", segment = "model")

        val lines = mutableListOf<Line>(
            listOf(Span(CellWidth.truncate(pwd, width, "..."), "dim", segment = "cwd")),
            line2,
        )
        if (i.extensionStatuses.isNotEmpty()) {
            val s = i.extensionStatuses.toSortedMap().values.joinToString(" ") { sanitize(it) }
            lines += listOf(Span(CellWidth.truncate(s, width, "...")))
        }
        return lines
    }
}
