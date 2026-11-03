package io.github.zr987459.pigo.core.engine

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 引擎前台服务（DESIGN.md §2.3、§3.2）：pi 进程存在期间让 App 保持前台优先级，降低被系统回收的概率。
 *
 * 服务本身**不持有唤醒锁**——唤醒锁由 [PiEngine] 经 [WakeLockGuard] 只在一轮运行期间持有（N1），
 * 服务被销毁也不会结束引擎（返回键退到后台时引擎继续，N4）。
 */
class EngineService : LifecycleService() {

    private var lastText = "pi 引擎已启动"

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIFICATION_ID, build(lastText))
        val engine = boundEngine ?: return
        lifecycleScope.launch {
            combine(engine.status, engine.chat.map { it.running }.distinctUntilChanged()) { st, running -> text(st, running) }
                .distinctUntilChanged()
                .collect { t ->
                    lastText = t
                    notify(t)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // 每次 startForegroundService 都要求服务调用 startForeground；先进入前台，再看引擎是否已经不需要服务了
        // （引擎在服务进入前台前就要求关闭时，ServiceForegroundPort 不会 stopService，而由这里 stopSelf，见 ForegroundGate）。
        startForeground(NOTIFICATION_ID, build(lastText))
        if (gate.onForegroundStarted()) stopSelf()
        // 被系统杀掉后不自动拉起：冷启动时由 App 按运行标记恢复会话（§3.9）。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        gate.onServiceDestroyed()
        super.onDestroy()
    }

    private fun text(st: EngineStatus, running: Boolean): String = when {
        running -> "pi 正在工作…"
        st is EngineStatus.Starting -> "pi 正在启动…"
        st is EngineStatus.Crashed -> if (st.retryInMs != null) "pi 意外退出，正在自动恢复" else "pi 已停止：${st.hint}"
        else -> "pi 空闲"
    }

    private fun notify(text: String) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, build(text))
        } catch (_: SecurityException) {
            // Android 13+ 未授予通知权限：前台服务仍有效，只是不更新文字。
        }
    }

    private fun ensureChannel() {
        val ch = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName("pi 引擎")
            .setDescription("pi 在后台运行时显示")
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(ch)
    }

    private fun build(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("pi-go")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(launch)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "pigo-engine"
        const val NOTIFICATION_ID = 0x9160

        /** 由装配层（EngineBindings.real）设置，供通知显示状态。 */
        @Volatile
        var boundEngine: EngineClient? = null

        /** 引擎侧（ServiceForegroundPort）与服务侧共享的启停协调。 */
        val gate = ForegroundGate()
    }
}
