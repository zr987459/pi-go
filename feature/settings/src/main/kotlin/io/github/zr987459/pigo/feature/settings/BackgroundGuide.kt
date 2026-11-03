package io.github.zr987459.pigo.feature.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * 后台运行引导（DESIGN.md §5.7 N5）：电池白名单、自启动、Android 12+ 幻影进程说明。
 *
 * 「首次进入聊天时引导」：聊天页（mvp/chat-ui）或 mvp/integration 在进入聊天时调用
 * `if (BackgroundGuide.shouldShow(context)) 显示 BackgroundGuideDialog`，关闭后自动记为已提示。
 */
object BackgroundGuide {
    private const val PREFS = "pigo_background_guide"
    private const val KEY_SHOWN = "shown_v1"

    val vendor: RomGuide.Vendor get() = RomGuide.vendorOf(Build.MANUFACTURER, Build.BRAND)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 首次进入聊天时是否需要弹引导：没提示过，且（国产 ROM 或还没关电池优化）。 */
    fun shouldShow(context: Context): Boolean {
        val shown = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOWN, false)
        if (shown) return false
        return RomGuide.isAggressive(vendor) || !isIgnoringBatteryOptimizations(context)
    }

    fun markShown(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOWN, true).apply()
    }

    /** 弹出系统「允许 pi-go 忽略电池优化」对话框；不支持时打开电池优化列表。返回是否成功打开。 */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        return start(context, direct) || start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
            openAppDetails(context)
    }

    /** 打开厂商的自启动管理页；都打不开时打开应用详情页。返回打开的是否为厂商页。 */
    fun openAutostart(context: Context): Boolean {
        for ((pkg, cls) in RomGuide.autostartComponents(vendor)) {
            if (start(context, Intent().setComponent(ComponentName(pkg, cls)))) return true
        }
        openAppDetails(context)
        return false
    }

    fun openAppDetails(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

    private fun start(context: Context, intent: Intent): Boolean = try {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false // 部分厂商页面不对第三方开放
    }
}

/** 引导内容（设置页与首次弹窗共用）。 */
@Composable
fun BackgroundGuideContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vendor = remember { BackgroundGuide.vendor }
    var ignoring by remember { mutableStateOf(BackgroundGuide.isIgnoringBatteryOptimizations(context)) }
    var autostartHint by remember { mutableStateOf<String?>(null) }
    // 从系统设置页回来时刷新状态
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ignoring = BackgroundGuide.isIgnoringBatteryOptimizations(context)
    }

    Column(modifier.testTag("settings:backgroundGuide"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "pi 在后台跑任务时，手机系统可能为了省电把它关掉。按下面两步设置后，切到其他 App 或锁屏也能继续运行。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("你的手机：${vendor.label}", style = MaterialTheme.typography.labelMedium)

        Text("第一步：关闭电池优化", style = MaterialTheme.typography.titleSmall)
        if (ignoring) {
            Text("✓ 已关闭，pi-go 不受电池优化限制。", color = MaterialTheme.colorScheme.primary)
        } else {
            Button(onClick = { BackgroundGuide.requestIgnoreBatteryOptimizations(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("允许 pi-go 在后台运行")
            }
        }

        Text("第二步：允许自启动 / 后台活动", style = MaterialTheme.typography.titleSmall)
        RomGuide.steps(vendor).forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(
            onClick = {
                val ok = BackgroundGuide.openAutostart(context)
                autostartHint = if (ok) null else "没找到厂商的自启动页面，已打开应用详情，请按上面的说明手动设置。"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("打开自启动设置") }
        autostartHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        if (RomGuide.needsPhantomNotice(Build.VERSION.SDK_INT)) {
            PhantomProcessNotice()
        }
    }
}

/** Android 12+ 幻影进程限制说明（诊断页也可以直接复用）。 */
@Composable
fun PhantomProcessNotice(modifier: Modifier = Modifier) {
    Column(modifier.testTag("settings:phantomNotice"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(RomGuide.PHANTOM_TITLE, style = MaterialTheme.typography.titleSmall)
        Text(RomGuide.PHANTOM_TEXT, style = MaterialTheme.typography.bodySmall)
    }
}

/** 首次进入聊天时弹出的引导；关闭（无论哪个按钮）后记为已提示，不再自动弹出。 */
@Composable
fun BackgroundGuideDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val close = {
        BackgroundGuide.markShown(context)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("让 pi 在后台也能继续干活") },
        text = { BackgroundGuideContent(Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = close) { Text("设置好了") } },
        dismissButton = { TextButton(onClick = close) { Text("以后再说（可在设置里找到）") } },
    )
}
