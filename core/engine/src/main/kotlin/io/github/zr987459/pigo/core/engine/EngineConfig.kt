package io.github.zr987459.pigo.core.engine

import io.github.zr987459.pigo.contract.GuestSpec
import io.github.zr987459.pigo.core.protocol.RpcTimeouts

/**
 * 引擎参数（DESIGN.md §3.2、§3.4、§3.7、§3.9）。默认值即设计值；单测/桌面集成测试会缩短时间、改用主机路径。
 */
data class EngineConfig(
    /** 容器内 pi 的配置目录。 */
    val agentDir: String = "/root/.pi/agent",
    /** 默认工作目录。 */
    val defaultCwd: String = "/root/pi-cwd",
    /** 容器内可执行名或路径。 */
    val piCommand: String = "pi",
    /** `NODE_OPTIONS=--max-old-space-size=<n>`；null 表示不设。 */
    val nodeHeapMb: Int? = 1024,
    /** 归约后发布 StateFlow 的最小间隔（§3.5 性能：33 ms 批量）。 */
    val frameMs: Long = 33,
    val bootTimeoutMs: Long = RpcTimeouts.BOOT_MS,
    val abortTimeoutMs: Long = RpcTimeouts.ABORT_MS,
    val getMessagesTimeoutMs: Long = RpcTimeouts.GET_MESSAGES_MS,
    val defaultTimeoutMs: Long = RpcTimeouts.DEFAULT_MS,
    /** 崩溃后自动重启的退避（§3.9：1 s → 3 s → 10 s，最多 3 次）。 */
    val restartBackoffMs: List<Long> = listOf(1_000, 3_000, 10_000),
    /** 一次连接存活超过这么久，崩溃计数清零。 */
    val stableResetMs: Long = 60_000,
    /** 正常停止进程时关 stdin 后等待退出的时间（§4.5）。 */
    val stopGraceMs: Long = 5_000,
    /**
     * 对话类扩展 UI 请求（select/confirm/input/editor）暂时没有界面订阅时，等待订阅者出现的时间；
     * 超时仍无人处理才回 cancelled（§3.6：pi 不能永久挂起）。
     */
    val uiRequestGraceMs: Long = 3_000,
    /** 额外环境变量（测试用：例如桌面上覆盖 HOME）。 */
    val extraEnv: Map<String, String> = emptyMap(),
) {
    fun timeoutFor(type: String): Long = when (type) {
        "abort" -> abortTimeoutMs
        "get_messages" -> getMessagesTimeoutMs
        else -> defaultTimeoutMs
    }

    /** §3.2 的启动参数：`pi --mode rpc [--session <file>]`，cwd 为会话 cwd，环境 = Key + 固定项。 */
    fun spec(sessionFile: String?, cwd: String?, keyEnv: Map<String, String>): GuestSpec {
        val argv = buildList {
            add(piCommand)
            add("--mode")
            add("rpc")
            if (!sessionFile.isNullOrEmpty()) {
                add("--session")
                add(sessionFile)
            }
        }
        val env = LinkedHashMap<String, String>()
        env.putAll(keyEnv)
        env["PI_CODING_AGENT_DIR"] = agentDir
        env["PI_OFFLINE"] = "1"
        env["PI_SKIP_VERSION_CHECK"] = "1"
        env["PI_TELEMETRY"] = "0"
        nodeHeapMb?.let { env["NODE_OPTIONS"] = "--max-old-space-size=$it" }
        env.putAll(extraEnv)
        return GuestSpec(argv = argv, cwd = cwd ?: defaultCwd, env = env)
    }

    companion object {
        private const val SIX_GB = 6L * 1024 * 1024 * 1024

        /** 设备总内存 < 6 GB 取 768 MB，否则 1024 MB（§3.2）。 */
        fun heapMbFor(totalMemBytes: Long): Int = if (totalMemBytes in 1 until SIX_GB) 768 else 1024
    }
}
