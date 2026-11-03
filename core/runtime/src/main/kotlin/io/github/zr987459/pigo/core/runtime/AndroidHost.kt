package io.github.zr987459.pigo.core.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import io.github.zr987459.pigo.core.runtime.install.HostInfo
import io.github.zr987459.pigo.core.runtime.process.Signaller
import java.io.File

/** [HostInfo] 的 Android 实现。 */
class AndroidHost(private val context: Context) : HostInfo {
    override val abis: List<String> get() = Build.SUPPORTED_ABIS.toList()

    override fun freeBytes(dir: File): Long = runCatching {
        dir.mkdirs()
        StatFs(dir.absolutePath).availableBytes
    }.getOrDefault(0L)

    override fun networkAvailable(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun dnsServers(): List<String> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork)
        lp?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') } ?: emptyList()
    }.getOrDefault(emptyList())

    /** 本进程的附加组（读 /proc/self/status 的 Groups 行）。 */
    override fun gids(): List<Int> = runCatching {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("Groups:") }
            ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() } ?: emptyList()
    }.getOrDefault(emptyList())

    val uid: Int get() = android.os.Process.myUid()

    val signaller = Signaller { pid, sig -> android.os.Process.sendSignal(pid, sig) }
}
