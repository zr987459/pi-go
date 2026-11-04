package io.github.zr987459.pigo.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

/** PR #19 审查第 2 轮 Nit 6：能力字段三态（未指定 / 开 / 关），显式关闭不会被写成「未指定」。 */
class CapabilityTriStateTest {
    private fun cap(explicit: Boolean?, inferred: Boolean, old: Boolean?) = RealCustomProviderStore.capability(explicit, inferred, old)

    @Test fun explicitOffIsWrittenEvenWhenFileHadNothing() {
        assertEquals(false, cap(explicit = false, inferred = false, old = null))
    }

    @Test fun explicitOnAndOffWin() {
        assertEquals(true, cap(true, false, false))
        assertEquals(false, cap(false, true, true))
    }

    @Test fun unspecifiedKeepsFileSemantics() {
        assertEquals(null, cap(null, false, null)) // 原来没写、也没指定：不写
        assertEquals(false, cap(null, false, false)) // 原来写了 false：保持
        assertEquals(false, cap(null, false, true)) // 推断为关且原来有值：照写推断值
        assertEquals(true, cap(null, true, null))
    }
}
