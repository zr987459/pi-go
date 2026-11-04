package io.github.zr987459.pigo.feature.chat

import io.github.zr987459.pigo.contract.session.UiForm
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESIGN-r3 §7.2 第 1 条（FormSwitchContractTest）中 ChatController 的部分：公开方法没有 [UiForm] 参数。
 * core/session 不依赖 feature/chat，所以这条放在 feature/chat；SessionHost 部分见 core/session 的 FormSwitchContractTest。
 */
class ChatControllerNoFormParamTest {
    @Test
    fun publicApiHasNoFormParameter() {
        val bad = ChatController::class.java.methods.filter { m -> m.parameterTypes.any { UiForm::class.java.isAssignableFrom(it) } }
        assertTrue("ChatController 有形态参数：$bad", bad.isEmpty())
        val ctor = ChatController::class.java.constructors.filter { c -> c.parameterTypes.any { UiForm::class.java.isAssignableFrom(it) } }
        assertTrue("ChatController 构造器有形态参数：$ctor", ctor.isEmpty())
    }
}
