package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.AttachmentProcessor
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.SessionExporter
import io.github.zr987459.pigo.contract.WorkspaceFs
import io.github.zr987459.pigo.contract.fake.FakeAttachmentProcessor
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.contract.fake.FakeSessionExporter
import io.github.zr987459.pigo.contract.fake.FakeWorkspaceFs
import io.github.zr987459.pigo.core.workspace.WorkspaceServices
import io.github.zr987459.pigo.feature.files.FilesDeps
import io.github.zr987459.pigo.feature.files.FilesRuntime

/**
 * 文件浏览/预览、附件处理、导出分享（W3 feat/files，契约 `Workspace.kt`）。
 * 真实实现是 `:core:workspace` 的 [WorkspaceServices]（W3 PR #9 已合入 main）。
 * HTML 导出：当前会话走 W1 的 `EngineExtras.exportHtml`（`export_html`，W1 PR #13 已合入）；其他会话走容器内 `pi --export`。
 * extras 是 Fake（引擎为 FAKE 的本地调试）时不传，统一走 `pi --export`。
 */
object WorkspaceBindings {
    class Workspace(val fs: WorkspaceFs, val attachments: AttachmentProcessor, val exporter: SessionExporter) {
        /** feature/files 的依赖；同时登记给系统分享入口（FilesRuntime）。 */
        val deps: FilesDeps by lazy { FilesDeps(fs, attachments, exporter).also { FilesRuntime.install(it) } }
    }

    fun workspace(mode: Mode, ctx: BindingContext, runtime: RuntimeManager, engine: EngineClient, extras: EngineExtras): Workspace =
        when (mode) {
            Mode.FAKE -> fake()
            Mode.REAL -> real(ctx, runtime, engine, extras)
        }

    fun fake(): Workspace {
        val fs = FakeWorkspaceFs()
        return Workspace(fs, FakeAttachmentProcessor(fs), FakeSessionExporter())
    }

    fun real(ctx: BindingContext, runtime: RuntimeManager, engine: EngineClient, extras: EngineExtras): Workspace {
        val svc = WorkspaceServices(
            runtime,
            ctx.appContext.cacheDir,
            exportHtml = if (extras is FakeEngineExtras) null else { out -> extras.exportHtml(out) },
            currentSessionPath = { engine.chat.value.sessionFile },
        )
        return Workspace(svc.fs, svc.attachments, svc.exporter)
    }
}
