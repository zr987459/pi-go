package io.github.zr987459.pigo.core.store

import io.github.zr987459.pigo.contract.PresetKind
import io.github.zr987459.pigo.contract.ProviderPreset

/**
 * DESIGN.md §3.10 的 Provider 预设表（provider id、环境变量名取自 pi 1.5.0 `providers.md`）。
 * 自定义端点预填的 baseUrl（服务商公开的 OpenAI 兼容地址，联调时以服务商当前文档为准）：
 * - 阿里云百炼 OpenAI 兼容：https://dashscope.aliyuncs.com/compatible-mode/v1
 * - 智谱开放平台：https://open.bigmodel.cn/api/paas/v4
 */
object ProviderPresets {
    /** 「其他 OpenAI 兼容」的占位 provider：用户自己起 id。 */
    const val OTHER_CUSTOM = "custom"

    val ALL: List<ProviderPreset> = listOf(
        ProviderPreset("deepseek", "DeepSeek", PresetKind.PAY_AS_YOU_GO, "DEEPSEEK_API_KEY", null, "DeepSeek 开放平台的普通 Key"),
        ProviderPreset("moonshotai-cn", "Moonshot（中国站）", PresetKind.PAY_AS_YOU_GO, "MOONSHOT_API_KEY", null, "月之暗面开放平台（api.moonshot.cn）的 Key"),
        ProviderPreset("minimax-cn", "MiniMax（中国）", PresetKind.PAY_AS_YOU_GO, "MINIMAX_CN_API_KEY", null, "MiniMax 中国站的 Key"),
        ProviderPreset("openrouter", "OpenRouter", PresetKind.PAY_AS_YOU_GO, "OPENROUTER_API_KEY", null, "国内网络可能需要代理"),
        ProviderPreset("openai", "OpenAI", PresetKind.PAY_AS_YOU_GO, "OPENAI_API_KEY", null, "国内网络可能需要代理"),
        ProviderPreset("anthropic", "Anthropic", PresetKind.PAY_AS_YOU_GO, "ANTHROPIC_API_KEY", null, "国内网络可能需要代理"),
        ProviderPreset("zai-coding-cn", "智谱 GLM Coding Plan（中国）", PresetKind.CODING_PLAN, "ZAI_CODING_CN_API_KEY", null, "需要套餐 Key；普通智谱开放平台 Key 请用下方「智谱开放平台」"),
        ProviderPreset("qwen-token-plan-cn", "通义 Token Plan（中国）", PresetKind.CODING_PLAN, "QWEN_TOKEN_PLAN_CN_API_KEY", null, "需要套餐 Key；普通百炼 Key 请用下方「阿里云百炼」"),
        ProviderPreset("kimi-coding", "Kimi For Coding", PresetKind.CODING_PLAN, "KIMI_API_KEY", null, "需要套餐 Key；普通 Moonshot Key 请用上方「Moonshot」"),
        ProviderPreset("bailian", "阿里云百炼（按量）", PresetKind.CUSTOM_OPENAI, "PIGO_KEY_BAILIAN", "https://dashscope.aliyuncs.com/compatible-mode/v1", "模型 id 自己填，例如 qwen-plus"),
        ProviderPreset("zhipu", "智谱开放平台（按量）", PresetKind.CUSTOM_OPENAI, "PIGO_KEY_ZHIPU", "https://open.bigmodel.cn/api/paas/v4", "模型 id 自己填，例如 glm-4.6"),
        ProviderPreset(OTHER_CUSTOM, "其他 OpenAI 兼容", PresetKind.CUSTOM_OPENAI, null, null, "接口地址和模型 id 都自己填"),
    )

    val BUILT_IN: List<ProviderPreset> = ALL.filter { it.kind != PresetKind.CUSTOM_OPENAI }

    fun builtInAlias(provider: String) = "key.$provider"
    fun customAlias(id: String) = "key.custom.$id"

    /** 自定义 id 规范化：小写，只留字母数字、`-`、`_`。 */
    fun normalizeCustomId(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-')

    /** `$PIGO_KEY_<ID>` 里的变量名：大写，非字母数字换成 `_`。 */
    fun customEnvVar(id: String): String = "PIGO_KEY_" + id.uppercase().replace(Regex("[^A-Z0-9]"), "_")
}
