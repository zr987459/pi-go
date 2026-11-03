package io.github.zr987459.pigo.core.store

import io.github.zr987459.pigo.contract.PresetKind

/**
 * pi 内置服务商的「怎么配」：中文名、分组、主 Key 环境变量、需要额外填写的字段。
 *
 * 服务商与模型列表本身**不在这里手写**，来自 pi 的模型目录（[BuiltinCatalog]，容器内由 `pigo-catalog.mjs`
 * 从 `@earendil-works/pi-ai` 导出）；这里只补目录里没有的配置知识。环境变量名以 pi 1.5.0
 * `dist/env-api-keys.js` 与 `docs/providers.md` 为准。目录里出现、这里没有登记的服务商按
 * [fallback] 处理（主变量 `<ID>_API_KEY`，只能用 OAuth 时没有 Key 字段）。
 */
object ProviderSpecs {

    /** 一个要用户填写的值。[secret] 为 true 的进 Keystore，界面只显示末 4 位；其余也存 Keystore，但可明文回显。 */
    data class Field(
        val envVar: String,
        val label: String,
        val secret: Boolean,
        val required: Boolean,
        val placeholder: String = "",
        val hint: String = "",
    )

    data class Spec(
        val provider: String,
        val label: String,
        val kind: PresetKind,
        /** 主 Key 环境变量；只支持 OAuth 的服务商为 null。 */
        val keyEnv: String?,
        /** 除主 Key 外的字段（区域、账户 id 等）。 */
        val extra: List<Field> = emptyList(),
        val note: String = "",
        /** 「主 Key 或某组字段」二选一时的校验（如 Bedrock：Bearer Token 或 AK/SK）。返回错误说明或 null。 */
        val validate: ((Map<String, String>) -> String?)? = null,
        /** 主 Key 是否可以不填（Bedrock 可只用 AK/SK）。 */
        val keyOptional: Boolean = false,
    ) {
        val allFields: List<Field>
            get() = listOfNotNull(keyEnv?.let { Field(it, "API Key", secret = true, required = !keyOptional) }) + extra
    }

    private const val PROXY_NOTE = "国内网络可能需要代理"

    private fun s(id: String, label: String, kind: PresetKind, env: String?, note: String = "", extra: List<Field> = emptyList()) =
        Spec(id, label, kind, env, extra, note)

    private val P = PresetKind.PAY_AS_YOU_GO
    private val C = PresetKind.CODING_PLAN

