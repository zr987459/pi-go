package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.core.engine.PiEngine
import io.github.zr987459.pigo.core.session.GuestOutput
import io.github.zr987459.pigo.core.session.SessionOps
import io.github.zr987459.pigo.core.session.TreeNav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [SessionOps] 的 App 实现（DESIGN-r3 §5.6；r3/session 留给装配层的能力）。
 *
 * - `abort_retry`：PiEngine 自带（只发它，不发 abort / clear_queue）。
 * - `runGuest`：在运行环境（PRoot）里 `bash -lc` 直接执行，不经 RPC `bash`，不写进会话文件。
 * - `writeReport`：写进 App 缓存目录 `reports/`。
 * - `newSessionInDir`：`new_session` 带工作目录（PiEngine 按需重启进程）。
 * - 标签、重新加载资源、树原地切换、取回排队、停止引擎：要靠 pigo-bridge（PiBridge，在 r3/core-base，**尚未进 main**），
 *   现在返回明确的中文原因，不假装成功；bridge 进 main 后在这里接上（交接见 docs/status/r3-app.md）。
 */
class AppSessionOps(
    private val engine: EngineClient,
    private val runtime: RuntimeManager,
    private val cacheDir: File,
) : SessionOps {

    private val pi: PiEngine? get() = ((engine as? GatedEngineClient)?.inner ?: engine) as? PiEngine

    override suspend fun abortRetry(): Outcome<Unit> =
        pi?.abortRetry() ?: Outcome.Err(ErrCode.UNKNOWN, "当前引擎不支持取消重试")

    override suspend fun dequeueAll(): Outcome<QueueSnapshot> = needsBridge("取回排队消息")

    override suspend fun stopEngine(): Outcome<Unit> = needsBridge("停止引擎")

    override suspend fun setLabel(entryId: String, label: String?): Outcome<Unit> = needsBridge("设置标签")

    override suspend fun reload(): Outcome<Unit> = needsBridge("重新加载资源")

    override suspend fun treeNavigate(entryId: String, summarize: Boolean): Outcome<TreeNav> = needsBridge("在分支树里原地切换")

    override suspend fun runGuest(command: String, timeoutMs: Long): Outcome<GuestOutput> = try {
        val r = runtime.exec(listOf("bash", "-lc", command), timeoutMs = timeoutMs)
        val out = buildString {
            append(r.stdout)
            if (r.stderr.isNotBlank()) { if (isNotEmpty() && !endsWith("\n")) append('\n'); append(r.stderr) }
            if (r.timedOut) append("\n（超时，已停止）")
        }
        Outcome.Ok(GuestOutput(if (r.timedOut) -1 else r.exitCode, out))
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Outcome.Err(ErrCode.UNKNOWN, t.message ?: "运行环境执行失败", t)
    }

    override suspend fun writeReport(fileName: String, text: String): Outcome<String> = withContext(Dispatchers.IO) {
        try {
            val dir = File(cacheDir, "reports").apply { mkdirs() }
            val f = File(dir, fileName.replace('/', '_'))
            f.writeText(text)
            Outcome.Ok(f.absolutePath)
        } catch (t: Throwable) {
            Outcome.Err(ErrCode.DISK_FULL, t.message ?: "写入失败", t)
        }
    }

    override suspend fun newSessionInDir(dir: String): Outcome<Unit> = engine.newSession(dir)

    private fun <T> needsBridge(what: String): Outcome<T> =
        Outcome.Err(ErrCode.UNKNOWN, "${what}需要 pi-go 桥接扩展（尚未接入此版本）")
}
