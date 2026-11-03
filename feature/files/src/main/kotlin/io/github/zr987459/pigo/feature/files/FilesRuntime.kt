package io.github.zr987459.pigo.feature.files

import io.github.zr987459.pigo.contract.AttachmentProcessor
import io.github.zr987459.pigo.contract.SessionExporter
import io.github.zr987459.pigo.contract.WorkspaceFs

/**
 * feature/files 的依赖。由装配层（`di/FilesBindings.kt`，W5 接到 AppGraph）在 App 启动时 [install]；
 * 系统分享入口 [io.github.zr987459.pigo.feature.files.share.ShareTargetActivity] 等不经导航进来的入口从这里取。
 */
class FilesDeps(
    val fs: WorkspaceFs,
    val attachments: AttachmentProcessor,
    val exporter: SessionExporter,
    /** 当前会话的 cwd（没有会话时为默认工作目录 `/root/pi-cwd`）。 */
    val defaultCwd: () -> String = { DEFAULT_CWD },
) {
    companion object {
        const val DEFAULT_CWD = "/root/pi-cwd"
    }
}

object FilesRuntime {
    @Volatile
    var current: FilesDeps? = null
        private set

    fun install(deps: FilesDeps) { current = deps }
}
