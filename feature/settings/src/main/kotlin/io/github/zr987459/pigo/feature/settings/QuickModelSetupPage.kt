package io.github.zr987459.pigo.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import io.github.zr987459.pigo.feature.settings.ui.PlainFilledButton
import io.github.zr987459.pigo.feature.settings.ui.PlainOutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.feature.settings.ui.BackButton
import io.github.zr987459.pigo.feature.settings.ui.LargeTitle
import io.github.zr987459.pigo.feature.settings.ui.PlainBackdrop
import io.github.zr987459.pigo.feature.settings.ui.SettingsColors
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 极简模型配置里的一个服务商（[needsBaseUrl] = 自定义 OpenAI 兼容端点，要填接口地址）。 */
data class QuickPreset(
    val id: String,
    val label: String,
    val needsBaseUrl: Boolean = false,
    val keyUrl: String = "",
    val hint: String = "",
    /** 模型列表来自 pi 自带目录：选中就能列出，不用先填 Key。 */
    val listWithoutKey: Boolean = false,
)

/** 测试连接的结果：[ok] 成败 + 中文说明（成功 / Key 错 / 网络不通 / 模型不存在…）。 */
data class QuickCheck(val ok: Boolean, val message: String)

/** 页面要用的动作，由装配层接到 `core/store` 的 QuickModelSetup。 */
class QuickSetupActions(
    val presets: List<QuickPreset>,
    /** 当前会话正在用的模型（`provider/id`），没有为 null。 */
    val currentModel: StateFlow<String?>? = null,
    val loadModels: suspend (presetId: String, key: String, baseUrl: String) -> Outcome<List<String>>,
    val test: suspend (presetId: String, key: String, model: String, baseUrl: String) -> QuickCheck,
    /** 「保存并使用」：在 App 级作用域里跑，页面只看它的 [QuickSaveRunner.state]（返回 / 转屏不中断）。 */
    val saver: QuickSaveRunner,
)

object QuickTags {
    const val PAGE = "page:quickModels"
    const val LIST = "quick.list"
    fun preset(id: String) = "quick.preset:$id"
    const val BASE_URL = "quick.baseUrl"
    const val KEY = "quick.key"
    const val FETCH = "quick.fetch"
    const val MODEL_INPUT = "quick.modelInput"
    fun model(id: String) = "quick.model:$id"
    const val TEST = "quick.test"
    const val SAVE = "quick.save"
    const val RESULT = "quick.result"
    const val CURRENT = "quick.current"
    const val ADVANCED = "quick.advanced"
}

/**
 * 设置 › 模型（r4）：选服务商 → 填 Key → 选模型（自动拉列表）→ 测试连接 → 保存并使用。
 * 其余服务商、账号登录（OAuth）、代理、运行环境都在「高级设置」（[onAdvanced]，即原来的完整页面）。
 */
