package io.github.zr987459.pigo.contract.fake

import io.github.zr987459.pigo.contract.AppLog
import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.GuestProcess
import io.github.zr987459.pigo.contract.GuestSpec
import io.github.zr987459.pigo.contract.InstallProgress
import io.github.zr987459.pigo.contract.InstallStep
import io.github.zr987459.pigo.contract.LaunchEnvProvider
import io.github.zr987459.pigo.contract.LogLevel
import io.github.zr987459.pigo.contract.LogLine
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Phase
import io.github.zr987459.pigo.contract.PresetKind
import io.github.zr987459.pigo.contract.ProviderConfigStore
import io.github.zr987459.pigo.contract.ProviderPreset
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.RuntimeStatus
import io.github.zr987459.pigo.contract.SecretStore
import io.github.zr987459.pigo.contract.SessionRepository
import io.github.zr987459.pigo.contract.SessionSummary
import io.github.zr987459.pigo.contract.sanitizeApiKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

// ---------------- 日志 ----------------

/** 内存日志；写入前做最基本的脱敏。真实实现由 mvp/engine 提供。 */
class FakeAppLog(private val capacity: Int = 500) : AppLog {
    private val lines = ArrayDeque<LogLine>()

    @Synchronized
    private fun add(level: LogLevel, tag: String, msg: String) {
        lines.addLast(LogLine(System.currentTimeMillis(), level, tag, redact(msg)))
        while (lines.size > capacity) lines.removeFirst()
    }

    override fun i(tag: String, msg: String) = add(LogLevel.INFO, tag, msg)
    override fun w(tag: String, msg: String, t: Throwable?) =
        add(LogLevel.WARN, tag, if (t != null) "$msg (${t::class.simpleName}: ${t.message})" else msg)

    @Synchronized
    override fun tail(n: Int): List<LogLine> = lines.toList().takeLast(n)

    companion object {
        private val patterns = listOf(
            Regex("sk-[A-Za-z0-9_\\-]{6,}"),
            Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{6,}"),
        )

        fun redact(s: String): String = patterns.fold(s) { acc, r -> r.replace(acc, "***") }
    }
}

// ---------------- 运行环境 ----------------

class FakeGuestProcess(
    output: String = "",
    private val exitCode: Int = 0,
) : GuestProcess {
    private val exited = CompletableDeferred<Int>()
    override val stdin: OutputStream = ByteArrayOutputStream()
    override val stdout: InputStream = ByteArrayInputStream(output.toByteArray())
    override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))
    override val prootPid: Int = -1

    override suspend fun awaitExit(): Int = exited.await()
    override suspend fun stop(graceMs: Long) {
        exited.complete(exitCode)
    }
}

/** 假装已经装好的运行环境。`install()` 快速走完全部步骤。 */
class FakeRuntimeManager(
    initialPhase: Phase = Phase.READY,
    private val stepDelayMs: Long = 150,
    private val root: File = File(System.getProperty("java.io.tmpdir"), "pigo-fake-rootfs"),
) : RuntimeManager {

    private val ready = RuntimeStatus(Phase.READY, ubuntu = "24.04.5", node = "v24.22.2", pi = "1.5.0", error = null)
    private val _status = MutableStateFlow(
        if (initialPhase == Phase.READY) ready else RuntimeStatus(initialPhase, null, null, null, null),
    )
    override val status: StateFlow<RuntimeStatus> = _status.asStateFlow()

    override fun install(): Flow<InstallProgress> = flow {
        _status.value = RuntimeStatus(Phase.INSTALLING, null, null, null, null)
        for (step in InstallStep.entries) {
            for (p in listOf(0, 50, 100)) {
                emit(InstallProgress(step, p, bytesPerSec = null, message = "${step.name} $p%"))
                delay(stepDelayMs)
            }
        }
        _status.value = ready
    }

    override suspend fun repair(): Outcome<Unit> {
        _status.value = ready
        return Outcome.Ok(Unit)
    }

    override suspend fun upgradePi(version: String): Outcome<String> {
        _status.update { it.copy(pi = version) }
        return Outcome.Ok(version)
    }

    override fun start(spec: GuestSpec): GuestProcess = FakeGuestProcess()

    override suspend fun exec(cmd: List<String>, env: Map<String, String>, timeoutMs: Long): ExecResult = when (cmd) {
        listOf("node", "-v") -> ExecResult(0, "v24.22.2\n", "")
        listOf("pi", "--version") -> ExecResult(0, "1.5.0\n", "")
        else -> ExecResult(0, "", "")
    }

    override fun hostFile(guestPath: String): File = File(root, guestPath.removePrefix("/"))
}

// ---------------- 存储 ----------------

class FakeSessionRepository(initial: List<SessionSummary> = demoSessions()) : SessionRepository {
    private val sessions = MutableStateFlow(initial)
    private val trash = MutableStateFlow<List<SessionSummary>>(emptyList())

    override fun observe(): Flow<List<SessionSummary>> = sessions
    override fun observeTrash(): Flow<List<SessionSummary>> = trash
    override suspend fun refresh() = Unit

    override suspend fun moveToTrash(guestPath: String): Outcome<String> {
        val s = sessions.value.firstOrNull { it.guestPath == guestPath }
            ?: return Outcome.Err(ErrCode.UNKNOWN, "会话不存在：$guestPath")
        val newPath = guestPath.replace("/.pi/agent/sessions/", "/.pi/agent/sessions-trash/")
        sessions.update { list -> list - s }
        trash.update { it + s.copy(guestPath = newPath) }
        return Outcome.Ok(newPath)
    }

