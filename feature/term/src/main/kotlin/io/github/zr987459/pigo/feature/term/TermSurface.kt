package io.github.zr987459.pigo.feature.term

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.zr987459.pigo.contract.session.SessionHost

/**
 * 终端形态入口（骨架占位，r3/core 首推）。真正的实现由 r3/term 按 DESIGN-r3 §3 完成。
 * 只读 [host] 的状态、动作一律走 `host.runAction`（不得自己压 overlay 栈），不持有业务状态。
 */
@Composable
fun TermSurface(host: SessionHost, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag("term:surface"), contentAlignment = Alignment.Center) {
        Text("终端形态尚未合入")
    }
}
