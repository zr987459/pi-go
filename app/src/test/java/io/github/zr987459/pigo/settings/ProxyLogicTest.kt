package io.github.zr987459.pigo.settings

import io.github.zr987459.pigo.contract.NetworkSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PR #21 审查第 1 轮 Important 2：设置 › 模型 › 网络代理 可查看 / 修改 / 关闭。 */
class ProxyLogicTest {
    @Test
    fun viewEditAndTurnOff() {
        assertEquals("未使用", ProxyLogic.summary(NetworkSettings()))
        val on = ProxyLogic.edited("  http://127.0.0.1:7890 ", "")
        assertEquals(NetworkSettings("http://127.0.0.1:7890", ProxyLogic.DEFAULT_NO_PROXY), on)
        assertEquals("已启用：http://127.0.0.1:7890", ProxyLogic.summary(on))
        assertEquals("代理已保存，重启 pi 后生效", ProxyLogic.savedMessage(on))
        val off = ProxyLogic.off(on.copy(noProxy = "localhost,10.0.0.0/8"))
        assertNull(off.proxyUrl)
        assertEquals("localhost,10.0.0.0/8", off.noProxy)
        assertEquals("已关闭代理，重启 pi 后生效", ProxyLogic.savedMessage(off))
        assertNull(ProxyLogic.edited("   ", "x").proxyUrl)
    }
}
