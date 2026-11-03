package io.github.zr987459.pigo.core.store

import java.net.InetSocketAddress
import java.net.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 审查第 1 轮 Nit：宿主「获取模型列表」走 M9 的代理。 */
class HostProxyTest {
    private fun s(url: String, no: String = PiProviderConfigStore.DEFAULT_NO_PROXY) = PiProviderConfigStore.ProxySettings(url, no)

    @Test fun httpProxyUsedForRemoteHost() {
        val p = HostProxy.select(s("http://127.0.0.1:7890"), "https://api.openai.com/v1/models")
        assertEquals(Proxy.Type.HTTP, p.type())
        val a = p.address() as InetSocketAddress
        assertEquals("127.0.0.1", a.hostString)
        assertEquals(7890, a.port)
    }

    @Test fun socksAndBypassAndNone() {
        assertEquals(Proxy.Type.SOCKS, HostProxy.select(s("socks5://10.0.0.2:1080"), "https://x.ai/v1/models").type())
        assertSame(Proxy.NO_PROXY, HostProxy.select(null, "https://x.ai/v1/models"))
        assertSame(Proxy.NO_PROXY, HostProxy.select(s("http://127.0.0.1:7890"), "http://localhost:11434/v1/models"))
        assertSame(Proxy.NO_PROXY, HostProxy.select(s("http://127.0.0.1:7890", "*.aliyuncs.com"), "https://dashscope.aliyuncs.com/compatible-mode/v1/models"))
        assertSame(Proxy.NO_PROXY, HostProxy.select(s("http://u:p@127.0.0.1:7890"), "https://x.ai/v1/models"))
    }
}
