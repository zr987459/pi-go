package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.AttachmentProcessor
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.SessionExporter
import io.github.zr987459.pigo.contract.WorkspaceFs
import io.github.zr987459.pigo.contract.fake.FakeAttachmentProcessor
import io.github.zr987459.pigo.contract.fake.FakeSessionExporter
import io.github.zr987459.pigo.contract.fake.FakeWorkspaceFs

/**
 * 文件浏览/预览、附件处理、导出分享（W3 feat/files，契约 `Workspace.kt`）。
 * W3（PR #9）合入 main 之前 `:core:workspace` 只有占位，[BindingMode.workspace] 为 FAKE；合入后在 [real] 里接 W3 的实现。
 */
object WorkspaceBindings {
    class Workspace(val fs: WorkspaceFs, val attachments: AttachmentProcessor, val exporter: SessionExporter)

    fun workspace(mode: Mode, ctx: BindingContext, runtime: RuntimeManager): Workspace = when (mode) {
        Mode.FAKE -> fake()
        Mode.REAL -> real(ctx, runtime)
    }

    fun fake(): Workspace {
        val fs = FakeWorkspaceFs()
        return Workspace(fs, FakeAttachmentProcessor(fs), FakeSessionExporter())
    }

    @Suppress("UNUSED_PARAMETER")
    fun real(ctx: BindingContext, runtime: RuntimeManager): Workspace = notWired("Workspace")
}
