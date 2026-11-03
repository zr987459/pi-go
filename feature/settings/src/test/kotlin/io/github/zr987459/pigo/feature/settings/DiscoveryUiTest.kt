package io.github.zr987459.pigo.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 审查第 2 轮 Nit：改地址 / Key 后，旧地址的模型列表不能再点选。 */
class DiscoveryUiTest {
    @Test fun inputChangeClearsOldModelsAndMessage() {
        val found = DiscoveryUi().started().succeeded(listOf("qwen-plus", "qwen-max")).finished()
        assertEquals(listOf("qwen-plus", "qwen-max"), found.discovered)
        assertTrue(found.message!!.contains("找到 2 个模型"))

        val changed = found.inputChanged()
        assertTrue("旧地址的模型不能再显示/点选", changed.discovered.isEmpty())
        assertNull("旧提示也清掉", changed.message)
        assertFalse(changed.running)
    }

    @Test fun inputChangeWhileRunningResetsButton() {
        val running = DiscoveryUi().started()
        assertTrue(running.running)
        assertFalse(running.inputChanged().running)
    }

    @Test fun failureClearsListAndExplains() {
        val s = DiscoveryUi().succeeded(listOf("a")).failed("")
        assertTrue(s.discovered.isEmpty())
        assertTrue(s.message!!.contains("未知错误"))
        assertTrue(DiscoveryUi().failed("401").message!!.contains("401"))
    }

    @Test fun startClearsMessageButKeepsListUntilResult() {
        val s = DiscoveryUi().succeeded(listOf("a")).started()
        assertNull(s.message)
        assertEquals(listOf("a"), s.discovered)
    }
}
