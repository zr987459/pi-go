package io.github.zr987459.pigo.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.AppSettingsStore
import io.github.zr987459.pigo.contract.CustomModel
import io.github.zr987459.pigo.contract.CustomProvider
import io.github.zr987459.pigo.contract.CustomProviderStore
import io.github.zr987459.pigo.contract.ModelCost
import io.github.zr987459.pigo.contract.ModelRef
import io.github.zr987459.pigo.contract.NetworkSettings
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.ProviderApi
import kotlinx.coroutines.launch

/** 自定义端点里一个模型的可编辑状态。 */
class EditableModel(m: CustomModel) {
    var id by mutableStateOf(m.id)
    var name by mutableStateOf(m.name.orEmpty())
    var contextWindow by mutableStateOf(m.contextWindow?.toString().orEmpty())
    var maxTokens by mutableStateOf(m.maxTokens?.toString().orEmpty())
    var reasoning by mutableStateOf(m.reasoning)
    var images by mutableStateOf("image" in m.input)
    var priceIn by mutableStateOf(m.cost?.input?.toString().orEmpty())
    var priceOut by mutableStateOf(m.cost?.output?.toString().orEmpty())
    private val cacheRead = m.cost?.cacheRead ?: 0.0
    private val cacheWrite = m.cost?.cacheWrite ?: 0.0

    fun toModel(): CustomModel = CustomModel(
        id = id.trim(),
        name = name.trim().ifEmpty { null },
        contextWindow = contextWindow.trim().toLongOrNull(),
        maxTokens = maxTokens.trim().toLongOrNull(),
        reasoning = reasoning,
        input = if (images) setOf("text", "image") else setOf("text"),
        cost = if (priceIn.isBlank() && priceOut.isBlank()) null
        else ModelCost(priceIn.toDoubleOrNull() ?: 0.0, priceOut.toDoubleOrNull() ?: 0.0, cacheRead, cacheWrite),
    )
}

/** 「获取模型列表」结果合并进已编辑的模型：已有的只补空字段，新的追加。 */
object CustomMerge {
    fun merge(current: List<CustomModel>, discovered: List<CustomModel>): List<CustomModel> {
        val byId = current.associateBy { it.id }
        val merged = current.map { c ->
            val d = discovered.firstOrNull { it.id == c.id } ?: return@map c
            c.copy(
                name = c.name ?: d.name,
                contextWindow = c.contextWindow ?: d.contextWindow,
                maxTokens = c.maxTokens ?: d.maxTokens,
                reasoning = c.reasoning || d.reasoning,
                input = c.input + d.input,
            )
        }
        return merged + discovered.filter { it.id !in byId }
    }
}

/**
 * 自定义端点编辑页（M6、M7）：四种协议、多个模型、每个模型的上下文、最大输出、推理、看图、单价；
 * 「获取模型列表」回填上游给的上下文与推理能力；「测试连接」。
 */