@Composable
fun QuickModelSetupPage(
    actions: QuickSetupActions,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onAdvanced: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var presetId by rememberSaveable { mutableStateOf(actions.presets.firstOrNull()?.id.orEmpty()) }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    // Key 只在内存里（不进 saveable：进程被杀后不落到 Bundle 里）。
    var key by remember { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    // 上一次真正选中的服务商：转屏 / 进程重建后 LaunchedEffect 会重跑，只有服务商真变了才清空已选模型（审查 N5）。
    var loadedPreset by rememberSaveable { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<QuickCheck?>(null) }
    val saveState by actions.saver.state.collectAsState()
    val saving = saveState is QuickSaveState.Running
    val current = actions.currentModel?.collectAsState()?.value
    val preset = actions.presets.firstOrNull { it.id == presetId }

    fun fetch() = scope.launch {
        busy = "正在获取模型列表…"; result = null; actions.saver.acknowledge()
        when (val r = actions.loadModels(presetId, key, baseUrl)) {
            is Outcome.Ok -> {
                models = r.value
                if (model.isBlank() || model !in r.value) model = r.value.firstOrNull().orEmpty()
            }
            is Outcome.Err -> { models = emptyList(); result = QuickCheck(false, r.message) }
        }
        busy = null
    }

    // 内置服务商的模型来自 pi 的目录，不用 Key：选中就拉。
    LaunchedEffect(presetId) {
        if (loadedPreset != null && loadedPreset != presetId) { model = ""; result = null; actions.saver.acknowledge() }
        loadedPreset = presetId
        models = emptyList()
        if (preset?.listWithoutKey == true) fetch()
    }
    // 保存成功后清掉内存里的 Key。
    LaunchedEffect(saveState) { if (saveState is QuickSaveState.Done) key = "" }

    // r5：照 OpenMinis 设置页（`ui/components/SettingsSection.kt` + `SectionDesign.kt` + `SectionTextField.kt`）的分组卡片外观：
    // iOS 式分组底色、强调色小标题、16dp 圆角卡片、行高 ≥44dp、行间 0.5dp 内缩细线、无边框输入框、选中行右侧 ✓。逻辑不变。
    PlainBackdrop(modifier.testTag(QuickTags.PAGE)) {
        LazyColumn(Modifier.fillMaxSize().testTag(QuickTags.LIST), contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp)) {
            if (onBack != null) item { BackButton(onBack, Modifier.padding(start = 12.dp).testTag("settings:back")) }
            item { LargeTitle("模型", subtitle = "选服务商、填 Key、选模型，保存后马上就能聊") }
            item {
                Card(Slice.Single) {
                    Row(
                        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(QuickTags.CURRENT)
                            .heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("正在使用", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            current ?: "还没有", style = MaterialTheme.typography.bodyMedium, color = SettingsColors.faint,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            item { Step("服务商") }
            actions.presets.forEachIndexed { i, p ->
                item(key = "p:${p.id}") {
                    Card(Slice.of(i, actions.presets.size)) {
                        CheckRow(p.label, selected = p.id == presetId, divider = i > 0, modifier = Modifier.testTag(QuickTags.preset(p.id))) { presetId = p.id }
                    }
                }
            }
            if (preset?.hint?.isNotBlank() == true) item { Footer(preset.hint) }
            if (preset?.needsBaseUrl == true) {
                item { Step("接口地址（Base URL）") }
                item {
                    Card(Slice.Single) {
                        CardField(baseUrl, { baseUrl = it; models = emptyList() }, "https://api.example.com/v1", Modifier.testTag(QuickTags.BASE_URL))
                    }
                }
            }
            item { Step("API Key") }
            item {
                Card(if (preset?.keyUrl?.isNotBlank() == true) Slice.Top else Slice.Single) {
                    CardField(key, { key = it.trim(); result = null }, "粘贴服务商给的 Key", Modifier.testTag(QuickTags.KEY), password = true)
                }
            }
            if (preset?.keyUrl?.isNotBlank() == true) item {
                Card(Slice.Bottom) {
                    LinkRow("没有 Key？去 ${preset.label} 申请", divider = true) { runCatching { uri.openUri(preset.keyUrl) } }
                }
            }
            item { Step("模型") }
            // 输入框里是列表中的某个模型（自动选中的第一个 / 点选的）时列出全部，方便换；只有手打了列表外的文字才按它筛选。
            // （此前选中后列表只剩它自己，别的模型看不到——QuickModelSetupUiTest 首次上 CI 模拟器时抓到。）
            val shown = (if (model.isBlank() || model in models) models else models.filter { it.contains(model, ignoreCase = true) })
                .ifEmpty { models }.take(40)
            item {
                Card(Slice.Top) {
                    CardField(model, { model = it.trim(); result = null }, "从下面选，或直接输入模型名", Modifier.testTag(QuickTags.MODEL_INPUT))
                }
            }
            shown.forEach { id ->
                item(key = "m:$id") {
                    Card(Slice.Middle) {
                        CheckRow(id, selected = id == model, divider = true, modifier = Modifier.testTag(QuickTags.model(id))) { model = id; result = null }
                    }
                }
            }
            item {
                Card(Slice.Bottom) {
                    LinkRow(if (models.isEmpty()) "获取模型列表" else "刷新列表", divider = true, enabled = busy == null, modifier = Modifier.testTag(QuickTags.FETCH)) { fetch() }
                }
            }
            item { Step("测试并保存") }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlainOutlinedButton(
                        "测试连接",
                        onClick = { actions.saver.acknowledge(); scope.launch { busy = "正在测试…"; result = actions.test(presetId, key, model, baseUrl); busy = null } },
                        enabled = busy == null && !saving, modifier = Modifier.weight(1f).testTag(QuickTags.TEST),
                    )
                    PlainFilledButton(
                        "保存并使用",
                        onClick = { result = null; actions.saver.start(QuickSaveRequest(presetId, key, model, models, baseUrl)) },
                        enabled = busy == null && !saving && model.isNotBlank(), modifier = Modifier.weight(1f).testTag(QuickTags.SAVE),
                    )
                }
            }
            item {
                // 保存的进度 / 结果优先（它在 App 作用域里跑，离开再回来也接得上），其次是本页的获取列表、测试连接。
                val shownResult = when (val s = saveState) {
                    is QuickSaveState.Done -> QuickCheck(true, s.message)
                    is QuickSaveState.Failed -> QuickCheck(false, s.message)
                    else -> result
                }
                val progress = (saveState as? QuickSaveState.Running)?.message ?: busy
                val text = progress ?: shownResult?.let { (if (it.ok) "✓ " else "✗ ") + it.message }
                if (text != null) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            progress != null -> SettingsColors.faint
                            shownResult?.ok == true -> Color(0xFF34C759)
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).testTag(QuickTags.RESULT),
                    )
                }
            }
            if (onAdvanced != null) item {
                Column(Modifier.padding(top = 16.dp)) {
                    Card(Slice.Single) {
                        LinkRow("高级设置（更多服务商、账号登录、代理、运行环境）", divider = false, chevron = true, modifier = Modifier.testTag(QuickTags.ADVANCED), onClick = onAdvanced)
                    }
                }
            }
        }
    }
}

