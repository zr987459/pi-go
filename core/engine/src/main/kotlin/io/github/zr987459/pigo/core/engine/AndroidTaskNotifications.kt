package io.github.zr987459.pigo.core.engine

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/** 以 Activity 的 started 计数判断 App 是否在前台（不依赖 lifecycle-process）。在 Application 创建时注册。 */
class ActivityVisibility(app: Application) : AppVisibility {
    private val started = AtomicInteger(0)

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started.incrementAndGet()
            }

            override fun onActivityStopped(activity: Activity) {
                started.updateAndGet { (it - 1).coerceAtLeast(0) }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    override fun isForeground(): Boolean = started.get() > 0
}

/**
 * `pigo-tasks` 通道的 Android 实现。点通知打开 App 的启动 Activity，附带 [EXTRA_SESSION_FILE]、[EXTRA_CWD]
 * （W5 接线时由导航读取，调用 `openSession(sessionFile, cwd)` 回到会话）。没有通知权限（Android 13+ 未授权）时静默跳过。
 */
class AndroidTaskNotifications(context: Context) : TaskNotificationPort {
    private val app = context.applicationContext

    init {
        NotificationManagerCompat.from(app).createNotificationChannel(
            NotificationChannelCompat.Builder(TaskNotifier.CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(TaskNotifier.CHANNEL_NAME)
                .setDescription("pi 在后台完成、出错或需要你回答时提醒")
                .build(),
        )
    }

    override fun post(notice: TaskNotice) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
            putExtra(EXTRA_SESSION_FILE, notice.outcome.sessionFile)
            putExtra(EXTRA_CWD, notice.outcome.cwd)
        }
        val pi = launch?.let {
            PendingIntent.getActivity(app, notice.id, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val n = NotificationCompat.Builder(app, TaskNotifier.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
        try {
            NotificationManagerCompat.from(app).notify(notice.id, n)
        } catch (e: SecurityException) {
            // 权限在检查后被撤销：忽略。
        }
    }

    companion object {
        const val EXTRA_SESSION_FILE = "io.github.zr987459.pigo.extra.SESSION_FILE"
        const val EXTRA_CWD = "io.github.zr987459.pigo.extra.CWD"
    }
}
