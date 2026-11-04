package io.github.zr987459.pigo.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.AppSettingsStore
import io.github.zr987459.pigo.contract.NetworkSettings
import kotlinx.coroutines.launch

/**
 * 设置 › 模型页末尾的三行（PR #21 审查第 1 轮 Important 2、Nit 3）：
 * - 「网络代理」：查看 / 修改 / 关闭（原「代理与费用显示」页已删除；代理仍以 `HTTP(S)_PROXY` 注入 pi，所以必须能改、能关）；
 * - 「修复运行环境」：原设置首页「运行环境与高级」里的修复；
 * - 「关于」：DESIGN-r3 §8.5 导航「设置 › 模型 → 关于」。
 * 导出会话（pi 功能）入口在会话菜单 ⋯「导出（HTML / JSONL）」与 ⌘，不在这里。
 */
@Composable
fun ModelsPageExtras(
    settings: AppSettingsStore,
    onRepairRuntime: (suspend () -> String)?,
    aboutText: String,
    /**
     * 「立即重启 pi」（PR #21 审查第 2 轮 Nit 4）：代理以环境变量注入 pi，保存后要重启 pi 才生效。
     * 装配层传 `engine.restart(…)`，返回给用户看的结果；为 null 时只提示用 ⌘ ›「停止引擎」。
     */
    onRestartEngine: (suspend () -> String)? = null,
) {
    val scope = rememberCoroutineScope()
    val net by settings.network.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var restartOffered by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("其他", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        ExtraRow("网络代理", ProxyLogic.summary(net), "settings:proxy") { editing = true }
        if (onRepairRuntime != null) {
            ExtraRow("修复运行环境", "重新校验并修复 pi 运行环境", "settings:repairRuntime") {
                message = "正在修复运行环境…"
                scope.launch { message = runCatching { onRepairRuntime() }.getOrElse { "修复失败：${it.message}" } }
            }
        }
        ExtraRow("关于", "pi-go 版本、许可", "settings:about") { about = true }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag("settings:extraMessage")) }
        if (restartOffered && onRestartEngine != null) {
            ExtraRow("立即重启 pi", "让代理设置生效；正在生成的回复会中断", "settings:restartPi") {
                restartOffered = false
                message = "正在重启 pi…"
                scope.launch { message = runCatching { onRestartEngine() }.getOrElse { "重启失败：${it.message}" } }
            }
        }
    }

    if (editing) {
        var proxy by remember { mutableStateOf(net.proxyUrl.orEmpty()) }
        var noProxy by remember { mutableStateOf(net.noProxy) }
        var error by remember { mutableStateOf<String?>(null) }
        fun save(v: NetworkSettings) {
            scope.launch {
                try {
                    settings.setNetwork(v)
                    message = ProxyLogic.savedMessage(v, canRestart = onRestartEngine != null)
                    restartOffered = onRestartEngine != null
                    editing = false
                } catch (e: IllegalArgumentException) {
                    error = e.message ?: "代理地址无效"
                }
            }
        }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("网络代理") },
            text = {
                Column {
                    Text("国外服务商在国内通常需要代理。只支持 http/https，pi 和登录都会走它，重启 pi 后生效（保存后可在本页「立即重启 pi」）。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(proxy, { proxy = it; error = null }, label = { Text("代理地址") }, placeholder = { Text("http://127.0.0.1:7890") }, singleLine = true,
                        isError = error != null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("proxy:url"))
                    OutlinedTextField(noProxy, { noProxy = it }, label = { Text("不走代理的地址") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("proxy:noProxy"))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("proxy:error")) }
                }
            },
            confirmButton = { TextButton(onClick = { save(ProxyLogic.edited(proxy, noProxy)) }, modifier = Modifier.testTag("proxy:save")) { Text("保存") } },
            dismissButton = {
                Column {
                    if (net.proxyUrl != null) TextButton(onClick = { save(ProxyLogic.off(net)) }, modifier = Modifier.testTag("proxy:off")) { Text("关闭代理") }
                    TextButton(onClick = { editing = false }, modifier = Modifier.testTag("proxy:cancel")) { Text("取消") }
                }
            },
        )
    }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("关于") },
            text = { Text(aboutText, modifier = Modifier.testTag("settings:aboutText")) },
            confirmButton = { TextButton(onClick = { about = false }) { Text("好") } },
        )
    }
}

@Composable
private fun ExtraRow(title: String, value: String, tag: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp).testTag(tag)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.testTag("$tag:value"))
    }
}

/** 代理行的纯逻辑（JVM 单测 ProxyLogicTest）。 */
object ProxyLogic {
    const val DEFAULT_NO_PROXY = "localhost,127.0.0.1"

    fun summary(n: NetworkSettings): String = n.proxyUrl?.let { "已启用：$it" } ?: "未使用"

    /** 对话框里的输入 → 设置：代理地址留空即关闭。 */
    fun edited(proxy: String, noProxy: String): NetworkSettings =
        NetworkSettings(proxy.trim().ifEmpty { null }, noProxy.trim().ifEmpty { DEFAULT_NO_PROXY })

    /** 「关闭代理」：只清代理地址，保留例外列表。 */
    fun off(n: NetworkSettings): NetworkSettings = n.copy(proxyUrl = null)

    /** 保存后的提示：有重启入口时指向本页「立即重启 pi」，没有时指向已有的 ⌘ ›「停止引擎」。 */
    fun savedMessage(n: NetworkSettings, canRestart: Boolean = true): String {
        val head = if (n.proxyUrl == null) "已关闭代理" else "代理已保存"
        return head + if (canRestart) "，点下方「立即重启 pi」生效" else "，在 ⌘ ›「停止引擎」后重新进入生效"
    }

    /** 「立即重启 pi」的结果提示。 */
    fun restartMessage(ok: Boolean, error: String? = null): String = if (ok) "pi 已重启，代理设置已生效" else "重启失败：${error.orEmpty()}"
}
