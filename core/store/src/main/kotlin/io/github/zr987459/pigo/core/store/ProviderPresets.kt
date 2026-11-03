package io.github.zr987459.pigo.core.store

import io.github.zr987459.pigo.contract.PresetKind
import io.github.zr987459.pigo.contract.ProviderPreset

/**
 * DESIGN.md §3.10 的 Provider 预设表：内置服务商由 pi 模型目录生成（[fromCatalog]），环境变量名见 [ProviderSpecs]。
 * 自定义端点预填的 baseUrl（服务商公开的 OpenAI 兼容地址，联调时以服务商当前文档为准）：
 * - 阿里云百炼 OpenAI 兼容：https://dashscope.aliyuncs.com/compatible-mode/v1
 * - 智谱开放平台：https://open.bigmodel.cn/api/paas/v4
 */
object ProviderPresets {
    /** 「其他 OpenAI 兼容」的占位 provider：用户自己起 id。 */
    const val OTHER_CUSTOM = "custom"

    /** 三个自定义 OpenAI 兼容入口（不在 pi 目录里）。 */
    val CUSTOM: List<ProviderPreset> = listOf(
        ProviderPreset("bailian", "阿里云百炼（按量）", PresetKind.CUSTOM_OPENAI, "PIGO_KEY_BAILIAN", "https://dashscope.aliyuncs.com/compatible-mode/v1", "模型 id 自己填，例如 qwen-plus"),
        ProviderPreset("zhipu", "智谱开放平台（按量）", PresetKind.CUSTOM_OPENAI, "PIGO_KEY_ZHIPU", "https://open.bigmodel.cn/api/paas/v4", "模型 id 自己填，例如 glm-4.6"),
        ProviderPreset(OTHER_CUSTOM, "其他 OpenAI 兼容", PresetKind.CUSTOM_OPENAI, null, null, "接口地址和模型 id 都自己填"),
    )

    /**
     * 由 pi 模型目录生成（W2 / feature-gap M1）：目录里每个支持 API Key 的服务商一项，顺序按 [ProviderSpecs.ALL]
     * （国内常用在前），目录里有而表里没登记的排在最后。只能 OAuth 登录的（如 openai-codex）不在这里，见 `:core:catalog`。
     */
    fun fromCatalog(snapshot: BuiltinCatalog.Snapshot): List<ProviderPreset> {
        val order = ProviderSpecs.ALL.withIndex().associate { (i, s) -> s.provider to i }
        return snapshot.providers
            .filter { it.apiKeyAuth }
            .sortedBy { order[it.id] ?: Int.MAX_VALUE }
            .mapNotNull { p ->
                val spec = ProviderSpecs.specFor(p.id, p.name, p.apiKeyAuth)
                val env = spec.keyEnv ?: return@mapNotNull null
                ProviderPreset(p.id, spec.label, spec.kind, env, null, spec.note)
            }
    }

    val ALL: List<ProviderPreset> by lazy { fromCatalog(BuiltinCatalog.bundled()) + CUSTOM }

    val BUILT_IN: List<ProviderPreset> by lazy { ALL.filter { it.kind != PresetKind.CUSTOM_OPENAI } }

    fun builtInAlias(provider: String) = "key.$provider"
    fun customAlias(id: String) = "key.custom.$id"

    /** 自定义 id 规范化：小写，只留字母数字、`-`、`_`。 */
    fun normalizeCustomId(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-')

    /** `$PIGO_KEY_<ID>` 里的变量名：大写，非字母数字换成 `_`。 */
    fun customEnvVar(id: String): String = "PIGO_KEY_" + id.uppercase().replace(Regex("[^A-Z0-9]"), "_")
}
