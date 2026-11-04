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
    /**
     * 随包扩展 `pigo-bridge.ts` 的容器内路径（DESIGN-r3 §5.6）。文件实际存在（已安装）时才加 `-e <路径>`；
     * null 表示不加载（只走退路）。
     */
    val bridgeExtension: String? = PigoBridge.GUEST_PATH,
    /** 启动后等桥接 ready 的时间；超时视为不可用，走退路。 */
    val bridgeReadyTimeoutMs: Long = 8_000,
    /** 快的桥接命令（`/pigo:label`）等回复的时间。 */
    val bridgeReplyTimeoutMs: Long = 30_000,
    /**
     * 慢的桥接命令（不带摘要的 `/pigo:tree-nav`、`/pigo:reload`、`/pigo:switch`）等回复的时间：reload 要重新加载全部扩展，
     * 切到大会话要读整个会话文件、重新绑定扩展。pi 要等扩展 handler 执行完才回 `prompt` 的 response（审查第 1 轮 Important 1）。
     */
    val bridgeSlowTimeoutMs: Long = 2 * RpcTimeouts.SLOW_MS,
    /** 带摘要的树切换（`/pigo:tree-nav --summarize`）要等模型写分支摘要，与 `compact` 同为 [RpcTimeouts.COMPACT_MS]。 */
    val bridgeSummarizeTimeoutMs: Long = RpcTimeouts.COMPACT_MS,
    /** 超时之后仍接收迟到回复的窗口：迟到的回复到了就再按 pi 当前状态重建一次时间线。 */
    val bridgeLateWindowMs: Long = RpcTimeouts.COMPACT_MS,
    /**
     * 启动参数加 `-a`（`--approve`，信任本次运行的项目目录）。默认关：它对整次运行的所有目录生效；
     * 按目录信任改用 [ProjectTrustFile] 写 `trust.json`（docs/protocol/r3-measurements.md §2）。
     */
    val approveProject: Boolean = false,
) {
    /**
     * 命令超时：abort、get_messages 可配置；compact（10 分钟）、bash（2 小时）、export_html/fork/clone/get_tree/get_entries（60 秒）
     * 用 [RpcTimeouts.forCommand] 的长超时（不随测试缩短的 [defaultTimeoutMs] 变短）；其余用 [defaultTimeoutMs]。
     */
    fun timeoutFor(type: String): Long = when (type) {
        "abort" -> abortTimeoutMs
        "get_messages" -> getMessagesTimeoutMs
        else -> RpcTimeouts.forCommand(type).let { if (it == RpcTimeouts.DEFAULT_MS) defaultTimeoutMs else it }
    }

    /**
     * §3.2 的启动参数：`pi --mode rpc [--session <file>] [-e <pigo-bridge.ts>] [-a]`，cwd 为会话 cwd，环境 = Key + 固定项。
     * @param withBridge 桥接扩展已安装在 [bridgeExtension]（调用方检查过文件存在）
     */
    fun spec(sessionFile: String?, cwd: String?, keyEnv: Map<String, String>, withBridge: Boolean = false): GuestSpec {
        val argv = buildList {
            add(piCommand)
            add("--mode")
            add("rpc")
            if (!sessionFile.isNullOrEmpty()) {
                add("--session")
                add(sessionFile)
            }
            if (withBridge && !bridgeExtension.isNullOrEmpty()) {
                add("-e")
                add(bridgeExtension)
            }
            if (approveProject) add("-a")
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
