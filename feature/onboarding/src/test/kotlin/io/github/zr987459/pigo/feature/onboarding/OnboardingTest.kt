package io.github.zr987459.pigo.feature.onboarding

import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.GuestProcess
import io.github.zr987459.pigo.contract.GuestSpec
import io.github.zr987459.pigo.contract.InstallProgress
import io.github.zr987459.pigo.contract.InstallStep
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.Phase
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.RuntimeStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** 可控的 RuntimeManager：hang = 卡在 PI 步骤直到取消；fail = 抛错；其他 = 成功。 */
    private class Scripted(var behaviour: String) : RuntimeManager {
        private val _status = MutableStateFlow(RuntimeStatus(Phase.NOT_INSTALLED, null, null, null, null))
        override val status: StateFlow<RuntimeStatus> = _status
        var collected = 0
        override fun install(): Flow<InstallProgress> = flow {
            collected++
            emit(InstallProgress(InstallStep.ROOTFS, 20, null, "正在安装 Ubuntu"))
            when (behaviour) {
                "hang" -> { emit(InstallProgress(InstallStep.PI, 80, null, "正在安装 pi")); awaitCancellation() }
                "fail" -> throw IllegalStateException("所有镜像都下载失败（node.tar.gz），请换网络或稍后再试。")
                else -> {
                    emit(InstallProgress(InstallStep.VERIFY, 100, null, "安装完成"))
                    _status.value = RuntimeStatus(Phase.READY, "24.04.5", "v24.22.2", "1.5.0", null)
                }
            }
        }
        override suspend fun repair(): Outcome<Unit> = Outcome.Ok(Unit)
        override suspend fun upgradePi(version: String): Outcome<String> = Outcome.Ok(version)
        override fun start(spec: GuestSpec): GuestProcess = error("unused")
        override suspend fun exec(cmd: List<String>, env: Map<String, String>, timeoutMs: Long) = ExecResult(0, "", "")
        override fun hostFile(guestPath: String) = File(guestPath)
    }

    @Test fun preflightBlocksBelow3GbWithChineseReason() {
        val p = Preflight.evaluate(listOf("arm64-v8a"), 2L * 1024 * 1024 * 1024, true)
        assertFalse(p.canStart)
        assertTrue(p.blockingReason!!.startsWith("可用空间不足"))
        val ok = Preflight.evaluate(listOf("x86_64"), 5L * 1024 * 1024 * 1024, true)
        assertTrue(ok.canStart)
        assertNull(ok.blockingReason)
        assertEquals(3, ok.checks.size)
        assertFalse(Preflight.evaluate(listOf("armeabi-v7a"), 5L shl 30, true).canStart)
        assertFalse(Preflight.evaluate(listOf("arm64-v8a"), 5L shl 30, false).canStart)
    }

    @Test fun backDuringInstallAsksAndCancelKeepsInstalling() = runTest(dispatcher) {
        val rt = Scripted("hang")
        val vm = OnboardingViewModel(rt)
        vm.start()
        advanceUntilIdle()
        assertEquals(OnboardingViewModel.Mode.RUNNING, vm.state.value.mode)

        assertTrue("安装中返回键应被拦截", vm.onBack())
        assertTrue(vm.state.value.confirmingAbort)
        vm.dismissAbort()
        advanceUntilIdle()
        assertFalse(vm.state.value.confirmingAbort)
        assertTrue("取消后安装继续", vm.isRunning)
        assertEquals(InstallStep.PI, vm.state.value.progress!!.step)

        vm.onBack()
        vm.confirmAbort()
        advanceUntilIdle()
        assertEquals(OnboardingViewModel.Mode.ABORTED, vm.state.value.mode)
        assertFalse(vm.isRunning)
        assertFalse("未在安装时返回键不拦截", vm.onBack())

        rt.behaviour = "ok"
        vm.start()
        advanceUntilIdle()
        assertEquals(OnboardingViewModel.Mode.DONE, vm.state.value.mode)
        assertEquals(2, rt.collected)
    }

    @Test fun failureShowsMessageAndRetryWorks() = runTest(dispatcher) {
        val rt = Scripted("fail")
        val vm = OnboardingViewModel(rt)
        vm.start()
        advanceUntilIdle()
        assertEquals(OnboardingViewModel.Mode.FAILED, vm.state.value.mode)
        assertTrue(vm.state.value.error!!.contains("请换网络或稍后再试"))
        rt.behaviour = "ok"
        vm.start()
        advanceUntilIdle()
        assertEquals(OnboardingViewModel.Mode.DONE, vm.state.value.mode)
        assertNull(vm.state.value.error)
    }

    @Test fun startIsIgnoredWhileRunning() = runTest(dispatcher) {
        val rt = Scripted("hang")
        val vm = OnboardingViewModel(rt)
        vm.start(); vm.start()
        advanceUntilIdle()
        assertEquals(1, rt.collected)
        vm.confirmAbort()
        advanceUntilIdle()
    }

    @Test fun labels() {
        assertEquals("安装 pi", stepLabel(InstallStep.PI))
        assertEquals("1.5 MB/s", formatSpeed(1572864))
        assertEquals("120 KB/s", formatSpeed(122880))
    }
}
