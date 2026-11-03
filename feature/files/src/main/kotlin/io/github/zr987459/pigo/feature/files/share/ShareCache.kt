package io.github.zr987459.pigo.feature.files.share

import android.content.Context
import android.net.Uri
import io.github.zr987459.pigo.feature.files.platform.FileShare
import java.io.File
import java.io.InputStream

/**
 * 应用缓存里分享相关副本的清理与限额：`pigo-share/`（分享出去 / 用其他应用打开的副本）、`pigo-share/inbox/`（冷启动收到的分享）、
 * `pigo-camera/`（拍照）。这些副本可能含敏感内容，不长期保留。
 */
object ShareCache {
    /** inbox 单个文件上限，超过的不复制。 */
    const val INBOX_MAX_BYTES = 50L * 1024 * 1024

    /** 分享出去的副本与拍照文件：保留 1 小时（接收方读取需要时间）。 */
    const val OUTGOING_TTL_MS = 60L * 60 * 1000

    /** inbox：等 App 装配好再处理，最多保留 24 小时；正常情况下处理完立即删除。 */
    const val INBOX_TTL_MS = 24L * 60 * 60 * 1000

    private const val INBOX = "inbox"

    fun inboxDir(ctx: Context) = File(File(ctx.cacheDir, FileShare.SHARE_DIR), INBOX)

    /** 本 App FileProvider 指向 inbox 副本的 Uri（`content://<pkg>.pigo.files/share/inbox/<文件>`）。 */
    fun isInboxUri(ctx: Context, uri: Uri): Boolean {
        if (!uri.scheme.equals("content", true) || uri.authority != FileShare.authority(ctx)) return false
        val seg = uri.pathSegments
        return seg.size == 3 && seg[0] == "share" && seg[1] == INBOX && seg[2] != ".." && seg[2].isNotBlank()
    }

    fun deleteInboxCopies(ctx: Context, uris: List<Uri>) {
        val dir = inboxDir(ctx)
        for (u in uris) if (isInboxUri(ctx, u)) File(dir, u.pathSegments[2]).takeIf { it.parentFile == dir }?.delete()
    }

    /** 删除过期副本；在分享入口、拍照、暂存分享副本前调用。 */
    fun cleanStale(ctx: Context, now: Long = System.currentTimeMillis()) {
        runCatching {
            val share = File(ctx.cacheDir, FileShare.SHARE_DIR)
            deleteOlder(share, now - OUTGOING_TTL_MS, skipDirNamed = INBOX)
            deleteOlder(File(share, INBOX), now - INBOX_TTL_MS)
            deleteOlder(File(ctx.cacheDir, FileShare.CAMERA_DIR), now - OUTGOING_TTL_MS)
        }
    }

    internal fun deleteOlder(dir: File, cutoff: Long, skipDirNamed: String? = null) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) { if (f.name != skipDirNamed && f.lastModified() < cutoff) f.deleteRecursively() }
            else if (f.lastModified() < cutoff) f.delete()
        }
    }

    /** 复制最多 [max] 字节；超过时删掉半成品并返回 false。 */
    internal fun copyLimited(input: InputStream, dst: File, max: Long): Boolean {
        var total = 0L
        val ok = dst.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) return@use false
                out.write(buf, 0, n)
            }
            true
        }
        if (!ok) dst.delete()
        return ok
    }
}