@Composable
fun CustomProviderScreen(
    store: CustomProviderStore,
    existing: CustomProvider?,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf(existing?.id.orEmpty()) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var api by remember { mutableStateOf(existing?.api ?: ProviderApi.OPENAI_COMPLETIONS) }
    var key by remember { mutableStateOf("") }
    val models = remember { mutableStateListOf<EditableModel>().apply { addAll(existing?.models.orEmpty().map(::EditableModel)) } }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(if (existing == null) "添加自定义端点" else "编辑 ${existing.id}", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(id, { id = it }, label = { Text("英文 id（如 bailian）") }, singleLine = true, enabled = existing == null,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("接口地址") }, placeholder = { Text("https://…/v1") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Text("协议", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        ProviderApi.entries.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = api == a, onClick = { api = a })
                Text(apiLabel(a))
            }
        }
        OutlinedTextField(key, { key = it }, label = { Text("API Key") },
            placeholder = { Text(if (existing != null) "留空 = 保持原 Key" else "粘贴 Key") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val r = store.discover(baseUrl, api, key.ifBlank { null }, existing?.id ?: id.ifBlank { null })
                    when (r) {
                        is Outcome.Ok -> {
                            val merged = CustomMerge.merge(models.map { it.toModel() }, r.value)
                            models.clear(); models.addAll(merged.map(::EditableModel))
                            message = "获取到 ${r.value.size} 个模型"
                        }
                        is Outcome.Err -> message = r.message
                    }
                    busy = false
                }
            }) { Text("获取模型列表") }
            OutlinedButton(onClick = { models.add(EditableModel(CustomModel(""))) }) { Text("+ 手动添加模型") }
        }
        models.forEachIndexed { i, m ->
            HorizontalDivider()
            OutlinedTextField(m.id, { m.id = it }, label = { Text("模型 id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(m.name, { m.name = it }, label = { Text("显示名（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(m.contextWindow, { m.contextWindow = it }, "上下文 token", Modifier.weight(1f))
                NumberField(m.maxTokens, { m.maxTokens = it }, "最大输出", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(m.priceIn, { m.priceIn = it }, "输入 $/百万", Modifier.weight(1f))
                NumberField(m.priceOut, { m.priceOut = it }, "输出 $/百万", Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(m.reasoning, { m.reasoning = it }); Text("推理")
                Checkbox(m.images, { m.images = it }); Text("看图")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch {
                        val r = store.test(ModelRef(existing?.id ?: id, m.id))
                        message = when (r) { is Outcome.Ok -> r.value.message; is Outcome.Err -> r.message }
                    }
                }) { Text("测试") }
                TextButton(onClick = { models.removeAt(i) }) { Text("删除") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    val p = CustomProvider(id.trim(), baseUrl.trim(), api, models.map { it.toModel() }.filter { it.id.isNotBlank() }, existing?.headers.orEmpty())
                    message = when (val r = store.upsert(p, key.ifBlank { null })) { is Outcome.Ok -> { onSaved(); "已保存，重启 pi 后生效" }; is Outcome.Err -> r.message }
                }
            }, modifier = Modifier.testTag("custom.save")) { Text("保存") }
            if (existing != null) {
                OutlinedButton(onClick = {
                    scope.launch { message = when (val r = store.remove(existing.id)) { is Outcome.Ok -> { onSaved(); "已删除" }; is Outcome.Err -> r.message } }
                }) { Text("删除端点") }
            }
        }
        message?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun NumberField(v: String, on: (String) -> Unit, label: String, modifier: Modifier) =
    OutlinedTextField(v, on, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier)

internal fun apiLabel(a: ProviderApi) = when (a) {
    ProviderApi.OPENAI_COMPLETIONS -> "OpenAI Chat Completions（最常见）"
    ProviderApi.OPENAI_RESPONSES -> "OpenAI Responses"
    ProviderApi.ANTHROPIC_MESSAGES -> "Anthropic Messages"
    ProviderApi.GOOGLE_GENERATIVE_AI -> "Google Gemini"
}

/**
 * 代理（M9）。费用显示（汇率）已按 DESIGN-r3 X2 删除，这里只剩代理。
 *
 * 交接给 r3/app：本页目前仍是**唯一**的代理入口。等 `PiSettings` 的 `httpProxy` 在 ⌘ › pi 设置有入口、
 * r3/app 去掉 `WiredPages` 的 network 路由后，由 r3/app 删除本函数（DESIGN-r3 §8.2 第 2、4 条）。
 */
@Deprecated("r3: 代理迁到 PiSettings.httpProxy、r3/app 断开 network 路由后，由 r3/app 删除")
@Composable
fun NetworkAndCostScreen(settings: AppSettingsStore, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val net by settings.network.collectAsState()
    var proxy by remember(net) { mutableStateOf(net.proxyUrl.orEmpty()) }
    var noProxy by remember(net) { mutableStateOf(net.noProxy) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("网络代理", style = MaterialTheme.typography.titleLarge)
        Text("国外服务商在国内通常需要代理。填本机或局域网代理地址（只支持 http/https），pi 和登录都会走它。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        OutlinedTextField(proxy, { proxy = it }, label = { Text("代理地址") }, placeholder = { Text("http://127.0.0.1:7890") },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("settings.proxy"))
        OutlinedTextField(noProxy, { noProxy = it }, label = { Text("不走代理的地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            scope.launch {
                message = try {
                    settings.setNetwork(NetworkSettings(proxy.trim().ifEmpty { null }, noProxy.trim().ifEmpty { "localhost,127.0.0.1" }))
                    if (proxy.isBlank()) "已关闭代理，重启 pi 后生效" else "已保存，重启 pi 后生效"
                } catch (e: IllegalArgumentException) {
                    e.message
                }
            }
        }, modifier = Modifier.padding(top = 8.dp)) { Text("保存代理") }
        message?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
    }
}
