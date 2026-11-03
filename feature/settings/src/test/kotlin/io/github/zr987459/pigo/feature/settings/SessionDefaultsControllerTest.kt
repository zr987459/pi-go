package io.github.zr987459.pigo.feature.settings

import io.github.zr987459.pigo.contract.QueueMode
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.feature.settings.session.SessionDefaultsController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M8（审查第 1 轮 Important 4）：四个开关调用 extras 的对应命令，状态随之更新。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionDefaultsControllerTest {
    @Test
    fun togglesCallExtrasAndUpdateState() = runTest(UnconfinedTestDispatcher()) {
        val extras = FakeEngineExtras()
        val c = SessionDefaultsController(extras, this)
        c.load().join()
        c.setAutoCompaction(false).join()
        c.setAutoRetry(true).join()
        c.setSteeringMode(QueueMode.ALL).join()
        c.setFollowUpMode(QueueMode.ALL).join()
        val s = c.state.value.settings
        assertEquals(false, s.autoCompaction)
        assertEquals(true, s.autoRetry)
        assertEquals(QueueMode.ALL, s.steeringMode)
        assertEquals(QueueMode.ALL, s.followUpMode)
        val after = (extras.sessionSettings() as io.github.zr987459.pigo.contract.Outcome.Ok).value
        assertEquals(s, after)
        assertTrue(extras.calls.toString(), extras.calls.any { it.contains("auto_compaction") || it.contains("AutoCompaction") })
    }
}
