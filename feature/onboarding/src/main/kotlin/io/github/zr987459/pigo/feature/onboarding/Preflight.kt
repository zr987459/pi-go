package io.github.zr987459.pigo.feature.onboarding

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs

/** §5.1 F1：ABI、可用空间、网络三项预检。纯数据 + 纯函数，便于单测。 */
data class PreflightCheck(val label: String, val ok: Boolean, val detail: String)

data class Preflight(val abi: PreflightCheck, val space: PreflightCheck, val network: PreflightCheck) {
    val checks get() = listOf(abi, space, network)

    /** 空间与 ABI 不满足时禁止开始；网络不通也不让开始（安装第一步就要下载）。 */
    val canStart get() = abi.ok && space.ok && network.ok

    /** 不能开始时给用户看的第一条原因。 */
    val blockingReason: String? get() = checks.firstOrNull { !it.ok }?.detail

    companion object {
        const val MIN_FREE_BYTES = 3L * 1024 * 1024 * 1024
        private val SUPPORTED = listOf("arm64-v8a", "x86_64")

        fun evaluate(abis: List<String>, freeBytes: Long, networkOk: Boolean): Preflight {
            val abi = abis.firstOrNull { it in SUPPORTED }
            val gb = freeBytes.toDouble() / (1024 * 1024 * 1024)
            return Preflight(
                abi = PreflightCheck(
                    "处理器架构",
                    abi != null,
                    if (abi != null) abi else "不支持的处理器架构（${abis.joinToString()}），pi-go 只支持 arm64-v8a 与 x86_64",
                ),
                space = PreflightCheck(
                    "可用空间",
                    freeBytes >= MIN_FREE_BYTES,
                    if (freeBytes >= MIN_FREE_BYTES) "%.1f GB 可用".format(gb)
                    else "可用空间不足：需要至少 3 GB，当前只有 %.1f GB。请清理手机存储后再开始。".format(gb),
                ),
                network = PreflightCheck(
                    "网络",
                    networkOk,
                    if (networkOk) "已连接" else "没有可用的网络连接，请联网后再开始",
                ),
            )
        }

        fun read(context: Context): Preflight {
            val free = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
            val net = runCatching {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }.getOrDefault(false)
            return evaluate(Build.SUPPORTED_ABIS.toList(), free, net)
        }
    }
}
