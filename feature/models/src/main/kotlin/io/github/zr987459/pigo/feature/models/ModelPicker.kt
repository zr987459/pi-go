package io.github.zr987459.pigo.feature.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.AppSettingsStore
import io.github.zr987459.pigo.contract.CatalogEntry
import io.github.zr987459.pigo.contract.CostDisplaySettings
import io.github.zr987459.pigo.contract.ModelCatalog
import io.github.zr987459.pigo.contract.ModelRef
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.ProviderInfo
import kotlinx.coroutines.launch

/** 测试标签（Compose 测试与 W5 接线共用）。 */
object ModelTags {
    const val SEARCH = "models.search"
    const val ROW_PREFIX = "models.row:"
    const val SECTION_PREFIX = "models.section:"
    const val THINKING = "models.thinking"
    const val LIST = "models.list"
}

/**
 * 模型选择页（M2、M4、M5）：放在全屏底部弹层里用。W5 把顶栏「模型▾」接到这里。
 *
 * - 列出 pi 目录里**全部**模型（含未配凭据的，灰显），可用的在前，最近使用 5 个置顶；
 * - 显示上下文窗口、是否推理、是否看图、单价（美元，可选同时显示人民币）；
 * - 点可用模型 → [onSelect]（调用方执行 `set_model`；生成中切换下一轮生效，[generating] 为真时提示）；
 *   点未配置模型 → [onConfigure]（跳到该服务商的 Key / 登录页），同时 [onConfigureModel] 收到被点的那个模型，
 *   供调用方在配置完成后自动选中它（W5 验收①）；服务商标题上的「去配置」只回调 [onConfigure]；
 * - 底部思考强度分段控件只对支持推理的当前模型显示。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelPicker(
    catalog: ModelCatalog,
    settings: AppSettingsStore?,
    current: ModelRef?,
    thinking: String?,
    thinkingLevels: List<String>,
    generating: Boolean,
    onSelect: (ModelRef) -> Unit,
    onThinking: (String) -> Unit,
    onConfigure: (provider: String) -> Unit,
    modifier: Modifier = Modifier,
    /** 点的是某个未配置的模型时先回调它（在 [onConfigure] 之前）；为 null 时只回调 [onConfigure]（旧行为）。 */
    onConfigureModel: ((ModelRef) -> Unit)? = null,
) {
    var entries by remember { mutableStateOf<List<CatalogEntry>>(emptyList()) }
    var providers by remember { mutableStateOf<List<ProviderInfo>>(emptyList()) }
    var recent by remember { mutableStateOf<List<ModelRef>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val cost = settings?.costDisplay?.collectAsState()?.value ?: CostDisplaySettings()

    LaunchedEffect(catalog) {
        when (val p = catalog.providers()) { is Outcome.Ok -> providers = p.value; is Outcome.Err -> error = p.message }
        when (val e = catalog.entries()) { is Outcome.Ok -> entries = e.value; is Outcome.Err -> error = e.message }
        recent = catalog.recent()
    }
    val sections = remember(entries, providers, recent, query) { PickerLogic.sections(entries, providers, recent, query) }
    val currentEntry = remember(entries, current) { entries.firstOrNull { it.model.ref == current } }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("选择模型", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索模型或服务商，例如 qwen、claude") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(ModelTags.SEARCH),
        )
        if (generating) {
            Text("正在生成：切换后从下一轮开始生效", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 4.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
        LazyColumn(Modifier.weight(1f).testTag(ModelTags.LIST)) {
            if (sections.isEmpty() && entries.isNotEmpty()) {
                item { Text("没有匹配「$query」的模型", modifier = Modifier.padding(vertical = 24.dp)) }
            }
            for (s in sections) {
                item(key = "h:" + s.key) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp).testTag(ModelTags.SECTION_PREFIX + s.key),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(s.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (s.provider != null) {
                            TextButton(onClick = { onConfigure(s.provider) }) { Text("去配置") }
                        }
                    }
                }
                items(s.rows, key = { s.key + "/" + it.model.ref.toString() }) { e ->
                    ModelRow(e, providers.firstOrNull { it.id == e.model.provider }?.displayName, cost, selected = e.model.ref == current) {
                        when (val c = PickerLogic.onClick(e)) {
                            is PickerLogic.Click.Select -> {
                                scope.launch { catalog.markUsed(c.ref) }
                                onSelect(c.ref)
                            }
                            is PickerLogic.Click.Configure -> {
                                c.ref?.let { onConfigureModel?.invoke(it) }
                                onConfigure(c.provider)
                            }
                        }
                    }
                }
            }
        }
        if (PickerLogic.showThinking(currentEntry, thinkingLevels)) {
            HorizontalDivider()
            Text("思考强度", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(ModelTags.THINKING)) {
                thinkingLevels.forEachIndexed { i, lv ->
                    SegmentedButton(
                        selected = lv == thinking,
                        onClick = { onThinking(lv) },
                        shape = SegmentedButtonDefaults.itemShape(i, thinkingLevels.size),
                    ) { Text(PickerLogic.thinkingLabel(lv), maxLines = 1) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelRow(e: CatalogEntry, providerName: String?, cost: CostDisplaySettings, selected: Boolean, onClick: () -> Unit) {
    val ready = e.availability == io.github.zr987459.pigo.contract.ModelAvailability.READY
    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .alpha(if (ready) 1f else 0.5f)
            .padding(vertical = 8.dp)
            .testTag(ModelTags.ROW_PREFIX + e.model.ref),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                (if (selected) "✓ " else "") + (e.model.name ?: e.model.id),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(providerName ?: e.model.provider, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PickerLogic.contextText(e.model.contextWindow)?.let { AssistChip(onClick = onClick, label = { Text("上下文 $it") }) }
            if (e.model.reasoning) AssistChip(onClick = onClick, label = { Text("推理") })
            if (e.model.supportsImages) AssistChip(onClick = onClick, label = { Text("看图") })
            if (!ready) AssistChip(onClick = onClick, label = { Text("未配置") })
        }
        PickerLogic.priceText(e.model.cost, cost)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
