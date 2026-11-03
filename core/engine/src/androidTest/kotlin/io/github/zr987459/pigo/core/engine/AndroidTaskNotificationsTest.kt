package io.github.zr987459.pigo.core.engine

import android.app.Application
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zr987459.pigo.contract.RunOutcome
import io.github.zr987459.pigo.contract.RunOutcomeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** N1 仪器测试：真实 [AndroidTaskNotifications] / [ActivityVisibility]（审查第 1 轮 Important#3）。 */
@RunWith(AndroidJUnit4::class)
class AndroidTaskNotificationsTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private fun outcome(kind: RunOutcomeKind) =
        RunOutcome(kind, "/root/.pi/agent/sessions/a.jsonl", "重构", message = null, lastTextPreview = "改好了", atMillis = 1, cwd = "/root/p")

    @Test
    fun channelIsCreated() {
        AndroidTaskNotifications(app)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationManagerCompat.from(app).getNotificationChannelCompat(TaskNotifier.CHANNEL_ID)
            assertNotNull("pigo-tasks 通道已建", ch)
        }
    }

    @Test
    fun postingWithoutPermissionDoesNotCrash() {
        // Android 13+ 测试进程默认未授予 POST_NOTIFICATIONS：静默跳过，不抛异常。
        val n = TaskNotifier({ false }, AndroidTaskNotifications(app))
        val posted = n.onOutcome(outcome(RunOutcomeKind.COMPLETED))
        assertEquals("pi 已完成 · 重构", posted?.title)
        n.onOutcome(outcome(RunOutcomeKind.ERROR))
    }

    @Test
    fun visibilityConstructedLateDoesNotThrowAndReadsImportance() {
        // 测试进程里没有 Activity；仅验证构造与读取在真实框架上可用（迟注册的前台判定逻辑由 JVM 测试 ForegroundDecider 覆盖）。
        ActivityVisibility(app).isForeground()
    }
}
