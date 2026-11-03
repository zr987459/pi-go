package io.github.zr987459.pigo.contract

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class Phase { NOT_INSTALLED, INSTALLING, READY, BROKEN }

data class RuntimeStatus(
    val phase: Phase,
    val ubuntu: String?,
    val node: String?,
    val pi: String?,
    val error: String?,
)

enum class InstallStep { PREFLIGHT, PROBE, ROOTFS, NODE, FIXUPS, APT, PI, VERIFY }

data class InstallProgress(
    val step: InstallStep,
    val percent: Int,
    val bytesPerSec: Long?,
    val message: String,
)

data class GuestSpec(
    /** 例：`["pi","--mode","rpc","--session","/root/.pi/agent/sessions/…jsonl"]` */
    val argv: List<String>,
    val cwd: String = "/root/pi-cwd",
    /** 经 `/usr/bin/env` 传入容器，不写文件。 */
    val env: Map<String, String> = emptyMap(),
)

data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)

/** 包装 `java.lang.Process`，带「只杀自己进程树」的停止。 */
interface GuestProcess {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
    val prootPid: Int
    suspend fun awaitExit(): Int

    /** 关 stdin → 等 → SIGTERM 进程树 → SIGKILL 进程树。 */
    suspend fun stop(graceMs: Long = 5_000)
}

interface RuntimeManager {
    val status: StateFlow<RuntimeStatus>

    /** 冷流；取消即中止；可重入。 */
    fun install(): Flow<InstallProgress>

    /** 只重跑缺失/损坏的步骤。 */
    suspend fun repair(): Outcome<Unit>

    /** 版本化 prefix + 冒烟 + 切软链，失败回滚。 */
    suspend fun upgradePi(version: String): Outcome<String>

    fun start(spec: GuestSpec): GuestProcess

    suspend fun exec(cmd: List<String>, env: Map<String, String> = emptyMap(), timeoutMs: Long = 60_000): ExecResult

    /** `/root/x` → `files/rootfs/root/x` */
    fun hostFile(guestPath: String): File
}
