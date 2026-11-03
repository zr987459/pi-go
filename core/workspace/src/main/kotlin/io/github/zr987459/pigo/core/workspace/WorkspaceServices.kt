package io.github.zr987459.pigo.core.workspace

import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.RuntimeManager
import java.io.File

/**
 * core/workspace 对外的装配入口：由 `app/di/FilesBindings.kt`（真实实现）调用。
 *
 * @param cacheDir 应用缓存目录，导出文件写到其下 `pigo-exports/`
 * @param exportHtml 引擎的 `export_html`（W1 的 EngineExtras.exportHtml）；没接上时 HTML 导出提示「需要引擎运行」
 */
class WorkspaceServices(
    runtime: RuntimeManager,
    cacheDir: File,
    exportHtml: (suspend (String) -> Outcome<String>)? = null,
) {
    val fs = RootfsWorkspace(runtime.hostFile("/"), { cmd, timeout -> runtime.exec(cmd, emptyMap(), timeout) })
    val attachments = WorkspaceAttachments(fs, AndroidImageCodec)
    val exporter = WorkspaceSessionExporter(fs, File(cacheDir, "pigo-exports"), exportHtml)
}
