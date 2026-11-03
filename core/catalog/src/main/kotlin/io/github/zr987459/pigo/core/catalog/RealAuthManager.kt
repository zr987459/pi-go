package io.github.zr987459.pigo.core.catalog

import io.github.zr987459.pigo.contract.AuthManager
import io.github.zr987459.pigo.contract.AuthState
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.OAuthStep
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.core.store.PiProviderConfigStore
import io.github.zr987459.pigo.core.store.ProviderSpecs
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * [AuthManager] 的实现（M1、M3）。Key 走 [PiProviderConfigStore]（Keystore + 进程环境），
 * OAuth 走 [AuthService]（容器内 pi 的登录流程，凭据由 pi 写 auth.json）。
 */
class RealAuthManager(
    private val config: PiProviderConfigStore,
    private val auth: AuthService,
    /** 目录里的服务商（判断是否内置）；默认取 [ProviderSpecs]。 */
    private val knownProvider: (String) -> Boolean = { ProviderSpecs.get(it) != null },
) : AuthManager {

    private val states = MutableStateFlow<Map<String, AuthState>?>(null)
    private val sessions = ConcurrentHashMap<String, OAuthLoginSession>()

    private suspend fun recompute(): Map<String, AuthState> {
        val oauth = auth.refreshOAuthStatus()
        val out = LinkedHashMap<String, AuthState>()
        for (spec in ProviderSpecs.ALL) {
            val st = keyState(spec.provider)
            out[spec.provider] = if (spec.provider in oauth) AuthState.OAuth() else st
        }
        for ((id, _) in config.customProviders()) out[id] = keyState(id)
        for (p in oauth) if (p !in out) out[p] = AuthState.OAuth()
        states.value = out
        return out
    }

    private suspend fun keyState(provider: String): AuthState {
        val custom = config.customProviders()[provider]
        if (custom != null) {
            return if (provider in config.configured()) AuthState.ApiKey(null) else AuthState.None
        }
        val spec = ProviderSpecs.get(provider) ?: return AuthState.None
        if (spec.keyEnv == null || !config.isBuiltInConfigured(provider)) return AuthState.None
        val fields = config.providerFields(provider)
        return if (spec.extra.isEmpty()) {
            AuthState.ApiKey(fields[spec.keyEnv]?.takeLast(4)?.takeIf { it.none { c -> c == '•' } })
        } else {
            AuthState.CloudEnv(fields.filterValues { it != null }.keys)
        }
    }

    override fun observe(): Flow<Map<String, AuthState>> = flow {
        if (states.value == null) recompute()
        emitAll(states)
    }.map { it.orEmpty() }.distinctUntilChanged()

    override suspend fun state(provider: String): AuthState = (states.value ?: recompute())[provider] ?: AuthState.None

    override suspend fun setApiKey(provider: String, rawKey: String?, extra: Map<String, String>): Outcome<Unit> = try {
        val custom = config.customProviders()[provider]
        if (custom != null) {
            config.upsertCustomProvider(custom.id, custom.baseUrl, custom.api, rawKey.orEmpty(), custom.models)
        } else {
            val spec = ProviderSpecs.get(provider) ?: return Outcome.Err(ErrCode.PROVIDER, "不认识的服务商：$provider")
            val keyEnv = spec.keyEnv ?: return Outcome.Err(ErrCode.PROVIDER, "${spec.label} 只能用账号登录")
            val values = LinkedHashMap(extra)
            if (rawKey != null) values[keyEnv] = rawKey
            config.setProviderFields(provider, values)
        }
        recompute()
        Outcome.Ok(Unit)
    } catch (e: IllegalArgumentException) {
        Outcome.Err(ErrCode.PROVIDER, e.message ?: "保存失败")
    } catch (e: Exception) {
        Outcome.Err(ErrCode.UNKNOWN, "保存失败：${e.message}", e)
    }

    override suspend fun clear(provider: String): Outcome<Unit> {
        val wasOAuth = provider in auth.refreshOAuthStatus()
        if (knownProvider(provider)) config.clearProvider(provider)
        val r = if (wasOAuth) auth.logout(provider) else Outcome.Ok(Unit)
        recompute()
        return r
    }

    override fun login(provider: String): Flow<OAuthStep> = channelFlow {
        send(OAuthStep.Starting)
        val proxy = config.env().filterKeys { it in ProviderSpecs.PROXY_ENVS || it == "NODE_USE_ENV_PROXY" }
        val session = when (val r = auth.startOAuth(provider, proxy)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> { send(OAuthStep.Failed(r.code, r.message)); return@channelFlow }
        }
        sessions[provider]?.let { old -> launch { old.cancel() } }
        sessions[provider] = session
        var lastUrl: String? = null
        try {
            session.state.takeWhile { st ->
                when (st) {
                    is OAuthLoginSession.State.Starting -> Unit
                    is OAuthLoginSession.State.AwaitingBrowser -> {
                        if (st.url != lastUrl) {
                            lastUrl = st.url
                            send(OAuthStep.OpenUrl(st.url, st.instructions?.let(::zh), redirectPrefix(st.url)))
                        }
                        st.pendingPrompt?.let { send(OAuthStep.NeedCode(zh(it.message))) }
                    }
                    is OAuthLoginSession.State.AwaitingDeviceCode ->
                        send(OAuthStep.DeviceCode(st.userCode, st.verificationUri, st.expiresInSeconds?.toInt()))
                    is OAuthLoginSession.State.AwaitingInput ->
                        if (st.prompt.kind == "select") send(OAuthStep.Select(zh(st.prompt.message), st.prompt.options.map { it.label }))
                        else send(OAuthStep.NeedCode(zh(st.prompt.message)))
                    is OAuthLoginSession.State.Working -> send(OAuthStep.Progress(zh(st.message)))
                    is OAuthLoginSession.State.Succeeded -> {
                        recompute()
                        send(OAuthStep.Done(AuthState.OAuth()))
                    }
                    is OAuthLoginSession.State.Failed ->
                        send(OAuthStep.Failed(if (st.cancelled) ErrCode.CANCELLED else ErrCode.PROVIDER, zh(st.message)))
                }
                st !is OAuthLoginSession.State.Succeeded && st !is OAuthLoginSession.State.Failed
            }.collect {}
        } finally {
            sessions.remove(provider, session)
            if (session.state.value !is OAuthLoginSession.State.Succeeded && session.state.value !is OAuthLoginSession.State.Failed) {
                withContext(NonCancellable) { session.cancel() } // 收集方取消 = 取消登录，并结束容器里的脚本
            }
        }
    }

    override suspend fun submitCode(provider: String, code: String) {
        val s = sessions[provider] ?: return
        val id = when (val st = s.state.value) {
            is OAuthLoginSession.State.AwaitingInput -> st.prompt.id
            is OAuthLoginSession.State.AwaitingBrowser -> st.pendingPrompt?.id
            else -> null
        } ?: return
        s.answer(id, code.trim())
    }

    override suspend fun submitSelection(provider: String, value: String) {
        val s = sessions[provider] ?: return
        val p = (s.state.value as? OAuthLoginSession.State.AwaitingInput)?.prompt ?: return
        val opt = p.options.firstOrNull { it.label == value || it.id == value }?.id ?: value
        s.answer(p.id, opt)
    }

    /** 在容器里跑 `pi auth check`。 */
    override suspend fun check(provider: String): Outcome<AuthState> =
        when (val r = auth.check(provider, config.env())) {
            is Outcome.Ok -> Outcome.Ok(
                when {
                    !r.value.ready -> if (r.value.status == "unknown") AuthState.Error("无法确认登录状态") else AuthState.None
                    r.value.authType == "oauth" -> AuthState.OAuth()
                    else -> keyState(provider).takeIf { it.configured } ?: AuthState.ApiKey(null)
                },
            )
            is Outcome.Err -> r
        }

    companion object {
        /** 授权地址里的本机回调（`redirect_uri=http://localhost:…`）前缀：宿主可拦截后自动提交。 */
        fun redirectPrefix(authUrl: String): String? {
            val q = runCatching { URI(authUrl).rawQuery }.getOrNull() ?: return null
            val ru = q.split('&').firstOrNull { it.startsWith("redirect_uri=") }?.substringAfter('=') ?: return null
            val decoded = java.net.URLDecoder.decode(ru, "UTF-8")
            return decoded.takeIf { Pkce.isLoopbackCallback(it, it) }
        }

        private val ZH = listOf(
            "GitHub Enterprise URL/domain (blank for github.com)" to "GitHub 企业版域名（普通 github.com 账号留空）",
            "Complete login in your browser, or paste the authorization code / redirect URL here:" to "在浏览器里完成登录；如果没有自动返回，把授权码或最终地址粘贴到这里：",
            "Paste the code Anthropic shows after you sign in:" to "粘贴 Anthropic 登录后显示的代码：",
            "Exchanging authorization code for tokens..." to "正在换取登录凭据…",
            "Enabling models..." to "正在启用模型…",
            "Waiting for authentication..." to "等待授权…",
            "Login cancelled" to "已取消登录",
            "Device flow timed out" to "设备码已过期，请重新登录",
        )

        /** pi 的英文提示 → 中文；不认识的保留原文（已脱敏）。 */
        fun zh(s: String): String = ZH.firstOrNull { s.startsWith(it.first) }?.second ?: OAuthProtocol.clean(s)
    }
}