    val ALL: List<Spec> = listOf(
        // —— 国内常用 ——
        s("deepseek", "DeepSeek", P, "DEEPSEEK_API_KEY", "DeepSeek 开放平台的普通 Key"),
        s("moonshotai-cn", "Moonshot（中国站）", P, "MOONSHOT_API_KEY", "月之暗面开放平台（api.moonshot.cn）的 Key"),
        s("minimax-cn", "MiniMax（中国）", P, "MINIMAX_CN_API_KEY", "MiniMax 中国站的 Key"),
        s("zai-coding-cn", "智谱 GLM Coding Plan（中国）", C, "ZAI_CODING_CN_API_KEY", "需要套餐 Key；普通智谱开放平台 Key 请用「自定义端点」"),
        s("qwen-token-plan-cn", "通义 Token Plan（中国）", C, "QWEN_TOKEN_PLAN_CN_API_KEY", "需要套餐 Key；普通百炼 Key 请用「自定义端点」"),
        s("kimi-coding", "Kimi For Coding", C, "KIMI_API_KEY", "套餐 Key，或用 Kimi 账号登录"),
        s("xiaomi", "小米 MiMo", P, "XIAOMI_API_KEY"),
        s("xiaomi-token-plan-cn", "小米 MiMo Token Plan（中国）", C, "XIAOMI_TOKEN_PLAN_CN_API_KEY"),
        s("ant-ling", "蚂蚁百灵 Ling", P, "ANT_LING_API_KEY"),
        // —— 海外按量 ——
        s("openai", "OpenAI", P, "OPENAI_API_KEY", "$PROXY_NOTE；也可用 ChatGPT 订阅登录"),
        s("anthropic", "Anthropic", P, "ANTHROPIC_API_KEY", "$PROXY_NOTE；也可用 Claude Pro/Max 订阅登录"),
        s("google", "Google Gemini", P, "GEMINI_API_KEY", PROXY_NOTE),
        s("openrouter", "OpenRouter", P, "OPENROUTER_API_KEY", "$PROXY_NOTE；也可用 OpenRouter 账号登录"),
        s("xai", "xAI（Grok）", P, "XAI_API_KEY", "$PROXY_NOTE；也可用 Grok/X 订阅登录"),
        s("mistral", "Mistral", P, "MISTRAL_API_KEY", PROXY_NOTE),
        s("groq", "Groq", P, "GROQ_API_KEY", PROXY_NOTE),
        s("cerebras", "Cerebras", P, "CEREBRAS_API_KEY", PROXY_NOTE),
        s("moonshotai", "Moonshot（国际站）", P, "MOONSHOT_API_KEY", "api.moonshot.ai 的 Key"),
        s("minimax", "MiniMax（国际）", P, "MINIMAX_API_KEY"),
        s("zai", "Z.AI（国际）", P, "ZAI_API_KEY"),
        s("nvidia", "NVIDIA NIM", P, "NVIDIA_API_KEY", PROXY_NOTE),
        s("huggingface", "Hugging Face", P, "HF_TOKEN", PROXY_NOTE),
        s("fireworks", "Fireworks", P, "FIREWORKS_API_KEY", PROXY_NOTE),
        s("together", "Together AI", P, "TOGETHER_API_KEY", PROXY_NOTE),
        s("baseten", "Baseten", P, "BASETEN_API_KEY", PROXY_NOTE),
        s("vercel-ai-gateway", "Vercel AI Gateway", P, "AI_GATEWAY_API_KEY", PROXY_NOTE),
        s("opencode", "OpenCode Zen", P, "OPENCODE_API_KEY", PROXY_NOTE),
        s("opencode-go", "OpenCode Go", C, "OPENCODE_API_KEY", PROXY_NOTE),
        s("meta", "Meta", P, "META_API_KEY", "$PROXY_NOTE；也可用 Muse 订阅登录"),
        s("radius", "Radius", P, "RADIUS_API_KEY", "也可用 Radius 账号登录"),
        s("typesafe", "TypeSafe（分类模型）", P, "TYPESAFE_API_KEY", "只提供分类模型，不用于对话"),
        s("qwen-token-plan", "通义 Token Plan（国际）", C, "QWEN_TOKEN_PLAN_API_KEY"),
        s("qwen-token-plan-individual", "通义 Token Plan（个人）", C, "QWEN_TOKEN_PLAN_API_KEY"),
        s("xiaomi-token-plan-ams", "小米 MiMo Token Plan（阿姆斯特丹）", C, "XIAOMI_TOKEN_PLAN_AMS_API_KEY"),
        s("xiaomi-token-plan-sgp", "小米 MiMo Token Plan（新加坡）", C, "XIAOMI_TOKEN_PLAN_SGP_API_KEY"),
        // —— 订阅（只能登录） ——
        s("github-copilot", "GitHub Copilot", C, "COPILOT_GITHUB_TOKEN", "推荐用 GitHub 账号登录（设备码）；也可填 GitHub Token"),
        s("openai-codex", "ChatGPT Plus/Pro（Codex 旧版）", C, null, "只能用 ChatGPT 账号登录"),
        // —— 需要额外配置的云平台 ——
        Spec(
            "amazon-bedrock", "Amazon Bedrock", P, "AWS_BEARER_TOKEN_BEDROCK",
            extra = listOf(
                Field("AWS_ACCESS_KEY_ID", "Access Key ID", secret = false, required = false, "AKIA…", "不用 Bearer Token 时填写"),
                Field("AWS_SECRET_ACCESS_KEY", "Secret Access Key", secret = true, required = false),
                Field("AWS_SESSION_TOKEN", "Session Token（临时凭证才需要）", secret = true, required = false),
                Field("AWS_REGION", "区域", secret = false, required = true, "us-west-2"),
            ),
            note = "填 Bedrock Bearer Token，或填 Access Key ID + Secret Access Key；区域必填",
            keyOptional = true,
            validate = { v ->
                val bearer = !v["AWS_BEARER_TOKEN_BEDROCK"].isNullOrBlank()
                val pair = !v["AWS_ACCESS_KEY_ID"].isNullOrBlank() && !v["AWS_SECRET_ACCESS_KEY"].isNullOrBlank()
                when {
                    !bearer && !pair -> "请填写 Bearer Token，或同时填写 Access Key ID 与 Secret Access Key"
                    !bearer && (v["AWS_ACCESS_KEY_ID"].isNullOrBlank() != v["AWS_SECRET_ACCESS_KEY"].isNullOrBlank()) ->
                        "Access Key ID 与 Secret Access Key 要一起填写"
                    else -> null
                }
            },
        ),
        Spec(
            "azure", "Azure OpenAI", P, "AZURE_OPENAI_API_KEY",
            extra = listOf(
                Field("AZURE_OPENAI_BASE_URL", "接口地址", secret = false, required = false, "https://xxx.openai.azure.com", "与资源名二选一"),
                Field("AZURE_OPENAI_RESOURCE_NAME", "资源名", secret = false, required = false, "your-resource"),
                Field("AZURE_OPENAI_API_VERSION", "API 版本（可选）", secret = false, required = false, "v1"),
                Field("AZURE_OPENAI_DEPLOYMENT_NAME_MAP", "部署名映射（可选）", secret = false, required = false, "gpt-5.4=my-deploy"),
            ),
            note = "API Key 必填；接口地址与资源名至少填一个",
            validate = { v ->
                if (v["AZURE_OPENAI_BASE_URL"].isNullOrBlank() && v["AZURE_OPENAI_RESOURCE_NAME"].isNullOrBlank()) "请填写接口地址或资源名" else null
            },
        ),
        Spec(
            "cloudflare-ai-gateway", "Cloudflare AI Gateway", P, "CLOUDFLARE_API_KEY",
            extra = listOf(
                Field("CLOUDFLARE_ACCOUNT_ID", "账户 ID", secret = false, required = true),
                Field("CLOUDFLARE_GATEWAY_ID", "网关 ID", secret = false, required = true),
            ),
            note = "需要 API Token、账户 ID 与网关 ID",
        ),
        Spec(
            "cloudflare-workers-ai", "Cloudflare Workers AI", P, "CLOUDFLARE_API_KEY",
            extra = listOf(Field("CLOUDFLARE_ACCOUNT_ID", "账户 ID", secret = false, required = true)),
            note = "需要 API Token 与账户 ID",
        ),
        Spec(
            "google-vertex", "Google Vertex AI", P, "GOOGLE_CLOUD_API_KEY",
            extra = listOf(
                Field("GOOGLE_CLOUD_PROJECT", "项目 ID（可选）", secret = false, required = false),
                Field("GOOGLE_CLOUD_LOCATION", "区域（可选）", secret = false, required = false, "us-central1"),
            ),
            note = "填 Google Cloud API Key（手机上不支持 gcloud 登录）",
        ),
    )

