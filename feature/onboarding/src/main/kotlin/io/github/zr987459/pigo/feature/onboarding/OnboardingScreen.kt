package io.github.zr987459.pigo.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zr987459.pigo.contract.InstallProgress
import io.github.zr987459.pigo.contract.InstallStep
import io.github.zr987459.pigo.contract.Phase
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.RuntimeStatus
import io.github.zr987459.pigo.core.ui.AppIcon
import io.github.zr987459.pigo.core.ui.AppIcons
import io.github.zr987459.pigo.core.ui.AppMotion
import io.github.zr987459.pigo.core.ui.AppType
import io.github.zr987459.pigo.core.ui.DshTheme

/**
 * 首启安装向导（§4.3、§5.1 F1–F6、§5.7 N4）。界面照 dsh-Go 的安装引导：
 * 未开始 = `local_install.dart` 的确认页（方圆图标头 + 事实行 + 底部「开始安装」）；
 * 安装中 / 失败 / 完成 = `runtime_setup_page.dart` 的进度页（184dp 环形进度 + 百分比、标题、说明、元信息行、底部按钮）。
 */
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

    OnboardingContent(
        ui = ui,
        status = status,
        preflight = preflight,
        onStart = { vm.start() },
        onAbort = { vm.onBack() },
        onToggleLog = { vm.toggleLog() },
        onDone = onDone,
        modifier = modifier,
    )

    if (ui.confirmingAbort) {
        AlertDialog(
            onDismissRequest = { vm.dismissAbort() },
            title = { Text("中止安装？") },
            text = { Text("安装正在进行。中止后已完成的步骤会保留，下次可以继续安装。") },
            confirmButton = { TextButton(onClick = { vm.confirmAbort() }) { Text("中止", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { vm.dismissAbort() }) { Text("继续安装") } },
        )
    }
}

/** 向导当前显示哪一屏（由 ViewModel 状态和运行环境状态推出，不改状态机）。 */
internal enum class SetupView { CONFIRM, INSTALLING, FAILED, DONE }

internal fun setupViewOf(mode: OnboardingViewModel.Mode, phase: Phase): SetupView = when {
    mode == OnboardingViewModel.Mode.RUNNING -> SetupView.INSTALLING
    mode == OnboardingViewModel.Mode.DONE -> SetupView.DONE
    phase == Phase.READY -> SetupView.DONE
    mode == OnboardingViewModel.Mode.FAILED -> SetupView.FAILED
    else -> SetupView.CONFIRM
}

/** 整体进度 0..1：已完成的步骤 + 当前步骤的百分比，按 [InstallStep] 的顺序均分。 */
internal fun overallFraction(p: InstallProgress?): Float? {
    if (p == null) return null
    val steps = InstallStep.entries.size
    val done = p.step.ordinal + p.percent.coerceIn(0, 100) / 100f
    return (done / steps).coerceIn(0f, 1f)
}

/** 无状态的向导界面，[OnboardingScreen] 与预览共用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnboardingContent(
    ui: OnboardingViewModel.UiState,
    status: RuntimeStatus,
    preflight: Preflight?,
    onStart: () -> Unit,
    onAbort: () -> Unit,
    onToggleLog: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = setupViewOf(ui.mode, status.phase)
    val step = when (view) {
        SetupView.CONFIRM -> 1
        SetupView.INSTALLING, SetupView.FAILED -> 2
        SetupView.DONE -> 3
    }
    OnboardingScaffold(step = step, steps = 3, modifier = modifier.testTag("page:onboarding")) {
        if (view == SetupView.CONFIRM) {
            ConfirmView(ui, status, preflight, onStart)
        } else {
            ProgressView(view, ui, status, onStart, onAbort, onToggleLog, onDone)
        }
    }

    if (ui.showLog) {
        ModalBottomSheet(onDismissRequest = onToggleLog) {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
                Text("安装日志", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .background(DshTheme.colors.codeWell, MaterialTheme.shapes.medium)
                        .padding(12.dp)
                        .testTag("onboarding:log"),
                ) {
                    items(ui.log.takeLast(200)) { line ->
                        Text(line, style = AppType.MonoLabel, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/** local_install.dart `_Confirm`：Spacer → 头部 → 28dp → 事实行 → Spacer(2) → 24dp → 主按钮。 */
