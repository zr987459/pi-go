package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.contract.session.UiForm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * 硬性要求 ② 的 JVM 契约（DESIGN-r3 §7.2 第 1 条）：`SessionHost`（及实现、动作表）的公开方法没有 [UiForm] 参数；
 * 改 `form` 前后命令日志不新增任何命令。`ChatController` 的同类断言在 feature/chat 的 `ChatControllerNoFormParamTest`
 * （core/session 不依赖 feature/chat，见 PR 描述）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FormSwitchContractTest {
    private fun formParams(c: Class<*>): List<Method> =
        c.methods.filter { m -> m.parameterTypes.any { UiForm::class.java.isAssignableFrom(it) } }

    @Test
    fun publicApiHasNoFormParameter() {
        for (c in listOf(SessionHost::class.java, SessionHostImpl::class.java, PiActionsImpl::class.java, SessionCatalogImpl::class.java, PiSettingsImpl::class.java)) {
            val bad = formParams(c)
            assertTrue("${c.simpleName} 有形态参数：$bad", bad.isEmpty())
        }
    }

    @Test
    fun changingFormSendsNoCommand() = runTest(UnconfinedTestDispatcher()) {
        val (host, engine) = testHost(backgroundScope)
        val before = engine.commands.value
        repeat(10) { host.form.value = if (host.form.value == UiForm.CALM) UiForm.TERM else UiForm.CALM }
        host.runAction("form.toggle")
        assertEquals(before, engine.commands.value)
        assertEquals(1, engine.startCount)
        assertEquals(0, engine.restartCount)
    }
}
