package io.github.zr987459.pigo.feature.files.share

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * 系统分享入口收到的 Uri 的准入规则（防借权读取）。
 *
 * [ShareTargetActivity] 是 exported 的，任何应用都能把 Uri 塞进 `EXTRA_STREAM` / `clipData`，而读取时用的是**本 App** 的权限。
 * 所以只接受别的应用自己提供的 `content://`：
 * - 拒绝 `file://` 及其他 scheme（可指向 `files/rootfs/root/.pi/agent/auth.json`、shared_prefs 等私有文件）；
 * - 拒绝 authority 属于本包的 content Uri（本 App 的 FileProvider 等内部 provider）。
 */
object ShareUriPolicy {
    /** 纯判断，便于单测。[ownAuthorities] 为本包声明的全部 provider authority；[ownPackage] 前缀的 authority 也一律视为本包。 */
    fun isAllowed(scheme: String?, authority: String?, ownPackage: String, ownAuthorities: Set<String>): Boolean {
        if (!scheme.equals("content", ignoreCase = true)) return false
        val auth = authority?.substringAfterLast('@')?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        if (auth in ownAuthorities.map { it.lowercase() }) return false
        val pkg = ownPackage.lowercase()
        if (auth == pkg || auth.startsWith("$pkg.")) return false
        return true
    }

    fun isAllowed(ctx: Context, uri: Uri): Boolean = isAllowed(uri.scheme, uri.authority, ctx.packageName, ownAuthorities(ctx))

    /** 把不允许的 Uri 分出来：first 为可读取的，second 为被拒绝的个数。 */
    fun partition(ctx: Context, uris: List<Uri>): Pair<List<Uri>, Int> {
        val own = ownAuthorities(ctx)
        val ok = uris.filter { isAllowed(it.scheme, it.authority, ctx.packageName, own) }
        return ok to (uris.size - ok.size)
    }

    @Suppress("DEPRECATION")
    fun ownAuthorities(ctx: Context): Set<String> = runCatching {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PROVIDERS.toLong()))
        else pm.getPackageInfo(ctx.packageName, PackageManager.GET_PROVIDERS)
        info.providers.orEmpty().flatMap { it.authority.orEmpty().split(';') }.filter { it.isNotBlank() }.toSet()
    }.getOrDefault(emptySet())

    const val REJECTED_MESSAGE = "已拒绝不安全的来源（只接受其他应用提供的 content:// 文件）"
}
