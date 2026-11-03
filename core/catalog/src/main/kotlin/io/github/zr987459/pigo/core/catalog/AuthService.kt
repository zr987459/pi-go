package io.github.zr987459.pigo.core.catalog

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.GuestProcess
import io.github.zr987459.pigo.contract.GuestSpec
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.Phase
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.core.store.BuiltinCatalog
import io.github.zr987459.pigo.core.store.GuestPaths
import io.github.zr987459.pigo.core.store.PiProviderConfigStore
import io.github.zr987459.pigo.core.store.ProviderSpecs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 服务商凭据：Key（宿主 Keystore，经进程环境注入）与 OAuth 订阅登录（容器内 pi 自己的流程，凭据写 auth.json）。
 *
 * OAuth 凭据落容器磁盘是调度方拍板的例外（DESIGN §3.10 增补）：auth.json 在应用私有目录、权限 600，
 * 排除在备份/导出/分享之外；宿主只读其中的「哪个服务商有哪类凭据」，从不读取或显示令牌本身。
 */
class AuthService(
    private val runtime: RuntimeManager,
    private val scripts: GuestScripts,
    private val config: PiProviderConfigStore,
    private val paths: GuestPaths,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** 登录 / 登出成功后通知（W5 接线：重启引擎或刷新 get_available_models）。 */
    private val onCredentialsChanged: suspend (provider: String) -> Unit = {},
) {
    enum class Kind { API_KEY, OAUTH_DEVICE, OAUTH_PKCE, CLOUD_ENV }

    data class ProviderAuth(
        val provider: String,
        val label: String,
        val note: String,
        val kinds: Set<Kind>,
        val oauthName: String?,
        val keyConfigured: Boolean,
        val oauthLoggedIn: Boolean,
        val fields: List<ProviderSpecs.Field>,
    ) {
        val configured: Boolean get() = keyConfigured || oauthLoggedIn
    }

    private val _oauth = MutableStateFlow<Set<String>>(emptySet())
    /** auth.json 里有 OAuth 凭据的服务商。 */
    val oauthProviders: StateFlow<Set<String>> = _oauth.asStateFlow()

    /** 设备码类的 OAuth（其余 OAuth 都是浏览器授权 + 回调/粘贴）。来自 pi-ai 1.5.0 `dist/auth/oauth 目录`。 */
    private val deviceCodeProviders = setOf("github-copilot", "kimi-coding")

    fun kindsOf(p: BuiltinCatalog.Provider): Set<Kind> = buildSet {
        val spec = ProviderSpecs.specFor(p.id, p.name, p.apiKeyAuth)
        if (p.apiKeyAuth && spec.keyEnv != null) add(if (spec.extra.isEmpty()) Kind.API_KEY else Kind.CLOUD_ENV)
        if (p.oauth) add(if (p.id in deviceCodeProviders) Kind.OAUTH_DEVICE else Kind.OAUTH_PKCE)
    }

    suspend fun providers(catalog: BuiltinCatalog.Snapshot): List<ProviderAuth> {
        refreshOAuthStatus()
        val oauth = _oauth.value
        val order = ProviderSpecs.ALL.withIndex().associate { (i, s) -> s.provider to i }
        return catalog.providers.sortedBy { order[it.id] ?: Int.MAX_VALUE }.map { p ->
            val spec = ProviderSpecs.specFor(p.id, p.name, p.apiKeyAuth)
            ProviderAuth(
                provider = p.id,
                label = spec.label,
                note = spec.note,
                kinds = kindsOf(p),
                oauthName = p.oauthName,
                keyConfigured = spec.keyEnv != null && config.isBuiltInConfigured(p.id),
                oauthLoggedIn = p.id in oauth,
                fields = if (p.apiKeyAuth) spec.allFields else emptyList(),
            )
        }
    }

    /** 读 auth.json 的「服务商 → 凭据类型」，只看 type 字段。文件不存在或损坏视为没有。 */
    suspend fun refreshOAuthStatus(): Set<String> = withContext(io) {
        val set = runCatching {
            val f = paths.authJson
            if (!f.isFile) emptySet() else {
                val o = Json.parseToJsonElement(f.readText()) as? JsonObject ?: JsonObject(emptyMap())
                o.filter { (_, v) -> ((v as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "oauth" }.keys
            }
        }.getOrDefault(emptySet())
        _oauth.value = set
        set
    }

    /**
     * 开始一次 OAuth 登录。运行环境没就绪时返回错误。
     * 释放脚本（读 asset、算 SHA、写文件）和拉起 proot 都是阻塞 IO，一律切到 [io]，调用方在主线程也不会卡（审查第 1 轮）。
     */
    suspend fun startOAuth(provider: String, proxyEnv: Map<String, String>): Outcome<OAuthLoginSession> = withContext(io) {
        if (runtime.status.value.phase != Phase.READY) return@withContext Outcome.Err(ErrCode.ENGINE_START, "请先完成运行环境安装再登录")
        try {
            scripts.ensure()
            val proc = runtime.start(GuestSpec(listOf(GuestScripts.NODE, GuestScripts.AUTH, "login", provider), env = proxyEnv))
            Outcome.Ok(
                OAuthLoginSession(provider, GuestLineProcess(proc, io), scope) { ok ->
                    refreshOAuthStatus()
                    if (ok) onCredentialsChanged(provider)
                },
            )
        } catch (e: Exception) {
            Outcome.Err(ErrCode.UNKNOWN, "无法启动登录：${e.message}", e)
        }
    }

    /** 退出 OAuth 登录（pi 自己删 auth.json 里的条目）。 */
    suspend fun logout(provider: String): Outcome<Unit> = withContext(io) {
        try {
            scripts.ensure()
            val r = runtime.exec(listOf(GuestScripts.NODE, GuestScripts.AUTH, "logout", provider), timeoutMs = 30_000)
            refreshOAuthStatus()
            val last = r.stdout.lineSequence().mapNotNull { OAuthProtocol.parse(it) }.lastOrNull()
            if (r.exitCode == 0 && last is LoginEvent.Done) {
                onCredentialsChanged(provider); Outcome.Ok(Unit)
            } else {
                Outcome.Err(ErrCode.PROVIDER, (last as? LoginEvent.Failed)?.message ?: "退出登录失败")
            }
        } catch (e: Exception) {
            Outcome.Err(ErrCode.UNKNOWN, "退出登录失败：${e.message}", e)
        }
    }

    /** `pi auth check --provider <id> --json --no-refresh`：pi 自己判断是否可用（不刷新令牌、不输出凭据）。 */
    suspend fun check(provider: String, env: Map<String, String> = emptyMap()): Outcome<AuthCheck> = withContext(io) {
        try {
            val r = runtime.exec(listOf("pi", "auth", "check", "--provider", provider, "--json", "--no-refresh"), env = env, timeoutMs = 30_000)
            Outcome.Ok(AuthCheck.parse(r.stdout))
        } catch (e: Exception) {
            Outcome.Err(ErrCode.UNKNOWN, "检查登录状态失败：${e.message}", e)
        }
    }

    data class AuthCheck(val ready: Boolean, val status: String, val authType: String?) {
        companion object {
            fun parse(stdout: String): AuthCheck {
                val line = stdout.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") }
                    ?: return AuthCheck(false, "unknown", null)
                val o = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return AuthCheck(false, "unknown", null)
                val status = (o["status"] as? JsonPrimitive)?.content ?: "unknown"
                return AuthCheck(status == "ready", status, (o["authType"] as? JsonPrimitive)?.content)
            }
        }
    }
}

/** [GuestProcess] → [LineProcess]。 */
class GuestLineProcess(private val p: GuestProcess, private val io: CoroutineDispatcher) : LineProcess {
    override val lines: Flow<String> = callbackFlow {
        val job = launch(io) {
            p.stdout.bufferedReader(Charsets.UTF_8).useLines { seq -> seq.forEach { trySend(it) } }
            close()
        }
        // stderr 要读掉，否则缓冲区满了会卡住进程；内容可能含调试信息，不外传
        val err = launch(io) { runCatching { p.stderr.readBytes() } }
        awaitClose { job.cancel(); err.cancel() }
    }.flowOn(io)

    override suspend fun send(line: String) = withContext(io) {
        p.stdin.write((line + "\n").toByteArray(Charsets.UTF_8)); p.stdin.flush()
    }

    override suspend fun stop() = p.stop(2_000)
}
