package io.github.zr987459.pigo.core.engine

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.bridge.BridgeState
import io.github.zr987459.pigo.contract.bridge.BridgeStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `pigo-bridge.ts`（DESIGN-r3 §5.6）的 Kotlin 侧：安装、命令文本、回传解析。协议见扩展文件头注释与
 * docs/protocol/r3-measurements.md §1。
 */
object PigoBridge {
    /** APK 内的资源路径（`core/engine/src/main/assets/`）。 */
    const val ASSET_PATH = "pi/pigo-bridge.ts"

    /** 容器内安装位置（`-e` 参数）。 */
    const val GUEST_PATH = "/root/.pi/pigo/pigo-bridge.ts"

    const val PREFIX = "pigo-bridge:"
    const val VERSION = 1

    sealed interface Message {
        /** 新扩展实例生效：startup / reload / resume / new / fork。 */
        data class Ready(val version: Int?, val reason: String, val sessionFile: String?, val req: String? = null) : Message

        /** 某条命令的回复。 */
        data class Reply(val cmd: String, val ok: Boolean, val error: String?, val raw: JsonObject) : Message {
            fun str(key: String): String? = (raw[key] as? JsonPrimitive)?.contentOrNull
            fun bool(key: String): Boolean = (raw[key] as? JsonPrimitive)?.booleanOrNull == true
            /** 扩展原样带回的请求序号（`--req=`）。 */
            val req: String? get() = str("req")
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析 notify 消息；不是桥接消息返回 null。 */
    fun parse(message: String?): Message? {
        if (message == null || !message.startsWith(PREFIX)) return null
        val o = runCatching { json.parseToJsonElement(message.substring(PREFIX.length)) as? JsonObject }.getOrNull() ?: return null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        return when {
            s("event") == "ready" -> Message.Ready((o["version"] as? JsonPrimitive)?.intOrNull, s("reason") ?: "startup", s("sessionFile"), s("req"))
            s("cmd") != null -> Message.Reply(s("cmd")!!, (o["ok"] as? JsonPrimitive)?.booleanOrNull == true, s("error"), o)
            else -> null
        }
    }

    /** entryId 只允许 pi 生成的 id 字符（十六进制、字母数字、`-`、`_`），防止拼进命令时注入别的参数。 */
    fun validEntryId(id: String): Boolean = id.isNotEmpty() && id.length <= 128 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** 请求序号参数（扩展在回复里原样带回，见 pigo-bridge.ts 头注释）。 */
    private fun reqArg(req: String?) = if (req == null) "" else "--req=$req "

    fun treeNavCommand(entryId: String, summarize: Boolean, req: String? = null): String =
        "/pigo:tree-nav ${reqArg(req)}$entryId" + if (summarize) " --summarize" else ""

    /** 标签里的换行与制表压成空格（命令是一行）；空白 = 清除。 */
    fun labelCommand(entryId: String, label: String?, req: String? = null): String {
        val clean = label.orEmpty().replace(Regex("[\\r\\n\\t]+"), " ").trim()
        return if (clean.isEmpty()) "/pigo:label ${reqArg(req)}$entryId" else "/pigo:label ${reqArg(req)}$entryId $clean"
    }

    const val RELOAD_COMMAND = "/pigo:reload"

    fun reloadCommand(req: String? = null): String = if (req == null) RELOAD_COMMAND else "$RELOAD_COMMAND --req=$req"

    fun switchCommand(path: String, req: String? = null): String = "/pigo:switch " + reqArg(req) + path.replace(Regex("[\\r\\n]+"), "").trim()

    /**
     * 容器内路径的规范形式，用于对账比较（审查 PR #16 Nit 4）：展开 `~`（[home]）、相对路径按 [cwd] 解析、
     * 去掉多余的 `/`、`.`、`..` 与末尾 `/`。宿主看不到容器内的符号链接，所以只做字面规范化。
     */
    fun normalizePath(path: String?, cwd: String? = null, home: String = "/root"): String? {
        val p = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val abs = when {
            p == "~" -> home
            p.startsWith("~/") -> home + p.substring(1)
            p.startsWith("/") -> p
            cwd != null -> "$cwd/$p"
            else -> p
        }
        val out = ArrayDeque<String>()
        for (seg in abs.split('/')) when (seg) {
            "", "." -> Unit
            ".." -> out.removeLastOrNull()
            else -> out.addLast(seg)
        }
        return if (abs.startsWith("/")) "/" + out.joinToString("/") else out.joinToString("/")
    }

