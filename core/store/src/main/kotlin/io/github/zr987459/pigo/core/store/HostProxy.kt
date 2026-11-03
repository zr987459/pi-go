package io.github.zr987459.pigo.core.store

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * 宿主侧 HTTP 请求（如「获取模型列表」）用 M9 里配置的代理（审查第 1 轮 Nit：之前是直连）。
 * 支持 `http://`、`https://`（按 HTTP 代理连）与 `socks5://`、`socks://`；代理地址里的账号密码不支持（HttpURLConnection
 * 没有通用的代理鉴权），这种情况退回直连并由调用方照常报网络错误。[noProxy] 规则同 pi：逗号分隔，
 * 精确主机名、`.后缀`/`*.后缀` 或后缀匹配。
 */
object HostProxy {
    fun select(settings: PiProviderConfigStore.ProxySettings?, targetUrl: String): Proxy {
        if (settings == null || settings.url.isBlank()) return Proxy.NO_PROXY
        val target = runCatching { URI(targetUrl).host }.getOrNull()?.lowercase() ?: return Proxy.NO_PROXY
        if (bypass(target, settings.noProxy)) return Proxy.NO_PROXY
        return parse(settings.url) ?: Proxy.NO_PROXY
    }

    fun parse(url: String): Proxy? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val host = u.host ?: return null
        if (u.userInfo != null) return null
        return when (u.scheme?.lowercase()) {
            "http", "https" -> Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, if (u.port > 0) u.port else 80))
            "socks", "socks5", "socks5h" -> Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, if (u.port > 0) u.port else 1080))
            else -> null
        }
    }

    fun bypass(host: String, noProxy: String): Boolean = noProxy.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { rule ->
        when {
            rule == "*" -> true
            rule.startsWith("*.") -> host.endsWith(rule.substring(1))
            rule.startsWith(".") -> host.endsWith(rule) || host == rule.substring(1)
            else -> host == rule || host.endsWith(".$rule")
        }
    }
}
