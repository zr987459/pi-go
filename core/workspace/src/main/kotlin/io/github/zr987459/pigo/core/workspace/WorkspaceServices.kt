package io.github.zr987459.pigo.core.workspace

import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.RuntimeManager
import java.io.File

/**
 * core/workspace 对外的装配入口：由 `app/di/FilesBindings.kt`（真实实现）调用。
 *
 * @param cacheDir 应用缓存目录，导出文件写到其下 `pigo-exports/`
 * @param exportHtml 引擎的 `export_html`（W1 的 EngineExtras.exportHtml），只导出当前会话
 * @param currentSessionPath 引擎当前会话文件（`EngineClient` 的当前会话路径）；用于判断能否走 [exportHtml]
 *
 * 非当前会话的 HTML 导出走容器内一次性 `pi --export <会话> <输出>`（pi 1.5.0 CLI），不影响正在运行的引擎。
 */
class WorkspaceServices(
    runtime: RuntimeManager,
    cacheDir: File,
    exportHtml: (suspend (String) -> Outcome<String>)? = null,
    currentSessionPath: (suspend () -> String?)? = null,
) {
    val fs = RootfsWorkspace(runtime.hostFile("/"), { cmd, timeout -> runtime.exec(cmd, emptyMap(), timeout) })
    val attachments = WorkspaceAttachments(fs, AndroidImageCodec)
    val exporter = WorkspaceSessionExporter(
        fs, File(cacheDir, "pigo-exports"), exportHtml,
        currentSessionPath = currentSessionPath,
        cliExportHtml = { session, out ->
            val r = runtime.exec(listOf("pi", "--export", session, out), emptyMap(), 120_000)
            if (r.exitCode == 0 && !r.timedOut) Outcome.Ok(out)
            else Outcome.Err(io.github.zr987459.pigo.contract.ErrCode.UNKNOWN,
                if (r.timedOut) "导出 HTML 超时" else "导出 HTML 失败：" + r.stderr.trim().lineSequence().firstOrNull().orEmpty())
        },
    )
}
