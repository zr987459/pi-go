package io.github.zr987459.pigo.di

import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** extras 切 REAL 后：extras 的命令同样会按需启动引擎，冷启动预热结束前要等闸门（与 GatedEngineClient 同一个）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatedEngineExtrasTest {
    @Test
    fun extrasCommandsWaitForWarmUp() = runTest {
        val inner = FakeEngineExtras()
        val gate = WarmUpGate()
        val ex = GatedEngineExtras(inner, gate)
        launch { ex.commands(false) }
        launch { ex.usage() }
        launch { ex.exportHtml(null) }
        launch { ex.cycleModelResult() }
        launch { ex.bash("ls").toList() }
        runCurrent()
        assertTrue("预热结束前不得转发：${inner.calls}", inner.calls.isEmpty())
        gate.release()
        advanceUntilIdle()
        assertTrue("commands 放行", inner.calls.any { it.startsWith("commands") })
        assertTrue("其余命令都放行：${inner.calls}", inner.calls.size >= 5)
    }

    @Test
    fun bypassWhenRuntimeNotReady() = runTest {
        val inner = FakeEngineExtras()
        val ex = GatedEngineExtras(inner, WarmUpGate(bypass = { true }))
        ex.commands(true)
        assertEquals(listOf("commands:true"), inner.calls.filter { it.startsWith("commands") })
    }
}
