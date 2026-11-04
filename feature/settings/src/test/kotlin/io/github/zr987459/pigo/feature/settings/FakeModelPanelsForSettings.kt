package io.github.zr987459.pigo.feature.settings

import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.models.ModelPanels
import io.github.zr987459.pigo.contract.models.ModelPickerTab
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SelectGroup
import io.github.zr987459.pigo.contract.session.SelectItem

/**
 * 测试用 [ModelPanels]：形状与 feature/models 的 RealModelPanels 一致（面板 id `auth.login`，
 * 分组「已连接」「全部服务商（N）」「自定义」，行 key `provider:<id>` / `custom` / `custom:<id>`）。
 * 搜索按 id 前缀 + 名字包含（拼音首字母本身由 RealModelPanels 的 ProviderSearch 负责，另有 ProviderSearchPinyinTest）。
 */
class FakeModelPanelsForSettings(
    val providerIds: List<String> = FIXTURE_42,
    val connected: Set<String> = setOf("deepseek", "anthropic"),
    val customs: List<Pair<String, String>> = listOf("my-vllm" to "http://10.0.0.2:8000"),
    val scoped: List<String> = listOf("anthropic/claude-sonnet-4-5", "deepseek/deepseek-chat"),
) : ModelPanels {
    val events = mutableListOf<String>()
    val queries = mutableListOf<String>()

    override suspend fun modelPicker(tab: ModelPickerTab, query: String): OverlayModel = OverlayModel.SelectList(
        id = "model.select", title = "模型", tab = tab.name, tabs = ModelPickerTab.entries.map { it.name },
        groups = listOf(SelectGroup(null, (if (tab == ModelPickerTab.SCOPED) scoped else scoped + "openai/gpt-5").map { SelectItem("model:$it", it, starred = it in scoped) })),
    )

    override suspend fun providerList(query: String): OverlayModel {
        queries += query
        val shown = providerIds.filter { query.isBlank() || it.startsWith(query, ignoreCase = true) || it.contains(query, ignoreCase = true) }
        val item = { id: String -> SelectItem("provider:$id", id, if (id in connected) "Key ···a1f3" else "未配置", toggled = id in connected) }
        return OverlayModel.SelectList(
            id = "auth.login", title = "服务商", query = query,
            groups = listOfNotNull(
                shown.filter { it in connected }.takeIf { it.isNotEmpty() }?.let { l -> SelectGroup("已连接", l.map(item)) },
                SelectGroup("全部服务商（${providerIds.size}）", shown.filter { it !in connected }.map(item)),
                SelectGroup("自定义", listOf(SelectItem("custom", "＋ 添加自定义服务商")) + customs.map { (id, url) -> SelectItem("custom:$id", id, url) }),
            ),
        )
    }

    override suspend fun providerForm(providerId: String): OverlayModel = OverlayModel.Form("provider.form:$providerId", providerId)
    override suspend fun customProviderForm(providerId: String?): OverlayModel = OverlayModel.Form("provider.custom" + (providerId?.let { ":$it" } ?: ""), "自定义")
    override suspend fun oauthFlow(providerId: String): OverlayModel = OverlayModel.Text("oauth:$providerId", providerId, "登录")
    override suspend fun logoutPicker(): OverlayModel = OverlayModel.SelectList("auth.logout", "退出登录")

    override suspend fun onPanelEvent(overlayId: String, key: String, value: String?): ActionResult {
        events += "$overlayId/$key"
        return when {
            key.startsWith("provider:") -> ActionResult.Overlay(providerForm(key.removePrefix("provider:")))
            key == "custom" -> ActionResult.Overlay(customProviderForm(null))
            key.startsWith("custom:") -> ActionResult.Overlay(customProviderForm(key.removePrefix("custom:")))
            key == "refresh" -> ActionResult.Toast("已刷新")
            else -> ActionResult.Toast("未知操作：$key")
        }
    }

    companion object {
        /** pi 1.5.0 的 42 个内置服务商 id（与 catalog-1.5.0.json 一致）。 */
        val FIXTURE_42: List<String> = listOf(
            "amazon-bedrock", "ant-ling", "anthropic", "azure", "baseten", "cerebras", "cloudflare-ai-gateway",
            "cloudflare-workers-ai", "deepseek", "fireworks", "github-copilot", "google", "google-vertex", "groq",
            "huggingface", "kimi-coding", "meta", "minimax", "minimax-cn", "mistral", "moonshotai", "moonshotai-cn",
            "nvidia", "openai", "openai-codex", "opencode", "opencode-go", "openrouter", "qwen-token-plan",
            "qwen-token-plan-cn", "qwen-token-plan-individual", "radius", "together", "typesafe", "vercel-ai-gateway",
            "xai", "xiaomi", "xiaomi-token-plan-ams", "xiaomi-token-plan-cn", "xiaomi-token-plan-sgp", "zai",
            "zai-coding-cn",
        )
    }
}
