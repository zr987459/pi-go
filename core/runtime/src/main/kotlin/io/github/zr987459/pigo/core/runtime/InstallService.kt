package io.github.zr987459.pigo.core.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * 安装期间的前台服务（§5.1 F3）：显示步骤与百分比；只在安装进行时持有 WakeLock（§5.7 N1）。
 * 安装逻辑本身不在这里跑，由 RealRuntimeManager 的协程执行；本服务只负责让进程在后台/锁屏时不被回收。
 */
class InstallService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startForeground(NOTIFICATION_ID, build(this, "正在准备安装…", 0))
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pi-go:install")
            ?.apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "正在安装运行环境…"
        val pct = intent?.getIntExtra(EXTRA_PERCENT, 0) ?: 0
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(this, text, pct))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "pigo-install"
        private const val NOTIFICATION_ID = 0x5049
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_PERCENT = "percent"
        private const val ACTION_STOP = "io.github.zr987459.pigo.runtime.STOP_INSTALL_SERVICE"

        fun update(context: Context, text: String, percent: Int) {
            val i = Intent(context, InstallService::class.java).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_PERCENT, percent)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, InstallService::class.java)) }
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "运行环境安装", NotificationManager.IMPORTANCE_LOW))
            }
        }

        private fun build(context: Context, text: String, percent: Int): Notification =
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("pi-go 正在安装运行环境 · $percent%")
                .setContentText(text)
                .setProgress(100, percent.coerceIn(0, 100), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
    }
}
