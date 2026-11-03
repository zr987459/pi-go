package io.github.zr987459.pigo.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.zr987459.pigo.contract.RuntimeManager

/**
 * scaffold 固定的入口签名（`:app` 的导航按此名字调用，导航文件不归 mvp/runtime）。
 * 占位内容已替换为真实的安装向导 [OnboardingScreen]；改名由 mvp/integration 统一处理。
 */
@Composable
fun OnboardingPlaceholder(runtime: RuntimeManager, onDone: () -> Unit, modifier: Modifier = Modifier) {
    OnboardingScreen(runtime = runtime, onDone = onDone, modifier = modifier)
}
