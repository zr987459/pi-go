package io.github.zr987459.pigo.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    /** 保存并立即使用；成功返回给用户的一句话。 */
    val save: suspend (presetId: String, key: String, model: String, models: List<String>, baseUrl: String) -> Outcome<String>,
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
@OptIn(ExperimentalLayoutApi::class)
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
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<QuickCheck?>(null) }
    val current = actions.currentModel?.collectAsState()?.value
    val preset = actions.presets.firstOrNull { it.id == presetId }

    fun fetch() = scope.launch {
        busy = "正在获取模型列表…"; result = null
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
        models = emptyList(); model = ""; result = null
        if (preset?.listWithoutKey == true) fetch()
    }

    PlainBackdrop(modifier.testTag(QuickTags.PAGE)) {
        LazyColumn(Modifier.fillMaxSize().testTag(QuickTags.LIST), contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp)) {
            if (onBack != null) item { BackButton(onBack, Modifier.padding(start = 12.dp).testTag("settings:back")) }
            item { LargeTitle("模型", subtitle = "选服务商、填 Key、选模型，保存后马上就能聊") }
            item {
                Text(
                    "正在使用：" + (current ?: "还没有"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SettingsColors.faint,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag(QuickTags.CURRENT),
                )
            }
            item { Step("1  选服务商") }
            item {
                FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.presets.forEach { p ->
                        FilterChip(
                            selected = p.id == presetId,
                            onClick = { presetId = p.id },
                            label = { Text(p.label) },
                            modifier = Modifier.testTag(QuickTags.preset(p.id)),
                        )
                    }
                }
            }
            if (preset?.hint?.isNotBlank() == true) item { Hint(preset.hint) }
            if (preset?.needsBaseUrl == true) item {
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it; models = emptyList() },
                    label = { Text("接口地址（Base URL）") }, placeholder = { Text("https://api.example.com/v1") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).testTag(QuickTags.BASE_URL),
                )
            }
            item { Step("2  填 API Key") }
            item {
                OutlinedTextField(
                    value = key, onValueChange = { key = it.trim(); result = null },
                    label = { Text("API Key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).testTag(QuickTags.KEY),
                )
            }
            if (preset?.keyUrl?.isNotBlank() == true) item {
                TextButton(onClick = { runCatching { uri.openUri(preset.keyUrl) } }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("没有 Key？去 ${preset.label} 申请") }
            }
            item { Step("3  选模型") }
            item {
                Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { fetch() }, enabled = busy == null, modifier = Modifier.testTag(QuickTags.FETCH)) {
                        Text(if (models.isEmpty()) "获取模型列表" else "刷新列表")
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = model, onValueChange = { model = it.trim(); result = null },
                    label = { Text("模型") }, placeholder = { Text("从下面选，或直接输入模型名") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).testTag(QuickTags.MODEL_INPUT),
                )
            }
            val shown = models.filter { model.isBlank() || it.contains(model, ignoreCase = true) || it == model }.take(40)
                .ifEmpty { models.take(40) }
            shown.forEach { id ->
                item(key = "m:$id") {
                    Text(
                        (if (id == model) "● " else "○ ") + id,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { model = id; result = null }
                            .padding(horizontal = 28.dp, vertical = 8.dp).testTag(QuickTags.model(id)),
                    )
                }
            }
            item { Step("4  测试并保存") }
            item {
                Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { scope.launch { busy = "正在测试…"; result = actions.test(presetId, key, model, baseUrl); busy = null } },
                        enabled = busy == null, modifier = Modifier.testTag(QuickTags.TEST),
                    ) { Text("测试连接") }
                    Button(
                        onClick = {
                            scope.launch {
                                busy = "正在保存并重启 pi…"
                                result = when (val r = actions.save(presetId, key, model, models, baseUrl)) {
                                    is Outcome.Ok -> QuickCheck(true, r.value).also { key = "" }
                                    is Outcome.Err -> QuickCheck(false, r.message)
                                }
                                busy = null
                            }
                        },
                        enabled = busy == null && model.isNotBlank(), modifier = Modifier.testTag(QuickTags.SAVE),
                    ) { Text("保存并使用") }
                }
            }
            item {
                val text = busy ?: result?.let { (if (it.ok) "✓ " else "✗ ") + it.message }
                if (text != null) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            busy != null -> SettingsColors.faint
                            result?.ok == true -> Color(0xFF2E7D32)
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag(QuickTags.RESULT),
                    )
                }
            }
            if (onAdvanced != null) item {
                Column(Modifier.padding(top = 16.dp)) {
                    TextButton(onClick = onAdvanced, modifier = Modifier.padding(horizontal = 12.dp).testTag(QuickTags.ADVANCED)) {
                        Text("高级设置（更多服务商、账号登录、代理、运行环境）")
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = SettingsColors.faint, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
}
