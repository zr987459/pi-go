package io.github.zr987459.pigo.feature.usage.logic

import java.math.BigDecimal
import java.math.RoundingMode

/** 用量弹层的数字格式（U1/U2），纯函数。 */
@Deprecated("r3: r3/app 删除（DESIGN-r3 X2/X3），新代码不得引用")
object UsageFormat {
    /** 默认汇率：只用于「约合人民币」的粗略换算，可在用量弹层里改。 */
    const val DEFAULT_CNY_PER_USD = 7.1

    /** 1234 → 1.2k，150000 → 150k，2_500_000 → 2.5M。 */
    fun tokens(n: Long?): String {
        if (n == null) return "—"
        val abs = kotlin.math.abs(n)
        return when {
            abs < 1_000 -> n.toString()
            abs < 1_000_000 -> trim(n / 1_000.0, if (abs < 10_000) 1 else 0) + "k"
            else -> trim(n / 1_000_000.0, 1) + "M"
        }
    }

    /** 美元：小于 0.01 时保留 4 位，否则 2 位。 */
    fun usd(cost: Double?): String {
        if (cost == null) return "—"
        val digits = if (cost != 0.0 && kotlin.math.abs(cost) < 0.01) 4 else 2
        return "$" + BigDecimal.valueOf(cost).setScale(digits, RoundingMode.HALF_UP).toPlainString()
    }

    /** 约合人民币；汇率不是正数时返回 null（不显示）。 */
    fun cny(cost: Double?, rate: Double): String? {
        if (cost == null || rate <= 0.0 || rate.isNaN()) return null
        val v = BigDecimal.valueOf(cost).multiply(BigDecimal.valueOf(rate))
        val digits = if (v.signum() != 0 && v.abs() < BigDecimal("0.01")) 4 else 2
        return "约 ¥" + v.setScale(digits, RoundingMode.HALF_UP).toPlainString()
    }

    /** 用户输入的汇率：可带空格、全角点；非法或不为正时返回 null。 */
    fun parseRate(text: String): Double? =
        text.trim().replace('。', '.').replace('．', '.').toDoubleOrNull()?.takeIf { it > 0 && it < 1_000 }

    private fun trim(v: Double, digits: Int): String {
        val s = BigDecimal.valueOf(v).setScale(digits, RoundingMode.HALF_UP).toPlainString()
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }
}

/** 上下文占用条的显示状态（U2）。 */
sealed interface ContextBar {
    /** 还没有任何统计。 */
    data object Unknown : ContextBar

    /** 压缩后 pi 把 percent 置空，要等下一轮回复后才有新值。 */
    data class AfterCompaction(val contextWindow: Long?) : ContextBar

    data class Known(val percent: Double, val tokens: Long?, val contextWindow: Long?) : ContextBar {
        /** 0..1，供进度环使用。 */
        val fraction: Float get() = (percent / 100.0).coerceIn(0.0, 1.0).toFloat()
        val label: String
            get() = buildString {
                append(BigDecimal.valueOf(percent).setScale(if (percent < 10) 1 else 0, RoundingMode.HALF_UP).toPlainString())
                append('%')
                if (tokens != null && contextWindow != null) {
                    append(" · ").append(UsageFormat.tokens(tokens)).append(" / ").append(UsageFormat.tokens(contextWindow))
                }
            }
    }

    companion object {
        /**
         * [hadUsage]：本会话是否已经出现过上下文统计（`contextUsage` 对象存在）。
         * pi 1.5.0 压缩后 `contextUsage.percent` 为 null，此时显示「压缩后待更新」，而不是沿用旧值。
         */
        fun of(percent: Double?, tokens: Long?, contextWindow: Long?, hadUsage: Boolean): ContextBar = when {
            percent != null && !percent.isNaN() -> Known(percent.coerceAtLeast(0.0), tokens, contextWindow)
            hadUsage -> AfterCompaction(contextWindow)
            else -> Unknown
        }

        const val AFTER_COMPACTION_TEXT = "压缩后待更新"
    }
}
