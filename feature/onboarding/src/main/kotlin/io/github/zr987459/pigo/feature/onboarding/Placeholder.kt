package io.github.zr987459.pigo.feature.onboarding

import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import io.github.zr987459.pigo.contract.InstallProgress
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.core.ui.InfoCard
import io.github.zr987459.pigo.core.ui.PlaceholderScreen
import kotlinx.coroutines.launch

/** 首启安装向导占位页（mvp/runtime 替换）。 */
@Composable
fun OnboardingPlaceholder(runtime: RuntimeManager, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val status by runtime.status.collectAsState()
    var progress by remember { mutableStateOf<InstallProgress?>(null) }
    val scope = rememberCoroutineScope()
    PlaceholderScreen(
        title = "首启安装",
        description = "占位页：真实的安装向导由 mvp/runtime 实现。",
        modifier = modifier,
        testTag = "page:onboarding",
    ) {
        InfoCard("运行环境", "状态 ${status.phase} · Ubuntu ${status.ubuntu ?: "-"} · Node ${status.node ?: "-"} · pi ${status.pi ?: "-"}")
        progress?.let {
            LinearProgressIndicator(progress = { it.percent / 100f }, modifier = Modifier.fillMaxWidth())
            Text(it.message)
        }
        Button(onClick = { scope.launch { runtime.install().collect { p -> progress = p } } }) { Text("模拟安装") }
        Button(onClick = onDone) { Text("进入聊天") }
    }
}
