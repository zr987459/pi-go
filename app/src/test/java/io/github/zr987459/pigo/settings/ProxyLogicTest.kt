package io.github.zr987459.pigo.settings

import io.github.zr987459.pigo.contract.NetworkSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals("代理已保存，点下方「立即重启 pi」生效", ProxyLogic.savedMessage(on))
        val off = ProxyLogic.off(on.copy(noProxy = "localhost,10.0.0.0/8"))
        assertNull(off.proxyUrl)
        assertEquals("localhost,10.0.0.0/8", off.noProxy)
        assertEquals("已关闭代理，点下方「立即重启 pi」生效", ProxyLogic.savedMessage(off))
        assertNull(ProxyLogic.edited("   ", "x").proxyUrl)
    }

    /** 第 2 轮 Nit 4：「重启 pi 后生效」要给能用的入口——有重启动作时指向本页「立即重启 pi」，没有时指向已有的 ⌘ ›「停止引擎」。 */
    @Test
    fun savedMessagePointsToUsableRestartEntry() {
        val on = ProxyLogic.edited("http://127.0.0.1:7890", "")
        assertTrue(ProxyLogic.savedMessage(on, canRestart = true).contains("立即重启 pi"))
        assertTrue(ProxyLogic.savedMessage(on, canRestart = false).contains("停止引擎"))
        assertFalse(ProxyLogic.savedMessage(on, canRestart = false).contains("立即重启 pi"))
        assertEquals("pi 已重启，代理设置已生效", ProxyLogic.restartMessage(true))
        assertEquals("重启失败：引擎没有运行", ProxyLogic.restartMessage(false, "引擎没有运行"))
    }

    @Test
    fun restartEntryFollowsAppliedVsSaved() {
        val on = NetworkSettings("http://127.0.0.1:7890")
        val off = NetworkSettings(null)
        // 知道生效值：不一致就一直显示，与「刚保存过」无关
        assertTrue(ProxyLogic.showRestart(applied = off, saved = on, justSaved = false))
        assertFalse(ProxyLogic.showRestart(applied = on, saved = on, justSaved = true))
        assertFalse(ProxyLogic.showRestart(applied = NetworkSettings(" http://127.0.0.1:7890 "), saved = on, justSaved = false))
        // 无代理时例外列表不同不算不一致
        assertFalse(ProxyLogic.showRestart(applied = NetworkSettings(null, "a"), saved = NetworkSettings(null, "b"), justSaved = false))
        assertTrue(ProxyLogic.showRestart(applied = NetworkSettings("http://p:1", "a"), saved = NetworkSettings("http://p:1", "b"), justSaved = false))
        // 不知道生效值：退回刚保存过
        assertTrue(ProxyLogic.showRestart(applied = null, saved = on, justSaved = true))
        assertFalse(ProxyLogic.showRestart(applied = null, saved = on, justSaved = false))
    }
}
