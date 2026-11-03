package io.github.zr987459.pigo.core.catalog

import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.core.store.BuiltinCatalog
import io.github.zr987459.pigo.core.store.PiProviderConfigStore
import io.github.zr987459.pigo.core.store.ProviderSpecs

/**
 * 模型选择页的数据（M2）= pi 目录 ∪ `get_available_models` ∪ models.json 自定义端点。
 * 纯函数，便于单测；界面只做展示。
 */
object ModelIndex {

    enum class Source { CATALOG, CUSTOM, RUNTIME }

    data class Entry(
        val provider: String,
        val providerLabel: String,
        val id: String,
        val name: String,
        val reasoning: Boolean,
        val supportsImages: Boolean,
        val contextWindow: Long?,
        val maxTokens: Long?,
        val cost: BuiltinCatalog.Cost?,
        val thinkingLevels: List<String>?,
        /** 当前能用（有凭据 / 出现在 get_available_models 里）。 */
        val available: Boolean,
        val source: Source,
    ) {
        val key: String get() = "$provider/$id"
    }

    data class Group(
        val provider: String,
        val label: String,
        val available: Boolean,
        val canOAuth: Boolean,
        val canKey: Boolean,
        val models: List<Entry>,
    )

    data class Inputs(
        val catalog: BuiltinCatalog.Snapshot,
        /** `get_available_models` 的结果；引擎没起来时为 null。 */
        val runtimeAvailable: List<ModelInfo>?,
        val custom: Map<String, PiProviderConfigStore.CustomProvider>,
        /** 宿主侧已配置 Key 的服务商（含自定义端点）。 */
        val keyConfigured: Set<String>,
        /** auth.json 里有 OAuth 凭据的服务商。 */
        val oauthLoggedIn: Set<String>,
    )

    fun build(input: Inputs): List<Group> {
        val runtimeKeys = input.runtimeAvailable?.map { "${it.provider}/${it.id}" }?.toSet()
        val credentialed = input.keyConfigured + input.oauthLoggedIn
        fun avail(provider: String, id: String): Boolean =
            runtimeKeys?.contains("$provider/$id") ?: (provider in credentialed)

        val groups = LinkedHashMap<String, Group>()
        val order = ProviderSpecs.ALL.withIndex().associate { (i, s) -> s.provider to i }

        for (p in input.catalog.providers.sortedBy { order[it.id] ?: Int.MAX_VALUE }) {
            val label = ProviderSpecs.get(p.id)?.label ?: p.name
            val models = p.models.map { m ->
                Entry(p.id, label, m.id, m.name, m.reasoning, m.supportsImages, m.contextWindow, m.maxTokens, m.cost,
                    m.thinkingLevels, avail(p.id, m.id), Source.CATALOG)
            }
            groups[p.id] = Group(p.id, label, false, p.oauth, p.apiKeyAuth && ProviderSpecs.specFor(p.id, p.name).keyEnv != null, models)
        }
        for ((id, c) in input.custom) {
            val models = c.models.map { m ->
                Entry(id, "自定义：$id", m.id, m.name ?: m.id, m.reasoning ?: false, m.supportsImages ?: false,
                    m.contextWindow, m.maxTokens, m.cost, null, avail(id, m.id), Source.CUSTOM)
            }
            groups[id] = Group(id, "自定义：$id", false, false, true, models)
        }
        // 只出现在 get_available_models 里的模型（动态目录、用户手写的 models.json 等）
        for (m in input.runtimeAvailable.orEmpty()) {
            val g = groups[m.provider]
            if (g != null && g.models.any { it.id == m.id }) continue
            val e = Entry(m.provider, g?.label ?: m.provider, m.id, m.name ?: m.id, m.reasoning, false, m.contextWindow, null, null, null, true, Source.RUNTIME)
            groups[m.provider] = (g ?: Group(m.provider, m.provider, true, false, false, emptyList())).let { it.copy(models = it.models + e) }
        }
        return groups.values.map { g -> g.copy(available = g.models.any { it.available }) }
            .sortedByDescending { it.available } // 可用的服务商排在前面，其余保持目录顺序
    }

    /** 「可用」组：所有当前能用的模型。 */
    fun availableModels(groups: List<Group>): List<Entry> = groups.flatMap { g -> g.models.filter { it.available } }

    /** 搜索：服务商 id/名称或模型 id/名称包含关键词（不分大小写）；服务商命中时保留整组。空关键词原样返回。 */
    fun search(groups: List<Group>, query: String): List<Group> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return groups
        return groups.mapNotNull { g ->
            if (g.provider.lowercase().contains(q) || g.label.lowercase().contains(q)) return@mapNotNull g
            val ms = g.models.filter { it.id.lowercase().contains(q) || it.name.lowercase().contains(q) }
            if (ms.isEmpty()) null else g.copy(models = ms)
        }
    }

    /** 最近使用（`provider/id` 顺序），找不到的跳过。 */
    fun recent(groups: List<Group>, keys: List<String>): List<Entry> {
        val all = groups.flatMap { it.models }.associateBy { it.key }
        return keys.mapNotNull { all[it] }
    }

    /** M5：思考强度选项。模型不支持推理时为空（界面隐藏控件）。 */
    fun thinkingOptions(entry: Entry?, runtimeLevels: List<String>?): List<String> {
        if (entry == null || !entry.reasoning) return emptyList()
        return entry.thinkingLevels?.takeIf { it.isNotEmpty() } ?: runtimeLevels ?: listOf("off", "minimal", "low", "medium", "high")
    }
}
