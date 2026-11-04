package io.github.zr987459.pigo.feature.files.output

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 系统返回键。feature/files 不依赖 activity-compose（构建文件归 scaffold），由装配层在外面提供：
 * `CompositionLocalProvider(LocalFilesBackHandler provides { enabled, onBack -> BackHandler(enabled, onBack) }) { FilesHost(…) }`。
 * 没提供时返回键交给 App 的导航（整页关闭），页面内的「←」按钮照常逐级返回。
 */
val LocalFilesBackHandler = staticCompositionLocalOf<@Composable (enabled: Boolean, onBack: () -> Unit) -> Unit> { { _, _ -> } }

@Composable
internal fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    LocalFilesBackHandler.current(enabled, onBack)
}
