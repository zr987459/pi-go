package io.github.zr987459.pigo.feature.chat.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.feature.chat.ExtUiNotifier
import io.github.zr987459.pigo.feature.chat.logic.ExtUi

/**
 * App 在后台（或不在聊天页）时，扩展对话框请求用通知提醒（§3.6）。点通知回到 App。
 * 通知权限被关时静默跳过：对话框仍在聊天页等待，不会丢。
 */
class AndroidExtUiNotifier(context: Context) : ExtUiNotifier {
    private val app = context.applicationContext
    private val nm = NotificationManagerCompat.from(app)
    private val ids = HashMap<String, Int>()
    private var nextId = BASE_ID

    init {
        val channel = NotificationChannel(CHANNEL, "pi 等待你的回答", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "扩展弹出的确认/选择/输入请求"
        }
        app.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    @Synchronized
    override fun notifyDialog(request: ExtUiRequest) {
        if (!nm.areNotificationsEnabled()) return
        val nid = ids.getOrPut(request.id) { nextId++ }
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = launch?.let {
            PendingIntent.getActivity(app, nid, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("pi 在等你回答：" + ExtUi.titleOf(request))
            .setContentText(request.message?.takeIf { it.isNotBlank() } ?: "请回到「聊天」页处理")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply { if (pi != null) setContentIntent(pi) }
            .build()
        try {
            nm.notify(nid, n)
        } catch (_: SecurityException) {
            // Android 13+ 未授予通知权限：忽略，对话框仍在聊天页。
        }
    }

    @Synchronized
    override fun cancel(id: String) {
        ids.remove(id)?.let { nm.cancel(it) }
    }

    @Synchronized
    override fun clearAll() {
        ids.values.forEach { nm.cancel(it) }
        ids.clear()
    }

    private companion object {
        const val CHANNEL = "pigo_ext_ui"
        const val BASE_ID = 7300
    }
}
