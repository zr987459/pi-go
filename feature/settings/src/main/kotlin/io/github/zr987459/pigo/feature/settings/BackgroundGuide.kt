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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.zr987459.pigo.core.ui.AppBackdrop
import io.github.zr987459.pigo.core.ui.AppIcons
import io.github.zr987459.pigo.core.ui.ChromeIconButton
import io.github.zr987459.pigo.core.ui.DshTheme
import io.github.zr987459.pigo.core.ui.GlassTier
import io.github.zr987459.pigo.feature.settings.ui.DshFilledButton
import io.github.zr987459.pigo.feature.settings.ui.DshOutlinedButton
import io.github.zr987459.pigo.feature.settings.ui.ExpandRow
import io.github.zr987459.pigo.feature.settings.ui.LargeTitle
import io.github.zr987459.pigo.feature.settings.ui.SettingsHues
import io.github.zr987459.pigo.feature.settings.ui.SettingsSection
import io.github.zr987459.pigo.feature.settings.ui.SettingsTile
import io.github.zr987459.pigo.feature.settings.ui.StepRow
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
 * 界面照 dsh-Go `app/lib/ui/settings/background_guide_page.dart`：分组卡 + 编号步骤 + 可展开的其他品牌说明。
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

/**
 * 引导内容（设置页的后台运行页与首次弹窗共用），dsh-Go 后台引导页的分组：
 * 「你的手机」厂商自启动入口 →「系统」电池优化状态 →「保持在线」分步说明 → 其他品牌（可展开）→ Android 12+ 子进程限制。
 */
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

    Column(modifier.testTag("settings:backgroundGuide")) {
        SettingsSection(vendor.label, footer = autostartHint) {
            SettingsTile(
                title = "允许自启动 / 后台活动",
                icon = AppIcons.autostart,
                tint = SettingsHues.GENERAL,
                subtitle = RomGuide.steps(vendor).first(),
                chevron = true,
                onTap = {
                    val ok = BackgroundGuide.openAutostart(context)
                    autostartHint = if (ok) null else "没找到厂商的自启动页面，已打开应用详情，请按下面的说明手动设置。"
                },
                modifier = Modifier.testTag("settings:openAutostart"),
            )
        }
        SettingsSection("系统") {
            SettingsTile(
                title = "电池优化",
                icon = AppIcons.keepAlive,
                tint = SettingsHues.GENERAL,
                value = if (ignoring) "已关闭" else "未关闭",
                valueColor = if (ignoring) DshTheme.colors.success else DshTheme.colors.warning,
                onTap = if (ignoring) {
                    null
                } else {
                    { BackgroundGuide.requestIgnoreBatteryOptimizations(context); Unit }
                },
                modifier = Modifier.testTag("settings:batteryOptimization"),
            )
        }
        SettingsSection("保持在线") {
            RomGuide.steps(vendor).forEachIndexed { i, s -> StepRow(i + 1, s) }
        }
        val others = RomGuide.Vendor.entries.filter { it != vendor && it != RomGuide.Vendor.OTHER }
        SettingsSection(if (vendor == RomGuide.Vendor.OTHER) "手机品牌" else "其他品牌") {
            others.forEach { v -> ExpandRow(v.label, RomGuide.steps(v)) }
        }
        if (RomGuide.needsPhantomNotice(Build.VERSION.SDK_INT)) {
            PhantomProcessNotice()
        }
    }
}

/** Android 12+ 幻影进程限制说明（诊断页也可以直接复用）。 */
@Composable
fun PhantomProcessNotice(modifier: Modifier = Modifier) {
    SettingsSection(RomGuide.PHANTOM_TITLE, modifier.testTag("settings:phantomNotice")) {
        Text(
            RomGuide.PHANTOM_TEXT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/**
 * 「后台运行」整页（dsh-Go `BackgroundGuidePage` 的 AppPage.list：返回圆钮 + 大标题 + 副标题 + 分组）。
 * 设置页没有自己的导航，所以用全屏 Dialog 承载；[footer] 放首次弹窗的按钮。
 */
@Composable
fun BackgroundGuidePage(onClose: () -> Unit, footer: (@Composable () -> Unit)? = null) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AppBackdrop(Modifier.testTag("page:backgroundGuide")) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChromeIconButton(AppIcons.back, "返回", onClose, size = 44.dp, tier = GlassTier.Pill)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    LargeTitle(
                        "后台运行",
                        subtitle = "pi 在后台跑任务时，手机系统可能为了省电把它关掉。按下面的设置后，切到其他 App 或锁屏也能继续运行。",
                    )
                    BackgroundGuideContent()
                }
                if (footer != null) {
                    Box(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)) { footer() }
                }
            }
        }
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
    BackgroundGuidePage(onClose = close) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DshFilledButton("设置好了", close, Modifier.fillMaxWidth())
            DshOutlinedButton("以后再说（可在设置里找到）", close, Modifier.fillMaxWidth())
        }
    }
}
