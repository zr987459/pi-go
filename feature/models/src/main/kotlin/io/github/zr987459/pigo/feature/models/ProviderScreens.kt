package io.github.zr987459.pigo.feature.models

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zr987459.pigo.contract.AuthKind
import io.github.zr987459.pigo.contract.AuthManager
import io.github.zr987459.pigo.contract.AuthState
import io.github.zr987459.pigo.contract.ModelCatalog
import io.github.zr987459.pigo.contract.OAuthStep
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.ProviderInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 服务商列表（M1）：pi 目录里的全部服务商 + 自定义端点，标出已配置 / 已登录。 */
@Composable
fun ProviderListScreen(
    catalog: ModelCatalog,
    auth: AuthManager,
    onOpen: (ProviderInfo) -> Unit,
    onAddCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var providers by remember { mutableStateOf<List<ProviderInfo>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    val states by auth.observe().collectAsState(initial = emptyMap())
    LaunchedEffect(catalog) { (catalog.providers() as? Outcome.Ok)?.let { providers = it.value } }
    val shown = remember(providers, states, query) { ProviderListLogic.sort(ProviderListLogic.filter(providers, query), states) }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("服务商与登录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
        OutlinedTextField(query, { query = it }, placeholder = { Text("搜索服务商") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = onAddCustom) { Text("+ 添加自定义端点（OpenAI / Anthropic / Gemini 兼容）") }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { p ->
                val st = states[p.id] ?: AuthState.None
                Column(Modifier.fillMaxWidth().clickable { onOpen(p) }.padding(vertical = 10.dp).testTag("providers.row:${p.id}")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(p.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(ProviderListLogic.stateText(st), style = MaterialTheme.typography.bodySmall,
                            color = if (st.configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    }
                    Text(ProviderListLogic.kindsText(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                HorizontalDivider()
            }
        }
    }
}

object ProviderListLogic {
    fun filter(ps: List<ProviderInfo>, q: String): List<ProviderInfo> {
        val k = q.trim().lowercase()
        return if (k.isEmpty()) ps else ps.filter { it.id.lowercase().contains(k) || it.displayName.lowercase().contains(k) }
    }

    /** 已配置的在前，其余保持目录顺序（国内常用在前）。 */
    fun sort(ps: List<ProviderInfo>, states: Map<String, AuthState>): List<ProviderInfo> =
        ps.withIndex().sortedWith(compareBy({ if (states[it.value.id]?.configured == true) 0 else 1 }, { it.index })).map { it.value }

    fun stateText(s: AuthState): String = when (s) {
        is AuthState.ApiKey -> "已配置" + (s.last4?.let { " ••••$it" } ?: "")
        is AuthState.OAuth -> "已登录" + (s.accountLabel?.let { "（$it）" } ?: "")
        is AuthState.CloudEnv -> if (s.configuredVars.isEmpty()) "未配置" else "已配置"
        is AuthState.Error -> "出错"
        AuthState.None -> "未配置"
    }

    fun kindsText(p: ProviderInfo): String = buildList {
        if (AuthKind.API_KEY in p.authKinds) add("API Key")
        if (AuthKind.CLOUD_ENV in p.authKinds) add("云平台凭据")
        if (AuthKind.OAUTH_DEVICE in p.authKinds || AuthKind.OAUTH_PKCE in p.authKinds) add("账号登录")
        if (p.reachableInChina == true) add("国内直连")
        p.note?.takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString(" · ")
}

/**
 * 单个服务商的配置页（M1、M3）：Key 与额外字段表单；支持 OAuth 的显示「用账号登录」，
 * 设备码类显示 user code 并打开验证页，浏览器授权类用 Custom Tab 打开授权页，回调自动完成或粘贴授权码。
 */
@Composable
fun ProviderConfigScreen(
    provider: ProviderInfo,
    auth: AuthManager,
    onChanged: () -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * 读已保存的非密文字段（区域、项目、API 版本等）用来回填表单；W5 接 `RealAuthManager.savedFields`。
     * 默认读不到：此时只有用户动过的可选字段才会按「留空 = 清除」提交，没看到的旧值不会被误清。
     */
    savedFields: suspend (String) -> Map<String, String> = { emptyMap() },
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val states by auth.observe().collectAsState(initial = emptyMap())
    val state = states[provider.id] ?: AuthState.None
    var key by remember { mutableStateOf("") }
    val extra = remember { mutableStateMapOf<String, String>() }
    /** 已回填或用户改过的非密文字段：保存时这些字段留空 = 清除。 */
    val shown = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(provider.id) {
        val saved = runCatching { savedFields(provider.id) }.getOrDefault(emptyMap())
        for (f in provider.fields) {
            if (f.secret) continue
            val v = saved[f.envVar] ?: continue
            if (extra[f.envVar].isNullOrEmpty()) extra[f.envVar] = v
            shown[f.envVar] = true
        }
    }
    var message by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf<OAuthStep?>(null) }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    var code by remember { mutableStateOf("") }
    val canKey = AuthKind.API_KEY in provider.authKinds || AuthKind.CLOUD_ENV in provider.authKinds
    val canOAuth = AuthKind.OAUTH_DEVICE in provider.authKinds || AuthKind.OAUTH_PKCE in provider.authKinds

    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(provider.displayName, style = MaterialTheme.typography.titleLarge)
        Text("当前：" + ProviderListLogic.stateText(state), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
        provider.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }

        if (canOAuth) {
            Spacer(Modifier.height(16.dp))
            Text("用账号登录", style = MaterialTheme.typography.titleMedium)
            Text("登录凭据由 pi 保存在应用私有目录的 auth.json（仅本应用可读，不参与备份与导出）。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            when (val s = step) {
                null, is OAuthStep.Done, is OAuthStep.Failed -> {
                    if (s is OAuthStep.Failed) Text(s.message, color = MaterialTheme.colorScheme.error)
                    if (s is OAuthStep.Done) Text("登录成功", color = MaterialTheme.colorScheme.primary)
                    Button(
                        modifier = Modifier.testTag("provider.login"),
                        onClick = {
                            loginJob?.cancel()
                            loginJob = scope.launch {
                                auth.login(provider.id).collect { st ->
                                    step = st
                                    when (st) {
                                        is OAuthStep.OpenUrl -> openUrl(ctx, st.url)
                                        is OAuthStep.Done -> onChanged()
                                        else -> Unit
                                    }
                                }
                            }
                        },
                    ) { Text(if (state is AuthState.OAuth) "重新登录" else "登录") }
                }
                is OAuthStep.Starting -> Row { CircularProgressIndicator(); Text("  正在准备登录…") }
                is OAuthStep.Progress -> Row { CircularProgressIndicator(); Text("  " + s.message) }
                is OAuthStep.DeviceCode -> {
                    Text("在打开的页面输入下面的代码：", modifier = Modifier.padding(top = 8.dp))
                    SelectionContainer {
                        Text(s.userCode, fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("provider.userCode"))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(s.userCode)) }) { Text("复制代码") }
                        Button(onClick = { openUrl(ctx, s.verificationUri) }) { Text("打开验证页") }
                    }
                    Text("授权完成后这里会自动继续", style = MaterialTheme.typography.bodySmall)
                }
                is OAuthStep.OpenUrl -> {
                    Text(s.instructions ?: "已在浏览器打开授权页，完成后会自动返回", modifier = Modifier.padding(top = 8.dp))
                    OutlinedButton(onClick = { openUrl(ctx, s.url) }) { Text("重新打开授权页") }
                }
                is OAuthStep.NeedCode -> {
                    Text(s.prompt, modifier = Modifier.padding(top = 8.dp))
                    OutlinedTextField(code, { code = it }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("provider.code"))
                    Button(onClick = {
                        scope.launch {
                            try {
                                auth.submitCode(provider.id, code); code = ""
                            } catch (e: IllegalArgumentException) {
                                message = e.message
                            }
                        }
                    }) { Text("提交") }
                }
                is OAuthStep.Select -> {
                    Text(s.prompt, modifier = Modifier.padding(top = 8.dp))
                    s.options.forEach { o ->
                        OutlinedButton(onClick = { scope.launch { auth.submitSelection(provider.id, o) } }) { Text(o) }
                    }
                }
            }
            if (step != null && step !is OAuthStep.Done && step !is OAuthStep.Failed) {
                TextButton(onClick = { loginJob?.cancel(); step = OAuthStep.Failed(io.github.zr987459.pigo.contract.ErrCode.CANCELLED, "已取消登录") }) { Text("取消登录") }
            }
        }

        if (canKey) {
            Spacer(Modifier.height(16.dp))
            Text(if (canOAuth) "或填写 API Key" else "API Key 与配置", style = MaterialTheme.typography.titleMedium)
            if (provider.envVar != null) {
                OutlinedTextField(
                    key, { key = it },
                    label = { Text("API Key（${provider.envVar}）") },
                    placeholder = { Text(if (state.configured) "留空 = 保持原 Key" else "粘贴 Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("provider.key"),
                )
            }
            provider.fields.forEach { f ->
                OutlinedTextField(
                    extra[f.envVar].orEmpty(), { extra[f.envVar] = it; shown[f.envVar] = true },
                    label = { Text(f.label + if (f.required) "" else "（可选）") },
                    placeholder = { f.hint?.let { Text(it) } },
                    singleLine = true,
                    visualTransformation = if (f.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val r = auth.setApiKey(provider.id, key.ifBlank { null }, ProviderFormLogic.fieldsToSave(provider.fields, extra, shown.keys))
                        message = when (r) { is Outcome.Ok -> { key = ""; onChanged(); "已保存，重启 pi 后生效" }; is Outcome.Err -> r.message }
                    }
                }, modifier = Modifier.testTag("provider.save")) { Text("保存") }
                if (state.configured) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            message = when (val r = auth.clear(provider.id)) { is Outcome.Ok -> { onChanged(); "已清除" }; is Outcome.Err -> r.message }
                        }
                    }) { Text(if (state is AuthState.OAuth) "退出登录" else "清除") }
                }
            }
        } else if (state is AuthState.OAuth) {
            OutlinedButton(onClick = {
                scope.launch { message = when (val r = auth.clear(provider.id)) { is Outcome.Ok -> { onChanged(); "已退出登录" }; is Outcome.Err -> r.message } }
            }) { Text("退出登录") }
        }
        message?.let { Text(it, modifier = Modifier.padding(top = 8.dp).testTag("provider.message")) }
    }
}

/** Custom Tab 打开授权页；没有浏览器时退回普通 VIEW。 */
internal fun openUrl(ctx: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(ctx, uri)
    } catch (_: ActivityNotFoundException) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    } catch (_: Exception) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
