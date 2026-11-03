package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.rpc.RpcCommand
import io.github.zr987459.pigo.contract.rpc.RpcRecord
import io.github.zr987459.pigo.contract.rpc.RpcResponse
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一条 pi RPC 连接的传输层（DESIGN.md §3.2–§3.4），与 Android 无关，桌面与手机共用。
 *
 * - stdout：专用读线程持续读字节块 → [JsonlFramer] → [RecordCodec]；只投递、从不阻塞（无界通道），
 *   因此不会因为界面慢而停读导致 pi 被 stdout 背压卡住。
 * - stderr：专用读线程排空 → 脱敏 → [LineRing]（500 行）→ 日志；不当协议解析。
 * - stdin：单写者（[Mutex]），每条 = JSON + LF，写后 flush，阻塞写放在 [io] 调度器上。
 * - 响应**按 id 关联**，与顺序无关；超时只移除自己那一条等待者，晚到的响应记日志后丢弃。
 * - 不带 id 的 `command:"parse"` 错误、非 JSON 行、超长行：记日志并经 [onProtocolError] 上报，不影响后续记录。
 * - stdout EOF：所有等待中的请求立即以 [ErrCode.ENGINE_EXIT] 结束（不会有协程永远等下去），事件通道关闭。
 */
class RpcChannel(
    private val stdout: InputStream,
    private val stdin: OutputStream,
    private val stderr: InputStream? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    val stderrTail: LineRing = LineRing(500),
    private val redactor: Redactor = Redactor(),
    private val log: (level: Level, msg: String) -> Unit = { _, _ -> },
    private val onProtocolError: (ProtocolError) -> Unit = {},
    private val framer: JsonlFramer = JsonlFramer(),
    private val name: String = "pi",
) {
    enum class Level { INFO, WARN }

    sealed interface ProtocolError {
        /** pi 回的不带 id 的 parse 错误：说明我们发出了它解析不了的命令，属客户端 bug。 */
        data class ParseRejected(val error: String?) : ProtocolError
        data class Malformed(val line: String, val reason: String) : ProtocolError
        data class Oversized(val bytes: Long) : ProtocolError
        /** 没有等待者的 response（超时后晚到，或 id 不认识）。 */
        data class Orphan(val response: RpcResponse) : ProtocolError
    }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<RpcResponse>>()
    private val writeLock = Mutex()
    private val started = AtomicBoolean(false)
    private val closedFlag = AtomicBoolean(false)
    private val _events = Channel<RpcRecord>(Channel.UNLIMITED)

    /** 事件与扩展 UI 请求（不含 response），按到达顺序。连接结束时关闭。 */
    val events: ReceiveChannel<RpcRecord> get() = _events

    /** stdout 读到 EOF（进程退出或管道断开）时完成。 */
    val closed = CompletableDeferred<Unit>()

    val isClosed: Boolean get() = closedFlag.get()

    /** 当前等待响应的请求数（测试与诊断用）。 */
    val pendingCount: Int get() = pending.size

    fun start() {
        if (!started.compareAndSet(false, true)) return
        thread("$name-stdout") { readStdout() }
        stderr?.let { s -> thread("$name-stderr") { drainStderr(s) } }
    }

    /**
     * 发送一条带 id 的命令并按 id 等待它的 response。
     * @return Ok(response) 仅当 `success:true`；`success:false` → [ErrCode.RPC_REJECTED]（message 为 pi 的 `error`）；
     *         超时 → [ErrCode.RPC_TIMEOUT]；连接已断 → [ErrCode.ENGINE_EXIT]。
     */
    suspend fun request(command: RpcCommand, timeoutMs: Long = RpcTimeouts.forCommand(command.type)): Outcome<RpcResponse> {
        val id = requireNotNull(command.id) { "${command.type} 没有 id，不能等待 response" }
        if (closedFlag.get()) return closedErr(command)
        val waiter = CompletableDeferred<RpcResponse>()
        check(pending.putIfAbsent(id, waiter) == null) { "重复的命令 id：$id" }
        try {
            // 关闭与登记之间的竞态：shutdown 先置标志再清点等待者，所以登记后再看一次标志即可。
            if (closedFlag.get()) return closedErr(command)
            if (!write(command)) return closedErr(command)
            val resp = try {
                withTimeoutOrNull(timeoutMs) { waiter.await() }
            } catch (e: ChannelClosedException) {
                return closedErr(command)
            } ?: return Outcome.Err(ErrCode.RPC_TIMEOUT, "${command.type} 超时（${timeoutMs / 1000} s 未响应）")
            return if (resp.success) Outcome.Ok(resp) else Outcome.Err(ErrCode.RPC_REJECTED, resp.error ?: "${command.type} 失败")
        } finally {
            pending.remove(id, waiter)
        }
    }

    /** 发送不需要 response 的记录（`extension_ui_response`）。@return 是否写出 */
    suspend fun send(command: RpcCommand): Boolean = write(command)

    /** 关闭 pi 的 stdin：官方的有序关闭方式。 */
    fun closeStdin() {
        try {
            stdin.close()
        } catch (_: IOException) {
        }
    }

    private suspend fun write(command: RpcCommand): Boolean {
        val bytes = (RecordCodec.encode(command) + "\n").toByteArray(Charsets.UTF_8)
        return writeLock.withLock {
            withContext(io) {
                try {
                    stdin.write(bytes)
                    stdin.flush()
                    true
                } catch (e: IOException) {
                    log(Level.WARN, "写 stdin 失败（${command.type}）：${e.message}")
                    false
                }
            }
        }
    }

    private fun readStdout() {
        try {
            JsonlFramer.pump(stdout, framer) { frame ->
                when (frame) {
                    is JsonlFramer.Frame.Line -> dispatch(frame.text)
                    is JsonlFramer.Frame.Oversized -> {
                        log(Level.WARN, "丢弃超长记录：${frame.bytes} 字节")
                        onProtocolError(ProtocolError.Oversized(frame.bytes))
                    }
                }
            }
        } catch (e: IOException) {
            log(Level.WARN, "读 stdout 结束：${e.message}")
        } catch (e: Throwable) {
            log(Level.WARN, "读 stdout 异常：$e")
        } finally {
            shutdown()
        }
    }

    internal fun dispatch(line: String) {
        when (val d = RecordCodec.decode(line)) {
            is RecordCodec.Decoded.Malformed -> {
                log(Level.WARN, "stdout 非 JSON 记录：${d.reason}：${redactor.redact(line.take(200))}")
                onProtocolError(ProtocolError.Malformed(line, d.reason))
            }
            is RecordCodec.Decoded.Record -> when (val r = d.record) {
                is RpcResponse -> onResponse(r)
                else -> _events.trySend(r)
            }
        }
    }

    private fun onResponse(r: RpcResponse) {
        if (r.id == null) {
            if (r.isParseError) {
                log(Level.WARN, "pi 无法解析我们发出的命令（客户端 bug）：${r.error}")
                onProtocolError(ProtocolError.ParseRejected(r.error))
            } else {
                log(Level.WARN, "收到不带 id 的 response：${r.command}")
                onProtocolError(ProtocolError.Orphan(r))
            }
            return
        }
        val waiter = pending.remove(r.id)
        if (waiter == null) {
            log(Level.INFO, "无人等待的 response（可能已超时）：${r.id} ${r.command}")
            onProtocolError(ProtocolError.Orphan(r))
        } else {
            waiter.complete(r)
        }
    }

    private fun drainStderr(s: InputStream) {
        val f = JsonlFramer(maxLineBytes = 64 * 1024)
        try {
            JsonlFramer.pump(s, f, chunkSize = 8 * 1024) { frame ->
                val text = when (frame) {
                    is JsonlFramer.Frame.Line -> frame.text
                    is JsonlFramer.Frame.Oversized -> "<${frame.bytes} 字节的超长 stderr 行已省略>"
                }
                val clean = redactor.redact(text)
                stderrTail.add(clean)
                log(Level.INFO, "stderr: $clean")
            }
        } catch (_: IOException) {
        } catch (e: Throwable) {
            log(Level.WARN, "读 stderr 异常：$e")
        }
    }

    private fun shutdown() {
        if (!closedFlag.compareAndSet(false, true)) return
        val ex = ChannelClosedException()
        for (w in pending.values) w.completeExceptionally(ex)
        pending.clear()
        _events.close()
        closed.complete(Unit)
    }

    private fun closedErr(command: RpcCommand) = Outcome.Err(ErrCode.ENGINE_EXIT, "引擎已退出，${command.type} 未完成")

    private fun thread(n: String, body: () -> Unit) = Thread(body, n).apply { isDaemon = true }.start()

    class ChannelClosedException : IOException("pi 连接已断开")
}
