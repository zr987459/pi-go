package io.github.zr987459.pigo.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.zr987459.pigo.contract.PresetKind
import io.github.zr987459.pigo.contract.ProviderConfigStore
import io.github.zr987459.pigo.core.ui.InfoCard
import io.github.zr987459.pigo.core.ui.PlaceholderScreen

/** 设置占位页（mvp/sessions-settings 替换）。 */
@Composable
fun SettingsPlaceholder(providers: ProviderConfigStore, modifier: Modifier = Modifier) {
    val groups = providers.presets().groupBy { it.kind }
    PlaceholderScreen(
        title = "设置",
        description = "占位页：模型与 Key、运行环境、关于由 mvp/sessions-settings 实现。",
        modifier = modifier,
        testTag = "page:settings",
    ) {
        PresetKind.entries.forEach { kind ->
            val label = when (kind) {
                PresetKind.PAY_AS_YOU_GO -> "按量 API"
                PresetKind.CODING_PLAN -> "编程套餐（需要套餐 Key）"
                PresetKind.CUSTOM_OPENAI -> "自定义 OpenAI 兼容"
            }
            InfoCard(label, groups[kind].orEmpty().joinToString("、") { it.label })
        }
    }
}
