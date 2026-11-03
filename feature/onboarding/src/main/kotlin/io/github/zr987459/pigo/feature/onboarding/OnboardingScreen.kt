package io.github.zr987459.pigo.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zr987459.pigo.contract.InstallStep
import io.github.zr987459.pigo.contract.Phase
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.RuntimeStatus
import io.github.zr987459.pigo.core.ui.InfoCard

/** 首启安装向导（§4.3、§5.1 F1–F6、§5.7 N4）。 */
@Composable
fun OnboardingScreen(runtime: RuntimeManager, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val vm: OnboardingViewModel = viewModel { OnboardingViewModel(runtime) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val status by runtime.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var preflight by remember { mutableStateOf<Preflight?>(null) }
    LaunchedEffect(ui.mode) {
        if (ui.mode != OnboardingViewModel.Mode.RUNNING) preflight = Preflight.read(context)
    }

    BackHandlerCompat(enabled = ui.mode == OnboardingViewModel.Mode.RUNNING) { vm.onBack() }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("page:onboarding"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("准备运行环境", style = MaterialTheme.typography.headlineSmall)

        if (status.phase == Phase.READY && ui.mode != OnboardingViewModel.Mode.RUNNING) {
            ReadyCard(status)
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().testTag("onboarding:enter")) { Text("进入聊天") }
            return@Column
        }

        Text(
            "pi-go 会在手机里安装一个 Ubuntu 小环境，再装上 Node.js 和 pi。全程使用国内镜像，下载的每个文件都会做 SHA256 校验。需要至少 3 GB 可用空间。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        preflight?.let { PreflightCard(it) }

        when (ui.mode) {
            OnboardingViewModel.Mode.RUNNING -> ProgressBlock(ui)
            OnboardingViewModel.Mode.FAILED -> ui.error?.let { ErrorCard(it) }
            OnboardingViewModel.Mode.ABORTED -> InfoCard("安装已中止", "已下载并校验过的文件会保留，继续安装时从中断的步骤接着做。")
            else -> if (status.phase == Phase.BROKEN) InfoCard("安装尚未完成", status.error ?: "可以继续安装")
        }

        val canStart = preflight?.canStart == true
        when (ui.mode) {
            OnboardingViewModel.Mode.RUNNING -> OutlinedButton(onClick = { vm.onBack() }, modifier = Modifier.fillMaxWidth()) { Text("中止安装") }
            OnboardingViewModel.Mode.DONE -> Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("进入聊天") }
            else -> {
                val label = when {
                    ui.mode == OnboardingViewModel.Mode.FAILED -> "重试（换镜像）"
                    ui.mode == OnboardingViewModel.Mode.ABORTED || status.phase == Phase.BROKEN -> "继续安装"
                    else -> "开始安装"
                }
                Button(onClick = { vm.start() }, enabled = canStart, modifier = Modifier.fillMaxWidth().testTag("onboarding:start")) { Text(label) }
                if (!canStart) preflight?.blockingReason?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (ui.log.isNotEmpty()) {
            TextButton(onClick = { vm.toggleLog() }) { Text(if (ui.showLog) "收起日志" else "查看日志") }
            if (ui.showLog) LogCard(ui.log)
        }
    }

    if (ui.confirmingAbort) {
        AlertDialog(
            onDismissRequest = { vm.dismissAbort() },
            title = { Text("中止安装？") },
            text = { Text("安装正在进行。中止后已完成的步骤会保留，下次可以继续安装。") },
            confirmButton = { TextButton(onClick = { vm.confirmAbort() }) { Text("中止") } },
            dismissButton = { TextButton(onClick = { vm.dismissAbort() }) { Text("继续安装") } },
        )
    }
}

@Composable
private fun PreflightCard(p: Preflight) {
    Card(Modifier.fillMaxWidth().testTag("onboarding:preflight")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("设备检查", style = MaterialTheme.typography.titleSmall)
            p.checks.forEach { c ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (c.ok) "✓" else "✗", color = if (c.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Text("${c.label}：${c.detail}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ProgressBlock(ui: OnboardingViewModel.UiState) {
    val p = ui.progress
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(p?.let { "${stepLabel(it.step)} · ${it.percent}%" } ?: "正在准备…", style = MaterialTheme.typography.titleSmall)
        LinearProgressIndicator(progress = { (p?.percent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
        p?.let {
            Text(it.message, style = MaterialTheme.typography.bodySmall)
            it.bytesPerSec?.let { bps -> Text("速度 ${formatSpeed(bps)}", style = MaterialTheme.typography.bodySmall) }
        }
        Text("可以切到后台或锁屏，安装会继续，通知栏会显示进度。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorCard(msg: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("安装没有完成", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Text(msg, style = MaterialTheme.typography.bodySmall)
            Text("点「重试」会从失败的步骤继续，已校验通过的文件不会重新下载。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ReadyCard(s: RuntimeStatus) {
    InfoCard(
        "运行环境已就绪",
        "Ubuntu ${s.ubuntu ?: "-"} · Node ${s.node ?: "-"} · pi ${s.pi ?: "-"}" + (s.error?.let { "\n$it" } ?: ""),
        modifier = Modifier.testTag("onboarding:ready"),
    )
}

@Composable
private fun LogCard(lines: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Text(
            lines.takeLast(80).joinToString("\n"),
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

internal fun stepLabel(step: InstallStep): String = when (step) {
    InstallStep.PREFLIGHT -> "检查设备"
    InstallStep.PROBE -> "测试镜像"
    InstallStep.ROOTFS -> "安装 Ubuntu"
    InstallStep.NODE -> "安装 Node.js"
    InstallStep.FIXUPS -> "配置容器"
    InstallStep.APT -> "安装基础工具"
    InstallStep.PI -> "安装 pi"
    InstallStep.VERIFY -> "验证"
}

internal fun formatSpeed(bps: Long): String = when {
    bps >= 1024 * 1024 -> "%.1f MB/s".format(bps / 1024.0 / 1024.0)
    bps >= 1024 -> "${bps / 1024} KB/s"
    else -> "$bps B/s"
}
