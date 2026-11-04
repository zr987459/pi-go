package io.github.zr987459.pigo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

/**
 * 仅 debug 的测试宿主：窗口配置照 [MainActivity]——清单里 `windowSoftInputMode="adjustResize"`，`onCreate` 里 `enableEdgeToEdge()`，
 * 内容由仪器测试 `setContent`。ui-test-manifest 自带的 ComponentActivity 是默认 adjustPan，软键盘弹出时会把整个窗口上推，
 * 与 App 的真实行为不同。
 */
class MainWindowTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }
}
