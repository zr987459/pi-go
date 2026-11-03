package io.github.zr987459.pigo.feature.settings

/**
 * 国产 ROM 后台保活引导的纯逻辑（DESIGN.md §5.7 N5），不依赖 Android，可 JVM 单测。
 * 各厂商设置页的组件名来自公开资料，系统版本不同可能打不开，打不开时退回「应用详情」页。
 */
object RomGuide {
    enum class Vendor(val label: String) {
        XIAOMI("小米 / Redmi（MIUI / 澎湃 OS）"),
        HUAWEI("华为（EMUI / 鸿蒙）"),
        HONOR("荣耀（MagicOS）"),
        OPPO("OPPO / realme（ColorOS）"),
        VIVO("vivo / iQOO（OriginOS）"),
        ONEPLUS("一加"),
        MEIZU("魅族（Flyme）"),
        SAMSUNG("三星"),
        OTHER("其他品牌"),
    }

    fun vendorOf(manufacturer: String?, brand: String?): Vendor {
        val s = "${manufacturer.orEmpty()} ${brand.orEmpty()}".lowercase()
        return when {
            "xiaomi" in s || "redmi" in s || "poco" in s -> Vendor.XIAOMI
            "honor" in s -> Vendor.HONOR
            "huawei" in s -> Vendor.HUAWEI
            "oneplus" in s -> Vendor.ONEPLUS
            "oppo" in s || "realme" in s -> Vendor.OPPO
            "vivo" in s || "iqoo" in s -> Vendor.VIVO
            "meizu" in s -> Vendor.MEIZU
            "samsung" in s -> Vendor.SAMSUNG
            else -> Vendor.OTHER
        }
    }

    /** 国产 ROM 默认会更积极地清理后台，需要引导。 */
    fun isAggressive(v: Vendor): Boolean = v != Vendor.OTHER && v != Vendor.SAMSUNG

    /** 自启动 / 后台管理页的候选组件（包名, 类名），按顺序尝试。 */
    fun autostartComponents(v: Vendor): List<Pair<String, String>> = when (v) {
        Vendor.XIAOMI -> listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
        Vendor.HUAWEI, Vendor.HONOR -> listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        )
        Vendor.OPPO, Vendor.ONEPLUS -> listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        )
        Vendor.VIVO -> listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        )
        Vendor.MEIZU -> listOf(
            "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",
        )
        Vendor.SAMSUNG, Vendor.OTHER -> emptyList()
    }

    /** 给新手看的分步说明。 */
    fun steps(v: Vendor): List<String> = when (v) {
        Vendor.XIAOMI -> listOf(
            "在「自启动管理」里打开 pi-go 的开关。",
            "回到应用详情 → 省电策略 → 选「无限制」。",
            "在最近任务里下拉 pi-go 的卡片加锁，防止一键清理时被关掉。",
        )
        Vendor.HUAWEI, Vendor.HONOR -> listOf(
            "在「应用启动管理」里找到 pi-go，关闭「自动管理」。",
            "在弹出的窗口里把「允许自启动」「允许关联启动」「允许后台活动」都打开。",
            "在最近任务里下拉 pi-go 的卡片加锁。",
        )
        Vendor.OPPO, Vendor.ONEPLUS -> listOf(
            "在「自启动管理」里打开 pi-go。",
            "应用详情 → 耗电管理 → 打开「允许后台运行」（部分机型叫「允许完全后台行为」）。",
            "在最近任务里长按 pi-go 卡片 → 锁定。",
        )
        Vendor.VIVO -> listOf(
            "在「后台高耗电」或「自启动」里允许 pi-go。",
            "设置 → 电池 → 后台耗电管理 → pi-go 选「允许后台高耗电」。",
            "在最近任务里下拉 pi-go 卡片加锁。",
        )
        Vendor.MEIZU -> listOf(
            "在「后台管理」里把 pi-go 设为「允许后台运行」。",
            "在最近任务里下拉 pi-go 卡片加锁。",
        )
        Vendor.SAMSUNG -> listOf(
            "设置 → 电池 → 后台使用限制 → 把 pi-go 加入「永不休眠的应用」。",
        )
        Vendor.OTHER -> listOf(
            "在系统设置里允许 pi-go 自启动、后台运行，并关闭它的电池优化。",
        )
    }

    /** Android 12+（API 31+）幻影进程限制说明。 */
    const val PHANTOM_TITLE = "Android 12 及以上：子进程数量限制"
    val PHANTOM_TEXT: String = """
        Android 12 起，系统会统计每个 App 在后台启动的子进程（叫「幻影进程」），全机超过约 32 个时会直接结束它们，
        也会结束占用 CPU 过多的后台子进程。pi 运行在容器里，命令行工具、编译、测试都会产生子进程，
        所以在后台跑大任务时，可能出现「上一轮被系统中断」。

        减少中断的办法：
        • 跑大任务时尽量让 pi-go 留在前台、屏幕常亮；
        • 按上面的步骤关闭电池优化、允许后台运行；
        • 部分系统（Android 14 及以上）可以在「开发者选项」里打开「停用子进程限制」；
        • 有电脑的话，可以用 adb 关闭限制（重启手机后可能失效）：
          adb shell device_config set_sync_disabled_for_tests persistent
          adb shell device_config put activity_manager max_phantom_processes 2147483647
          Android 12L / 13 还可以用：adb shell settings put global settings_enable_monitor_phantom_procs false
    """.trimIndent()

    fun needsPhantomNotice(sdkInt: Int): Boolean = sdkInt >= 31
}