    override suspend fun restore(trashPath: String): Outcome<String> {
        val s = trash.value.firstOrNull { it.guestPath == trashPath }
            ?: return Outcome.Err(ErrCode.UNKNOWN, "回收站里没有：$trashPath")
        val back = trashPath.replace("/.pi/agent/sessions-trash/", "/.pi/agent/sessions/")
        trash.update { it - s }
        sessions.update { (it + s.copy(guestPath = back)).sortedByDescending { x -> x.updatedAt } }
        return Outcome.Ok(back)
    }

    companion object {
        fun demoSessions(): List<SessionSummary> {
            val now = Instant.parse("2026-11-03T06:40:00Z")
            return listOf(
                SessionSummary(
                    guestPath = "/root/.pi/agent/sessions/--root-pi-cwd--/2026-11-03T06-40-00-000Z_demo-1.jsonl",
                    id = "demo-1", cwd = "/root/pi-cwd", name = "修登录 bug", preview = "登录页点了没反应",
                    createdAt = now, updatedAt = now, sizeBytes = 2048,
                ),
                SessionSummary(
                    guestPath = "/root/.pi/agent/sessions/--root-pi-cwd--/2026-11-02T10-00-00-000Z_demo-2.jsonl",
                    id = "demo-2", cwd = "/root/pi-cwd", name = null, preview = "列出当前目录并读 README",
                    createdAt = now.minusSeconds(86_400), updatedAt = now.minusSeconds(86_400), sizeBytes = 1024,
                ),
            )
        }
    }
}

class FakeSecretStore : SecretStore {
    private val map = mutableMapOf<String, String>()
    override suspend fun put(alias: String, plain: String) { map[alias] = plain }
    override suspend fun get(alias: String): String? = map[alias]
    override suspend fun remove(alias: String) { map.remove(alias) }
    override suspend fun last4(alias: String): String? = map[alias]?.takeLast(4)
}

class FakeProviderConfigStore(private val secrets: SecretStore = FakeSecretStore()) : ProviderConfigStore {
    private val configured = linkedSetOf<String>()
    var default: Triple<String, String, String?>? = null
        private set

    override fun presets(): List<ProviderPreset> = DEFAULT_PRESETS

    override suspend fun setBuiltInKey(provider: String, rawKey: String) {
        secrets.put("key.$provider", sanitizeApiKey(rawKey))
        configured += provider
    }

    override suspend fun upsertCustom(id: String, baseUrl: String, rawKey: String, modelIds: List<String>) {
        secrets.put("key.custom.$id", sanitizeApiKey(rawKey))
        configured += id
    }

    override suspend fun setDefault(provider: String, modelId: String, thinking: String?) {
        default = Triple(provider, modelId, thinking)
    }

    override suspend fun configured(): List<String> = configured.toList()

    companion object {
        /** DESIGN.md §3.10 的预设表。 */
        val DEFAULT_PRESETS: List<ProviderPreset> = listOf(
            ProviderPreset("deepseek", "DeepSeek", PresetKind.PAY_AS_YOU_GO, "DEEPSEEK_API_KEY", null, "普通开放平台 Key"),
            ProviderPreset("moonshotai-cn", "Moonshot（中国站）", PresetKind.PAY_AS_YOU_GO, "MOONSHOT_API_KEY", null, "baseUrl api.moonshot.cn/v1"),
            ProviderPreset("minimax-cn", "MiniMax（中国）", PresetKind.PAY_AS_YOU_GO, "MINIMAX_CN_API_KEY", null, ""),
            ProviderPreset("openrouter", "OpenRouter", PresetKind.PAY_AS_YOU_GO, "OPENROUTER_API_KEY", null, "国内网络可能需要代理"),
            ProviderPreset("openai", "OpenAI", PresetKind.PAY_AS_YOU_GO, "OPENAI_API_KEY", null, "国内网络可能需要代理"),
            ProviderPreset("anthropic", "Anthropic", PresetKind.PAY_AS_YOU_GO, "ANTHROPIC_API_KEY", null, "国内网络可能需要代理"),
            ProviderPreset("zai-coding-cn", "智谱 GLM Coding Plan（中国）", PresetKind.CODING_PLAN, "ZAI_CODING_CN_API_KEY", null, "需要套餐 Key；普通智谱开放平台 Key 请用自定义"),
            ProviderPreset("qwen-token-plan-cn", "通义 Token Plan（中国）", PresetKind.CODING_PLAN, "QWEN_TOKEN_PLAN_CN_API_KEY", null, "需要套餐 Key；普通百炼 Key 请用自定义"),
            ProviderPreset("kimi-coding", "Kimi For Coding", PresetKind.CODING_PLAN, "KIMI_API_KEY", null, "需要套餐 Key；普通 Moonshot Key 用 Moonshot"),
            ProviderPreset("bailian", "阿里云百炼（按量）", PresetKind.CUSTOM_OPENAI, "PIGO_KEY_BAILIAN", "https://dashscope.aliyuncs.com/compatible-mode/v1", "模型 id 由用户填"),
            ProviderPreset("zhipu", "智谱开放平台（按量）", PresetKind.CUSTOM_OPENAI, "PIGO_KEY_ZHIPU", "https://open.bigmodel.cn/api/paas/v4", "模型 id 由用户填"),
            ProviderPreset("custom", "其他 OpenAI 兼容", PresetKind.CUSTOM_OPENAI, null, null, "baseUrl、模型 id 由用户填"),
        )
    }
}

class FakeLaunchEnvProvider(private val values: Map<String, String> = emptyMap()) : LaunchEnvProvider {
    override suspend fun env(): Map<String, String> = values
}