    private val byId = ALL.associateBy { it.provider }

    fun get(provider: String): Spec? = byId[provider]

    /** 目录里有、上表没登记的服务商（pi 升级后新增）：按通用规则给一个可用的配置方式。 */
    fun fallback(provider: String, name: String, hasApiKey: Boolean): Spec =
        Spec(
            provider, name, PresetKind.PAY_AS_YOU_GO,
            if (hasApiKey) provider.uppercase().replace(Regex("[^A-Z0-9]"), "_") + "_API_KEY" else null,
            note = "pi 新增的服务商，环境变量名按通用规则推断，如不可用请改用自定义端点",
        )

    fun specFor(provider: String, name: String = provider, hasApiKey: Boolean = true): Spec =
        get(provider) ?: fallback(provider, name, hasApiKey)

    /**
     * 多个服务商共用同一个环境变量（如 moonshotai 与 moonshotai-cn 都读 `MOONSHOT_API_KEY`）。
     * 这些服务商在 `auth.json` 里写 `{"type":"api_key","key":"$PIGO_PK_<ID>"}`：**只写变量引用、不写 Key**，
     * Key 仍由引擎启动时经进程环境注入（DESIGN.md §3.10），两个服务商各用各的 Key 互不覆盖。
     */
    fun sharedKeyEnv(): Set<String> =
        ALL.mapNotNull { it.keyEnv }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    fun usesAuthReference(spec: Spec): Boolean = spec.keyEnv != null && spec.keyEnv in sharedKeyEnv()

    /** 共用变量的服务商在 auth.json 里引用的私有变量名。 */
    fun privateKeyEnv(provider: String): String = "PIGO_PK_" + provider.uppercase().replace(Regex("[^A-Z0-9]"), "_")

    /** 代理相关的环境变量（M9）。 */
    val PROXY_ENVS = listOf("HTTPS_PROXY", "HTTP_PROXY", "https_proxy", "http_proxy", "NO_PROXY", "no_proxy")
}
