package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.bridge.PiBridge
import io.github.zr987459.pigo.core.engine.PiEngine
import io.github.zr987459.pigo.core.session.GuestOutput
import io.github.zr987459.pigo.core.session.SessionOps
import io.github.zr987459.pigo.core.session.TreeNav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [SessionOps] 的 App 实现（DESIGN-r3 §5.6；r3/session 留给装配层的能力）。全部走真实引擎，没有桩：
 *
 * - 标签 / 重新加载资源 / 树内原地切换：[PiBridge]（pigo-bridge 的 `/pigo:label`、`/pigo:reload`、`/pigo:tree-nav`）。
 *   桥接不可用时由 PiEngine 自己走 §5.6 的退路（树切换退化为 fork；标签、reload 返回带原因的 Err）。
 * - `abort_retry`、取回排队（`clear_queue`，不中止当前一轮）、停止引擎（`/quit`：停掉当前一轮再结束 pi 进程，
 *   下次发送按需以 `--session` 启动）：[Engine]（真实为 PiEngine）。
 * - `runGuest`：在运行环境（PRoot）里 `bash -lc` 直接执行，不经 RPC `bash`，不写进会话文件。
 * - `writeReport`：写进 App 缓存目录 `reports/`。
 * - `newSessionInDir`：`new_session` 带工作目录（PiEngine 按需重启进程）。
 *
 * 构造参数都是窄接口，JVM 测试用 FakePiBridge + 假 [Engine] 装配同一段代码（AppSessionOpsTest）。
 */
class AppSessionOps(
    private val client: EngineClient,
    private val engine: Engine,
    private val bridge: PiBridge,
    private val exec: suspend (cmd: List<String>, timeoutMs: Long) -> ExecResult,
    private val cacheDir: File,
) : SessionOps {

    /** PiEngine 上 SessionOps 用到、但不在 EngineClient 契约里的那几个方法。 */
    interface Engine {
        suspend fun abortRetry(): Outcome<Unit>
        suspend fun clearQueue(): Outcome<QueueSnapshot>
        suspend fun stopProcess(): Outcome<Unit>
    }

    override suspend fun abortRetry(): Outcome<Unit> = engine.abortRetry()

    override suspend fun dequeueAll(): Outcome<QueueSnapshot> = engine.clearQueue()

    /**
     * 停止引擎撤回的排队文本交给谁（[SessionBindings.real] 设为「并进共享草稿」，与 session.stop 一致），
     * 这样 `/quit` 不丢用户还没发出的话。
     */
    @Volatile var onWithdrawn: (List<String>) -> Unit = {}

    override suspend fun stopEngine(): Outcome<Unit> {
        // 先停掉当前一轮（clear_queue → abort），时间线收尾；撤回的文本放回草稿；再结束进程。
        val s = client.stop()
        if (s is Outcome.Err) return s
        (s as Outcome.Ok).value.let { q -> (q.steering + q.followUp).takeIf { it.isNotEmpty() }?.let(onWithdrawn) }
        return engine.stopProcess()
    }

    override suspend fun setLabel(entryId: String, label: String?): Outcome<Unit> = bridge.setEntryLabel(entryId, label)

    override suspend fun reload(): Outcome<Unit> = bridge.reloadResources()

    override suspend fun treeNavigate(entryId: String, summarize: Boolean): Outcome<TreeNav> =
        when (val r = bridge.treeNavigate(entryId, summarize)) {
            is Outcome.Ok -> r.value.let { n ->
                Outcome.Ok(TreeNav(leafId = n.leafId, cancelled = n.cancelled, viaFork = n.viaFork, editorText = n.editorText, unconfirmed = n.unconfirmed))
            }
            is Outcome.Err -> r
        }

    override suspend fun runGuest(command: String, timeoutMs: Long): Outcome<GuestOutput> = try {
        val r = exec(listOf("bash", "-lc", command), timeoutMs)
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

    override suspend fun newSessionInDir(dir: String): Outcome<Unit> = client.newSession(dir)

    companion object {
        /**
         * 真实装配：[client] 是 AppGraph 的引擎（[GatedEngineClient] 包着 PiEngine）；桥接与扩展方法取里面那个 PiEngine
         * ——与两种形态共用的是同一个对象，不另起引擎。Fake 引擎（本地调试）时这几项返回「当前引擎不支持」。
         */
        fun forEngine(client: EngineClient, runtime: RuntimeManager, cacheDir: File): AppSessionOps {
            val pi = ((client as? GatedEngineClient)?.inner ?: client) as? PiEngine
            val engine = if (pi != null) object : Engine {
                override suspend fun abortRetry() = pi.abortRetry()
                override suspend fun clearQueue() = pi.clearQueue()
                override suspend fun stopProcess() = pi.stopProcess()
            } else Unsupported
            val bridge: PiBridge = pi ?: UnsupportedBridge
            return AppSessionOps(client, engine, bridge, { cmd, t -> runtime.exec(cmd, timeoutMs = t) }, cacheDir)
        }

        private const val NOT_PI = "当前引擎不是 pi（Fake 调试模式）"

        private object Unsupported : Engine {
            override suspend fun abortRetry() = Outcome.Err(ErrCode.UNKNOWN, NOT_PI)
            override suspend fun clearQueue() = Outcome.Err(ErrCode.UNKNOWN, NOT_PI)
            override suspend fun stopProcess() = Outcome.Err(ErrCode.UNKNOWN, NOT_PI)
        }

        private object UnsupportedBridge : PiBridge {
            override val bridgeStatus = kotlinx.coroutines.flow.MutableStateFlow(
                io.github.zr987459.pigo.contract.bridge.BridgeStatus(io.github.zr987459.pigo.contract.bridge.BridgeState.UNAVAILABLE, NOT_PI),
            )
            override suspend fun treeNavigate(entryId: String, summarize: Boolean) = Outcome.Err(ErrCode.UNKNOWN, NOT_PI)
            override suspend fun setEntryLabel(entryId: String, label: String?) = Outcome.Err(ErrCode.UNKNOWN, NOT_PI)
            override suspend fun reloadResources() = Outcome.Err(ErrCode.UNKNOWN, NOT_PI)
            override suspend fun switchSessionViaBridge(sessionPath: String) = Outcome.Err(ErrCode.UNKNOWN, NOT_PI)
        }
    }
}