@Composable
private fun ColumnScope.ConfirmView(
    ui: OnboardingViewModel.UiState,
    status: RuntimeStatus,
    preflight: Preflight?,
    onStart: () -> Unit,
) {
    val colors = DshTheme.colors
    val resume = ui.mode == OnboardingViewModel.Mode.ABORTED || status.phase == Phase.BROKEN
    Spacer(Modifier.weight(1f))
    OnboardingHeader(
        icon = AppIcons.thisPhone,
        title = if (resume) "继续安装运行环境" else "准备运行环境",
        message = "pi-go 会在手机里安装一个 Ubuntu 小环境，再装上 Node.js 和 pi。全程使用国内镜像，下载的每个文件都会做 SHA256 校验。",
    )
    Spacer(Modifier.height(28.dp))
    when {
        ui.mode == OnboardingViewModel.Mode.ABORTED ->
            OnboardingFact(AppIcons.paused, "安装已中止。已下载并校验过的文件会保留，继续安装时从中断的步骤接着做。", colors.warning)
        status.phase == Phase.BROKEN ->
            OnboardingFact(AppIcons.attention, "安装尚未完成：${status.error ?: "可以继续安装"}", colors.warning)
    }
    Column(Modifier.testTag("onboarding:preflight")) {
        if (preflight == null) {
            OnboardingFact(AppIcons.loading, "正在检查设备…")
        } else {
            preflight.checks.forEach { c ->
                OnboardingFact(
                    if (c.ok) AppIcons.success else AppIcons.error,
                    "${c.label}：${c.detail}",
                    if (c.ok) null else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    OnboardingFact(AppIcons.artifacts, "需要至少 3 GB 可用空间")
    OnboardingFact(AppIcons.localOnly, "运行环境只装在这台手机上")
    Spacer(Modifier.weight(2f))
    Spacer(Modifier.height(24.dp))
    val canStart = preflight?.canStart == true
    OnboardingFilledButton(
        text = if (resume) "继续安装" else "开始安装",
        onClick = onStart,
        enabled = canStart,
        modifier = Modifier.fillMaxWidth().testTag("onboarding:start"),
    )
    if (!canStart) {
        preflight?.blockingReason?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

/** runtime_setup_page.dart `_progress`：Spacer(3) → 环形进度 → 36dp → 标题 → 6dp → 说明 → 8dp → 元信息 → Spacer(2) → 按钮区。 */
@Composable
private fun ColumnScope.ProgressView(
    view: SetupView,
    ui: OnboardingViewModel.UiState,
    status: RuntimeStatus,
    onStart: () -> Unit,
    onAbort: () -> Unit,
    onToggleLog: () -> Unit,
    onDone: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = DshTheme.colors
    val p = ui.progress
    val fraction = when (view) {
        SetupView.DONE -> 1f
        SetupView.INSTALLING -> overallFraction(p)
        else -> 0f
    }
    val title = when (view) {
        SetupView.DONE -> "运行环境已就绪"
        SetupView.FAILED -> "安装没有完成"
        else -> "正在准备运行环境"
    }
    val body = when (view) {
        SetupView.DONE -> "Ubuntu ${status.ubuntu ?: "-"} · Node ${status.node ?: "-"} · pi ${status.pi ?: "-"}" +
            (status.error?.let { "\n$it" } ?: "")
        SetupView.FAILED -> (ui.error ?: status.error ?: "安装失败") + "\n点「重试」会从失败的步骤继续，已校验通过的文件不会重新下载。"
        else -> p?.message?.takeIf { it.isNotBlank() } ?: "正在准备…"
    }
    val meta = if (view == SetupView.INSTALLING && p != null) {
        "${stepLabel(p.step)} · ${p.percent}%" + (p.bytesPerSec?.let { " · ${formatSpeed(it)}" } ?: "")
    } else {
        null
    }

    Spacer(Modifier.weight(3f))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ProgressHalo(
            value = fraction,
            color = if (view == SetupView.FAILED) colors.danger else scheme.primary,
            modifier = if (view == SetupView.DONE) Modifier.testTag("onboarding:ready") else Modifier,
        ) {
            AnimatedContent(
                targetState = view,
                transitionSpec = {
                    (fadeIn(tween(AppMotion.Emphasized, easing = AppMotion.Enter)) + scaleIn(tween(AppMotion.Emphasized, easing = AppMotion.Enter), initialScale = 0.85f))
                        .togetherWith(fadeOut(tween(AppMotion.Quick)))
                },
                label = "haloCenter",
            ) { v ->
                when (v) {
                    SetupView.DONE -> AppIcon(AppIcons.done, null, size = 48.dp, tint = scheme.primary)
                    SetupView.FAILED -> AppIcon(AppIcons.attention, null, size = 44.dp, tint = colors.danger)
                    else -> SetupPercent(fraction ?: 0f)
                }
            }
        }
    }
    Spacer(Modifier.height(36.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, color = scheme.onSurface, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = scheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().heightIn(min = 20.dp), contentAlignment = Alignment.Center) {
        if (meta != null) Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.textFaint, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.weight(2f))
    when (view) {
        SetupView.FAILED -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OnboardingOutlinedButton("查看日志", onToggleLog, Modifier.weight(1f))
            OnboardingFilledButton("重试（换镜像）", onStart, Modifier.weight(1f).testTag("onboarding:start"))
        }
        SetupView.DONE -> OnboardingFilledButton("进入聊天", onDone, Modifier.fillMaxWidth().testTag("onboarding:enter"))
        else -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                AppIcon(AppIcons.notifications, null, size = 16.dp, tint = colors.textFaint)
                Spacer(Modifier.width(6.dp))
                Text(
                    "可以切到后台或锁屏，安装会继续，通知栏会显示进度。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textFaint,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(12.dp))
            OnboardingOutlinedButton("中止安装", onAbort, Modifier.fillMaxWidth().testTag("onboarding:abort"))
        }
    }
    Spacer(Modifier.height(8.dp))
    if (ui.log.isNotEmpty() && view != SetupView.FAILED) {
        TextButton(
            onClick = onToggleLog,
            colors = TextButtonDefaults.textButtonColors(contentColor = scheme.onSurfaceVariant),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text("查看日志") }
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
