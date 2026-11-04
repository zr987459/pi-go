package io.github.zr987459.pigo.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.zr987459.pigo.contract.Phase
import io.github.zr987459.pigo.contract.RuntimeStatus
import io.github.zr987459.pigo.contract.fake.FakeProviderConfigStore
import io.github.zr987459.pigo.feature.settings.ui.PlainBackdrop
import io.github.zr987459.pigo.core.ui.PiGoTheme
import io.github.zr987459.pigo.feature.settings.ui.LargeTitle

private val previewRuntime = RuntimeStatus(Phase.READY, ubuntu = "24.04.5", node = "v24.22.2", pi = "1.5.0", error = null)

@Composable
private fun SettingsSample() {
    SettingsScreen(
        providers = FakeProviderConfigStore(),
        runtimeStatus = previewRuntime,
        onRepairRuntime = {},
    )
}

@Preview(name = "设置 · 浅色", widthDp = 380, heightDp = 1400)
@Composable
private fun SettingsLightPreview() = PiGoTheme(darkTheme = false) { SettingsSample() }

@Preview(name = "设置 · 深色", widthDp = 380, heightDp = 1400)
@Composable
private fun SettingsDarkPreview() = PiGoTheme(darkTheme = true) { SettingsSample() }

@Composable
private fun GuideSample() {
    PlainBackdrop {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LargeTitle("后台运行", subtitle = "pi 在后台跑任务时，手机系统可能为了省电把它关掉。")
            BackgroundGuideContent()
        }
    }
}

@Preview(name = "后台运行引导 · 浅色", widthDp = 380, heightDp = 1200)
@Composable
private fun GuideLightPreview() = PiGoTheme(darkTheme = false) { GuideSample() }

@Preview(name = "后台运行引导 · 深色", widthDp = 380, heightDp = 1200)
@Composable
private fun GuideDarkPreview() = PiGoTheme(darkTheme = true) { GuideSample() }