    /**
     * 把 [source] 写到 [target]（宿主路径）：内容相同则不动，不同则先写临时文件再改名。返回是否成功。
     * 每次启动引擎前调用，App 升级后扩展随之更新。
     */
    fun install(source: InputStream, target: File): Boolean = runCatching {
        val bytes = source.use { it.readBytes() }
        if (target.isFile && target.readBytes().contentEquals(bytes)) return@runCatching true
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.part")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) return@runCatching false
            }
        } finally {
            tmp.delete()
        }
        true
    }.getOrDefault(false)
}

/** 提供扩展源文件（Android 上从 APK assets 读，见 `AndroidBridgeSource`）。返回 null = 不可用。 */
fun interface BridgeSource {
    fun open(): InputStream?
}

/**
 * 一个引擎进程内的桥接状态与在途请求。由 [PiEngine] 持有：每次启动进程 [reset]，收到 notify 交给 [onNotify]，
 * 收到 `extension_error` 交给 [onExtensionError]。
 *
 * 请求流程：先 [expect] 登记等待条件，再发 `prompt`，最后 [Waiter.await]——避免回复早于登记而丢失。
 */
class BridgeChannel(
    /** 没有任何在途请求认领的回复（多为超时之后迟到、而且已有新的同类请求）：丢弃，交给调用方记日志。 */
    private val onDropped: (PigoBridge.Message) -> Unit = {},
) {
    private val reqSeq = java.util.concurrent.atomic.AtomicLong()

    /** 新的请求序号（随命令发给扩展，回复按它认领）。 */
    fun newReq(): String = "r" + reqSeq.incrementAndGet()

    private val _status = MutableStateFlow(BridgeStatus(BridgeState.UNAVAILABLE, reason = "引擎未启动"))
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    inner class Waiter internal constructor(val match: (PigoBridge.Message) -> Boolean) {
        internal val result = CompletableDeferred<Outcome<PigoBridge.Message>>()

        /**
         * 等回复，最多 [timeoutMs]。[keepOnTimeout] 为真时超时后仍保持登记（调用方随后用 [awaitLate] 接迟到的回复，
         * 或 [cancel]）；否则超时即注销。
         */
        suspend fun await(timeoutMs: Long, keepOnTimeout: Boolean = false): Outcome<PigoBridge.Message> {
            val r = withTimeoutOrNull(timeoutMs) { result.await() }
            if (r != null || !keepOnTimeout) waiters.remove(this)
            return r ?: Outcome.Err(ErrCode.RPC_TIMEOUT, "桥接扩展没有回应")
        }

        /** 超时之后继续等迟到的回复，最多 [windowMs]；结束（无论有没有等到）都注销。 */
        suspend fun awaitLate(windowMs: Long): PigoBridge.Message? {
            val r = withTimeoutOrNull(windowMs) { result.await() }
            waiters.remove(this)
            return (r as? Outcome.Ok)?.value
        }

        /** 提前以失败结束（例如承载命令的 `prompt` 被 pi 拒绝），不必等到超时。 */
        fun fail(err: Outcome.Err) {
            result.complete(err)
            waiters.remove(this)
        }

        val isDone: Boolean get() = result.isCompleted

        fun cancel() { waiters.remove(this) }
    }

    private val waiters = CopyOnWriteArrayList<Waiter>()

    @Volatile private var gen = 0

    /** 新进程（第 [gen] 代）：有扩展参数时进入 PENDING，否则直接 UNAVAILABLE（[reason]）。在途请求全部失败。 */
    fun reset(gen: Int, loaded: Boolean, reason: String? = null) {
        this.gen = gen
        _status.value = if (loaded) BridgeStatus(BridgeState.PENDING) else BridgeStatus(BridgeState.UNAVAILABLE, reason ?: "桥接扩展未安装")
        failAll("引擎已重启")
    }

    /** 第 [gen] 代进程退出；已被新进程取代的旧进程退出不影响状态。 */
    fun onExit(gen: Int) {
        if (gen != this.gen) return
        _status.value = BridgeStatus(BridgeState.UNAVAILABLE, reason = "引擎未运行")
        failAll("引擎已退出")
    }

    /** ready 一直没来（加载失败但没有 extension_error）。只在仍是 PENDING 时生效。 */
    fun onReadyTimeout() {
        if (_status.value.state == BridgeState.PENDING) {
            _status.value = BridgeStatus(BridgeState.UNAVAILABLE, reason = "桥接扩展没有就绪")
        }
    }

    /** 返回 true 表示这是桥接消息（已消费，不再交给界面）。 */
    fun onNotify(message: String?): Boolean {
        val m = PigoBridge.parse(message) ?: return false
        if (m is PigoBridge.Message.Ready) _status.value = BridgeStatus(BridgeState.READY, version = m.version)
        var claimed = false
        for (w in waiters) if (!w.result.isCompleted && w.match(m)) {
            w.result.complete(Outcome.Ok(m))
            waiters.remove(w)
            claimed = true
        }
        // 命令回复、以及带序号的 ready 都应有人认领；没人认领 = 迟到（发起它的请求已结束），丢弃并记日志。
        if (!claimed && (m is PigoBridge.Message.Reply || (m is PigoBridge.Message.Ready && m.req != null))) onDropped(m)
        return true
    }

    /** 本扩展报错（路径以 `pigo-bridge.ts` 结尾）且尚未 ready：标记不可用。ready 之后的运行时错误只记日志。 */
    fun onExtensionError(extensionPath: String?, error: String?): Boolean {
        if (extensionPath == null || !extensionPath.endsWith("pigo-bridge.ts")) return false
        if (_status.value.state != BridgeState.READY) {
            _status.value = BridgeStatus(BridgeState.UNAVAILABLE, reason = "桥接扩展加载失败：" + (error ?: "未知错误").take(200))
            failAll("桥接扩展加载失败")
        }
        return true
    }

    fun expect(match: (PigoBridge.Message) -> Boolean): Waiter = Waiter(match).also { waiters += it }

    /** [req] 非空时只认带同一序号的回复（审查 PR #16 Nit 1：超时后的迟到回复不会被下一次同类请求认领）。 */
    private fun sameReq(msgReq: String?, req: String?) = req == null || msgReq == req

    fun expectReply(cmd: String, req: String? = null): Waiter = expect { it is PigoBridge.Message.Reply && it.cmd == cmd && sameReq(it.req, req) }

    /**
     * 标签回复按 entryId 关联（审查 Nit 1）：同一 entry 的回复才算；扩展在参数缺失时回的失败没有 entryId，也接受。
     * PiEngine 另外对每种桥接命令串行（Mutex），所以同一时刻每种命令最多一个在途；[req] 再按序号认领。
     */
    fun expectLabelReply(entryId: String, req: String? = null): Waiter = expect {
        it is PigoBridge.Message.Reply && it.cmd == "label" && sameReq(it.req, req) && (it.str("entryId") == null || it.str("entryId") == entryId)
    }

    /**
     * 某个原因的 ready：带序号时只认同一序号；不带序号的 ready（扩展没能带回序号）只在 [fallback] 为真时认。
     */
    fun expectReady(reason: String, req: String? = null, fallback: (PigoBridge.Message.Ready) -> Boolean = { req == null }): Waiter = expect {
        it is PigoBridge.Message.Ready && it.reason == reason && (if (it.req != null) sameReq(it.req, req) else fallback(it))
    }

    /** reload / switch：完成信号是 [reason] 的 ready，失败或取消是 [cmd] 的回复；都按 [req] 认领。 */
    fun expectReadyOrReply(reason: String, cmd: String, req: String, fallback: (PigoBridge.Message.Ready) -> Boolean): Waiter = expect {
        when (it) {
            is PigoBridge.Message.Ready -> it.reason == reason && (if (it.req != null) it.req == req else fallback(it))
            is PigoBridge.Message.Reply -> it.cmd == cmd && it.req == req
        }
    }

    /** 等到 PENDING 结束（READY 或 UNAVAILABLE），最多 [timeoutMs]。返回最终是否 READY。 */
    suspend fun awaitSettled(timeoutMs: Long): Boolean {
        withTimeoutOrNull(timeoutMs) { _status.first { it.state != BridgeState.PENDING } }
        if (_status.value.state == BridgeState.PENDING) onReadyTimeout()
        return _status.value.state == BridgeState.READY
    }

    private fun failAll(why: String) {
        for (w in waiters) w.result.complete(Outcome.Err(ErrCode.ENGINE_EXIT, why))
        waiters.clear()
    }
}
