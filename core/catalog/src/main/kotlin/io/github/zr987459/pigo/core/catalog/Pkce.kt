package io.github.zr987459.pigo.core.catalog

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE（RFC 7636）与授权回调的宿主侧处理。
 *
 * 真正的令牌交换由容器里 pi 自己的登录流程完成（`pigo-auth.mjs`），宿主只负责：
 * - 从 Custom Tab 拦截到的回调地址或用户粘贴的内容里取出 `code` / `state`（与 pi 的 `parseAuthorizationInput` 同规则）；
 * - 在转交给容器之前校验 `state` 与授权地址里的一致（防 CSRF / 串号），不一致直接拒绝；
 * - 生成 verifier/challenge 供自测与将来宿主直连的服务商使用。
 */
object Pkce {
    data class Pair(val verifier: String, val challenge: String)

    data class AuthInput(val code: String?, val state: String?)

    class StateMismatchException : IllegalArgumentException("登录校验失败（state 不一致），请重新登录")

    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun generate(random: SecureRandom = SecureRandom()): Pair {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val verifier = b64.encodeToString(bytes)
        return Pair(verifier, challengeOf(verifier))
    }

    fun challengeOf(verifier: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** 与 pi-ai `parseAuthorizationInput` 相同：完整 URL、`code#state`、`code=…&state=…`、或只有 code。 */
    fun parseAuthorizationInput(input: String): AuthInput {
        val v = input.trim()
        if (v.isEmpty()) return AuthInput(null, null)
        if (v.startsWith("http://") || v.startsWith("https://")) {
            val q = runCatching { URI(v).rawQuery }.getOrNull()
            if (q != null) {
                val m = query(q)
                return AuthInput(m["code"], m["state"])
            }
        }
        if ('#' in v) {
            val (c, s) = v.split('#', limit = 2)
            return AuthInput(c, s)
        }
        if ("code=" in v) {
            val m = query(v)
            return AuthInput(m["code"], m["state"])
        }
        return AuthInput(v, null)
    }

    /** 授权地址里的 `state` 参数。 */
    fun stateOf(authUrl: String): String? = runCatching { URI(authUrl).rawQuery }.getOrNull()?.let { query(it)["state"] }

    /** 粘贴内容 / 回调地址带了 state 时必须与授权地址一致；没带 state（只粘了 code）放行，由 pi 用自己的 state 交换。 */
    fun validate(authUrl: String, input: String): AuthInput {
        val parsed = parseAuthorizationInput(input)
        val expected = stateOf(authUrl)
        if (parsed.state != null && expected != null && parsed.state != expected) throw StateMismatchException()
        require(!parsed.code.isNullOrBlank()) { "没有找到授权码，请粘贴登录完成后页面上的代码或地址" }
        return parsed
    }

    /** 回调地址是否指向本机登录回调（Custom Tab 拦截用）：`http://localhost:<port>/…` 或 `127.0.0.1`。 */
    fun isLoopbackCallback(url: String, redirectUri: String?): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        if (u.scheme != "http" || (u.host != "localhost" && u.host != "127.0.0.1")) return false
        val r = redirectUri?.let { runCatching { URI(it) }.getOrNull() } ?: return u.path?.contains("callback") == true
        return u.port == r.port && u.path == r.path
    }

    private fun query(q: String): Map<String, String> = q.split('&').mapNotNull { kv ->
        val i = kv.indexOf('=')
        if (i <= 0) null else URLDecoder.decode(kv.substring(0, i), "UTF-8") to URLDecoder.decode(kv.substring(i + 1), "UTF-8")
    }.toMap()
}
