package io.github.zr987459.pigo.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
private fun ThemeSampler() {
    AppBackdrop {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChromeIconButton(AppIcons.menu, "菜单", {})
                ChromePill({}) {
                    Column {
                        Text("新对话", style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            StatusDot(StatusTone.Idle, size = 6.dp)
                            Text("已停止", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                ChromeIconButton(AppIcons.newChat, "新建对话", {})
            }
            Text("下午好", style = AppType.Greeting)
            PrimaryPillButton("启动引擎", {})
            TonalPillButton("取消", {})
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(StatusTone.Ok, "运行中")
                StatusPill(StatusTone.Busy, "思考中")
                StatusPill(StatusTone.Error, "出错")
            }
            InfoCard("运行环境", "Ubuntu 24.04.5 · Node v24.22.2 · pi 1.5.0")
            SectionHeader("模型与 Key")
            Text("let x = 42 // JetBrains Mono", style = MonoStyle)
        }
    }
}

@Preview(name = "主题 · 浅色", widthDp = 380, heightDp = 640)
@Composable
private fun ThemeLightPreview() = PiGoTheme(darkTheme = false) { ThemeSampler() }

@Preview(name = "主题 · 深色", widthDp = 380, heightDp = 640)
@Composable
private fun ThemeDarkPreview() = PiGoTheme(darkTheme = true) { ThemeSampler() }
