package io.github.zr987459.pigo.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.zr987459.pigo.contract.InstallProgress
import io.github.zr987459.pigo.contract.InstallStep
import io.github.zr987459.pigo.contract.Phase
import io.github.zr987459.pigo.contract.RuntimeStatus
import io.github.zr987459.pigo.core.ui.PiGoTheme

private val notInstalled = RuntimeStatus(Phase.NOT_INSTALLED, null, null, null, null)
private val ready = RuntimeStatus(Phase.READY, "24.04.5", "v24.22.2", "1.5.0", null)
private val preflightOk = Preflight.evaluate(listOf("arm64-v8a"), 12L * 1024 * 1024 * 1024, networkOk = true)

@Composable
private fun Sample(ui: OnboardingViewModel.UiState, status: RuntimeStatus) =
    OnboardingContent(ui, status, preflightOk, onStart = {}, onAbort = {}, onToggleLog = {}, onDone = {})

private val installing = OnboardingViewModel.UiState(
    mode = OnboardingViewModel.Mode.RUNNING,
    progress = InstallProgress(InstallStep.ROOTFS, 64, bytesPerSec = 3_400_000, message = "正在下载 Ubuntu 24.04 根文件系统"),
    log = listOf("测速：mirrors.tuna 38ms", "下载 ubuntu-base-24.04.5"),
)

@Preview(name = "安装引导 · 确认 · 浅色", widthDp = 380, heightDp = 760)
@Composable
private fun ConfirmLight() = PiGoTheme(darkTheme = false) { Sample(OnboardingViewModel.UiState(), notInstalled) }

@Preview(name = "安装引导 · 确认 · 深色", widthDp = 380, heightDp = 760)
@Composable
private fun ConfirmDark() = PiGoTheme(darkTheme = true) { Sample(OnboardingViewModel.UiState(), notInstalled) }

@Preview(name = "安装引导 · 安装中 · 浅色", widthDp = 380, heightDp = 760)
@Composable
private fun InstallingLight() = PiGoTheme(darkTheme = false) { Sample(installing, RuntimeStatus(Phase.INSTALLING, null, null, null, null)) }

@Preview(name = "安装引导 · 安装中 · 深色", widthDp = 380, heightDp = 760)
@Composable
private fun InstallingDark() = PiGoTheme(darkTheme = true) { Sample(installing, RuntimeStatus(Phase.INSTALLING, null, null, null, null)) }

@Preview(name = "安装引导 · 失败 · 深色", widthDp = 380, heightDp = 760)
@Composable
private fun FailedDark() = PiGoTheme(darkTheme = true) {
    Sample(OnboardingViewModel.UiState(mode = OnboardingViewModel.Mode.FAILED, error = "下载校验失败（SHA256 不一致）", log = listOf("x")), notInstalled)
}

@Preview(name = "安装引导 · 完成 · 浅色", widthDp = 380, heightDp = 760)
@Composable
private fun DoneLight() = PiGoTheme(darkTheme = false) { Sample(OnboardingViewModel.UiState(), ready) }
