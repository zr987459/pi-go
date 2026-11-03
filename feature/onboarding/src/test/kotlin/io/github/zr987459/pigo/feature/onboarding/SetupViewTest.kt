package io.github.zr987459.pigo.feature.onboarding

import io.github.zr987459.pigo.contract.InstallProgress
import io.github.zr987459.pigo.contract.InstallStep
import io.github.zr987459.pigo.contract.Phase
import io.github.zr987459.pigo.feature.onboarding.OnboardingViewModel.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** dsh-Go 样式的安装引导：哪种状态显示哪一屏、环形进度的整体百分比。 */
class SetupViewTest {
    @Test fun viewFollowsModeAndPhase() {
        assertEquals(SetupView.CONFIRM, setupViewOf(Mode.IDLE, Phase.NOT_INSTALLED))
        assertEquals(SetupView.CONFIRM, setupViewOf(Mode.ABORTED, Phase.BROKEN))
        assertEquals(SetupView.CONFIRM, setupViewOf(Mode.IDLE, Phase.BROKEN))
        assertEquals(SetupView.INSTALLING, setupViewOf(Mode.RUNNING, Phase.INSTALLING))
        assertEquals(SetupView.INSTALLING, setupViewOf(Mode.RUNNING, Phase.READY)) // 安装中不被旧的 READY 打断
        assertEquals(SetupView.FAILED, setupViewOf(Mode.FAILED, Phase.BROKEN))
        assertEquals(SetupView.DONE, setupViewOf(Mode.DONE, Phase.READY))
        assertEquals(SetupView.DONE, setupViewOf(Mode.IDLE, Phase.READY)) // 已装好直接显示「进入聊天」
    }

    @Test fun overallFractionSpreadsStepsEvenly() {
        assertNull(overallFraction(null))
        val n = InstallStep.entries.size.toFloat()
        assertEquals(0f, overallFraction(InstallProgress(InstallStep.PREFLIGHT, 0, null, ""))!!, 1e-6f)
        assertEquals(0.5f / n, overallFraction(InstallProgress(InstallStep.PREFLIGHT, 50, null, ""))!!, 1e-6f)
        assertEquals(1f, overallFraction(InstallProgress(InstallStep.VERIFY, 100, null, ""))!!, 1e-6f)
        assertEquals(1f, overallFraction(InstallProgress(InstallStep.VERIFY, 250, null, ""))!!, 1e-6f) // 超出范围也不越界
    }
}