/** 一段分组卡片：同一组的相邻行拼成一张 16dp 圆角卡片（首行上圆角、末行下圆角）。 */
private enum class Slice(val top: Boolean, val bottom: Boolean) {
    Single(true, true), Top(true, false), Middle(false, false), Bottom(false, true);
    companion object {
        fun of(i: Int, n: Int) = when {
            n == 1 -> Single
            i == 0 -> Top
            i == n - 1 -> Bottom
            else -> Middle
        }
    }
}

@Composable
private fun Card(slice: Slice, content: @Composable () -> Unit) {
    val r = 16.dp
    val shape = RoundedCornerShape(
        topStart = if (slice.top) r else 0.dp, topEnd = if (slice.top) r else 0.dp,
        bottomStart = if (slice.bottom) r else 0.dp, bottomEnd = if (slice.bottom) r else 0.dp,
    )
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = if (slice.bottom) 8.dp else 0.dp),
    ) { Column(Modifier.fillMaxWidth()) { content() } }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun CheckRow(label: String, selected: Boolean, divider: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (divider) RowDivider()
    Row(
        modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) Text("✓", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun LinkRow(label: String, divider: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, chevron: Boolean = false, onClick: () -> Unit) {
    if (divider) RowDivider()
    Row(
        modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
            color = if (chevron) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f),
        )
        if (chevron) Text("›", style = MaterialTheme.typography.titleMedium, color = SettingsColors.faint)
    }
}

/** 卡片里的无边框输入框（OpenMinis `SectionTextField`：边框、底色透明，卡片本身是唯一边界）。 */
@Composable
private fun CardField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, password: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        placeholder = { Text(placeholder, color = SettingsColors.faint) },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent,
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
    )
}

@Composable
private fun Footer(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = SettingsColors.faint, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
}

@Composable
private fun Step(text: String) {
    // r5：OpenMinis 分组小标题（强调色 labelMedium）
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp).semantics { heading() })
}
