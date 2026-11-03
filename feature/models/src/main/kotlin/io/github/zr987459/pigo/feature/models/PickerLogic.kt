package io.github.zr987459.pigo.feature.models

import io.github.zr987459.pigo.contract.CatalogEntry
import io.github.zr987459.pigo.contract.ModelAvailability
import io.github.zr987459.pigo.contract.ModelCost
import io.github.zr987459.pigo.contract.ModelRef
import io.github.zr987459.pigo.contract.ProviderInfo
import java.util.Locale

/** 模型选择页的纯逻辑（便于 JVM 单测）：分组、搜索、点击去向、思考强度是否显示、单价与上下文的显示。 */
object PickerLogic {

    data class Section(
        val key: String,
        val title: String,
        /** 未配置凭据的服务商分组：点任意一行都去该服务商的配置页。 */
        val provider: String?,
        val ready: Boolean,
        val rows: List<CatalogEntry>,
    )

    sealed interface Click {
        data class Select(val ref: ModelRef) : Click
        /** [ref] 为用户点的那个未配置模型；服务商标题上的「去配置」没有具体模型，为 null。 */
        data class Configure(val provider: String, val ref: ModelRef? = null) : Click
    }

    fun matches(e: CatalogEntry, providerName: String?, q: String): Boolean {
        if (q.isEmpty()) return true
        return listOfNotNull(e.model.id, e.model.name, e.model.provider, providerName).any { it.lowercase(Locale.ROOT).contains(q) }
    }

    /**
     * 分组顺序：最近使用（最多 5 个，只在没搜索时显示）→ 可用（有凭据）→ 各个未配置的服务商。
     * 搜索按模型 id、名称、服务商 id 与显示名，不分大小写。
     */
    fun sections(
        entries: List<CatalogEntry>,
        providers: List<ProviderInfo>,
        recent: List<ModelRef>,
        query: String,
    ): List<Section> {
        val names = providers.associate { it.id to it.displayName }
        val q = query.trim().lowercase(Locale.ROOT)
        val filtered = entries.filter { matches(it, names[it.model.provider], q) }
        val out = ArrayList<Section>()
        if (q.isEmpty() && recent.isNotEmpty()) {
            val byRef = entries.associateBy { it.model.ref }
            val rows = recent.mapNotNull { byRef[it] }.take(5)
            if (rows.isNotEmpty()) out += Section("recent", "最近使用", null, true, rows)
        }
        val ready = filtered.filter { it.availability == ModelAvailability.READY }
        if (ready.isNotEmpty()) out += Section("ready", "可用", null, true, ready)
        val order = providers.withIndex().associate { (i, p) -> p.id to i }
        filtered.filter { it.availability == ModelAvailability.NEEDS_AUTH }
            .groupBy { it.model.provider }
            .toSortedMap(compareBy<String> { order[it] ?: Int.MAX_VALUE }.thenBy { it })
            .forEach { (p, rows) -> out += Section("p:$p", names[p] ?: p, p, false, rows) }
        return out
    }

    fun onClick(e: CatalogEntry): Click =
        if (e.availability == ModelAvailability.READY) Click.Select(e.model.ref) else Click.Configure(e.model.provider, e.model.ref)

    /** M5：只有支持推理的模型显示思考强度控件。 */
    fun showThinking(e: CatalogEntry?, levels: List<String>): Boolean = e?.model?.reasoning == true && levels.size > 1

    /** `128K`、`1M`。 */
    fun contextText(tokens: Long?): String? = when {
        tokens == null || tokens <= 0 -> null
        tokens >= 1_000_000 && tokens % 1_000_000 == 0L -> "${tokens / 1_000_000}M"
        tokens >= 1_000_000 -> String.format(Locale.ROOT, "%.1f", tokens / 1_000_000.0).removeSuffix(".0") + "M"
        tokens >= 1_000 -> "${tokens / 1_000}K"
        else -> tokens.toString()
    }

    /** 每百万 token 单价，照 pi 显示美元：`输入 $0.30 · 输出 $1.20`。全 0 显示「免费」。 */
    fun priceText(cost: ModelCost?): String? {
        cost ?: return null
        if (cost.input == 0.0 && cost.output == 0.0) return "免费 / 套餐内"
        return "输入 ${usd(cost.input)} · 输出 ${usd(cost.output)}（每百万 token）"
    }

    /** 美元金额：小于 1 美分时保留 4 位小数，否则 2 位。 */
    fun usd(v: Double): String = "$" +
        if (v != 0.0 && kotlin.math.abs(v) < 0.01) String.format(Locale.ROOT, "%.4f", v) else String.format(Locale.ROOT, "%.2f", v)

    /** 中文思考强度标签（沿用聊天页的叫法）。 */
    fun thinkingLabel(level: String): String = when (level) {
        "off" -> "关闭"
        "minimal" -> "极低"
        "low" -> "低"
        "medium" -> "中"
        "high" -> "高"
        "xhigh" -> "很高"
        "max" -> "最高"
        else -> level
    }
}

/** 服务商配置表单提交什么（审查第 1 轮 Nit：可选字段填过一次就清不掉）。纯函数，便于单测。 */
object ProviderFormLogic {
    /**
     * - 密文字段：留空 = 保持原值，所以空的不提交；
     * - 非密文字段：回填过或用户改过（[shown]）的，留空也提交空串 = 清除；没显示过旧值的空字段不提交，避免误清。
     */
    fun fieldsToSave(fields: List<io.github.zr987459.pigo.contract.ProviderField>, values: Map<String, String>, shown: Set<String>): Map<String, String> =
        buildMap {
            for (f in fields) {
                val v = values[f.envVar].orEmpty()
                when {
                    v.isNotBlank() -> put(f.envVar, v)
                    !f.secret && f.envVar in shown -> put(f.envVar, "")
                }
            }
        }
}
