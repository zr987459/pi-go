package io.github.zr987459.pigo.feature.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 审查 R1 #2：自动获取模型列表时，旧请求晚到的结果 / 取消异常不能覆盖新结果。 */
@OptIn(ExperimentalCoroutinesApi::class)
class LatestOnlyTest {
    private val asError: (Throwable) -> String = { "失败：${it.message}" }

    @Test fun staleSlowRequestDoesNotOverwriteNewerResult() = runTest {
        val gate = LatestOnly()
        val slow = CompletableDeferred<String>()
        val t1 = gate.begin()
        val first = async { gate.run(t1, asError) { slow.await() } }
        runCurrent()
        // 用户改了地址：旧请求作废，新请求很快成功
        gate.invalidate()
        val t2 = gate.begin()
        assertEquals("新模型列表", gate.run(t2, asError) { "新模型列表" })
        // 旧请求这时才超时返回（失败），结果被丢弃
        slow.completeExceptionally(RuntimeException("timeout"))
        assertNull(first.await())
        assertTrue(gate.isLatest(t2))
        assertFalse(gate.isLatest(t1))
    }

    @Test fun cancellationIsRethrownNotShownAsFailure() = runTest {
        val gate = LatestOnly()
        val blocker = CompletableDeferred<String>()
        var shown: String? = "未写入"
        val token = gate.begin()
        val job = launch {
            try {
                shown = gate.run(token, asError) { blocker.await() }
            } catch (e: CancellationException) {
                // 取消：调用方什么都不写
            }
        }
        runCurrent()
        job.cancel()
        runCurrent()
        assertEquals("未写入", shown)
        assertTrue(job.isCancelled)
    }

    @Test fun ordinaryFailureOfLatestRequestIsReported() = runTest {
        val gate = LatestOnly()
        val token = gate.begin()
        assertEquals("失败：401", gate.run(token, asError) { throw IllegalStateException("401") })
    }
}
