package io.github.zr987459.pigo.feature.files.platform

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/**
 * 宿主文件 → 系统分享 / 用其他应用打开，经 FileProvider（authority `<applicationId>.pigo.files`）。
 *
 * 可分享的只有两类目录（`res/xml/pigo_files_paths.xml`）：应用缓存的 `pigo-share/`、`pigo-camera/`。
 * rootfs 里的文件先复制到 `pigo-share/` 再分享，避免把整个 rootfs 暴露给 FileProvider。
 * 副本 1 小时后由 [io.github.zr987459.pigo.feature.files.share.ShareCache.cleanStale] 清理。
 */
object FileShare {
    const val SHARE_DIR = "pigo-share"
    const val CAMERA_DIR = "pigo-camera"

    fun authority(ctx: Context) = ctx.packageName + ".pigo.files"

    fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, authority(ctx), f)

    /** 复制到分享目录（同名覆盖），返回副本。大文件在调用方的 IO 线程上执行。 */
    fun stage(ctx: Context, src: File, name: String = src.name): File {
        io.github.zr987459.pigo.feature.files.share.ShareCache.cleanStale(ctx)
        val dir = File(ctx.cacheDir, SHARE_DIR).apply { mkdirs() }
        val dst = File(dir, name)
        if (src.canonicalPath != dst.canonicalPath) src.copyTo(dst, overwrite = true)
        return dst
    }

    /** 系统分享菜单。返回 false 表示没有可用的应用。 */
    fun share(ctx: Context, staged: File, mimeType: String, title: String = "分享"): Boolean {
        val uri = uriFor(ctx, staged)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(staged.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return start(ctx, Intent.createChooser(send, title))
    }

    fun shareText(ctx: Context, text: String, title: String = "分享"): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        return start(ctx, Intent.createChooser(send, title))
    }

    /** 用其他应用打开（PDF、音视频、Office 文档）。 */
    fun openWith(ctx: Context, staged: File, mimeType: String): Boolean {
        val uri = uriFor(ctx, staged)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return start(ctx, Intent.createChooser(view, "打开方式"))
    }

    private fun start(ctx: Context, i: Intent): Boolean = try {
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** Uri 的显示名与大小（SAF / 分享来的 content://）。 */
    fun displayName(ctx: Context, uri: Uri): Pair<String?, Long?> = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null to null
            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
            val s = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
            n to s
        } ?: (uri.lastPathSegment to null)
    }.getOrDefault(uri.lastPathSegment to null)
}
