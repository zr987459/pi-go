package io.github.zr987459.pigo.feature.files.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import io.github.zr987459.pigo.contract.Attachment
import io.github.zr987459.pigo.contract.AttachmentProcessor
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.feature.files.FilesRuntime
import io.github.zr987459.pigo.feature.files.platform.FileShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 其他应用经系统分享菜单发来的内容（解析后、处理前）。 */
data class SharedPayload(val text: String?, val subject: String?, val uris: List<Uri>, val mimeType: String?) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && uris.isEmpty()
}

/** 处理好的分享：文字进输入框，附件挂到输入框（W5 接到聊天页「新会话」）。 */
data class ShareDelivery(val text: String, val attachments: List<Attachment>, val errors: List<String>)

/**
 * 分享收件箱（§2.7「发给 pi」）：[ShareTargetActivity] 放进来，聊天页（W5 接线）取走。
 * 处理器未就绪时（App 冷启动、运行环境没装好）先放 [raw]，就绪后由接线方调用 [processPending]。
 */
object ShareInbox {
    private val _delivery = MutableStateFlow<ShareDelivery?>(null)
    val delivery: StateFlow<ShareDelivery?> = _delivery.asStateFlow()
    private val _raw = MutableStateFlow<SharedPayload?>(null)
    val raw: StateFlow<SharedPayload?> = _raw.asStateFlow()

    fun put(d: ShareDelivery) { _delivery.value = d }
    fun putRaw(p: SharedPayload) { _raw.value = p }

    /** 取走并清空（只交付一次）。 */
    fun consume(): ShareDelivery? = _delivery.getAndSet(null)

    suspend fun processPending(ctx: Context, processor: AttachmentProcessor, cwd: String): ShareDelivery? {
        val p = _raw.getAndSet(null) ?: return null
        return ShareReceiver.process(ctx, p, processor, cwd).also(::put)
    }

    private fun <T> MutableStateFlow<T?>.getAndSet(v: T?): T? {
        while (true) {
            val cur = value
            if (compareAndSet(cur, v)) return cur
        }
    }
}

object ShareReceiver {
    /** 解析 ACTION_SEND / SEND_MULTIPLE；其他 Intent 返回 null。 */
    fun parse(intent: Intent): SharedPayload? {
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> intent.streamExtra()?.let(uris::add)
            Intent.ACTION_SEND_MULTIPLE -> uris += intent.streamListExtra()
            else -> return null
        }
        intent.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { if (it !in uris) uris += it } }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        return SharedPayload(text, subject, uris, intent.type).takeUnless { it.isEmpty }
    }

    /**
     * 图片 → [AttachmentProcessor.prepareImage]（压缩、摆正、base64）；其他文件 → 导入到 `<cwd>/.pigo/uploads/<日期>/`。
     * 单个失败不影响其他，错误以中文列在 [ShareDelivery.errors]。
     */
    suspend fun process(ctx: Context, p: SharedPayload, processor: AttachmentProcessor, cwd: String): ShareDelivery = withContext(Dispatchers.IO) {
        val atts = ArrayList<Attachment>()
        val errors = ArrayList<String>()
        for (uri in p.uris) {
            val (name, _) = FileShare.displayName(ctx, uri)
            val mime = ctx.contentResolver.getType(uri) ?: p.mimeType
            val isImage = mime?.startsWith("image/") == true && mime != "image/svg+xml"
            val input = runCatching { ctx.contentResolver.openInputStream(uri) }.getOrNull()
            if (input == null) { errors += "无法读取：${name ?: uri}"; continue }
            val r = input.use {
                if (isImage) processor.prepareImage(it, mime, name).let { o -> if (o is Outcome.Ok) Outcome.Ok(Attachment.Image(o.value)) else o as Outcome.Err }
                else processor.importFile(name ?: "shared", it, cwd)
            }
            when (r) {
                is Outcome.Ok -> atts += r.value
                is Outcome.Err -> errors += "${name ?: "文件"}：${r.message}"
            }
        }
        val text = listOfNotNull(p.subject?.takeIf { it.isNotBlank() && p.text?.contains(it) != true }, p.text).joinToString("\n").trim()
        ShareDelivery(text, atts, errors)
    }
}

/** 系统分享菜单里的「发给 pi」。处理完把 App 带到前台，由聊天页（W5）从 [ShareInbox] 取走。 */
class ShareTargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = ShareReceiver.parse(intent)
        if (payload == null) { finish(); return }
        val rt: io.github.zr987459.pigo.feature.files.FilesDeps? = FilesRuntime.current
        if (rt == null) {
            // App 还没装配好（冷启动直接从分享进来）：分享方给的读权限随本 Activity 结束而失效，
            // 先把内容复制到应用缓存，换成本 App 自己的 Uri，交给接线方稍后处理。
            val app = applicationContext
            scope.launch {
                val local = withContext(Dispatchers.IO) { copyToInbox(app, payload) }
                ShareInbox.putRaw(local)
                openApp()
                finish()
            }
            return
        }
        Toast.makeText(this, "正在处理分享的内容…", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        scope.launch {
            val d = ShareReceiver.process(app, payload, rt.attachments, rt.defaultCwd())
            ShareInbox.put(d)
            withContext(Dispatchers.Main) {
                if (d.errors.isNotEmpty()) Toast.makeText(app, d.errors.joinToString("\n"), Toast.LENGTH_LONG).show()
                openApp()
                finish()
            }
        }
    }

    private fun openApp() {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            it.putExtra(EXTRA_FROM_SHARE, true)
            startActivity(it)
        }
    }

    private fun copyToInbox(ctx: Context, p: SharedPayload): SharedPayload {
        val dir = java.io.File(ctx.cacheDir, FileShare.SHARE_DIR + "/inbox").apply { mkdirs() }
        val uris = p.uris.mapNotNull { uri ->
            runCatching {
                val name = FileShare.displayName(ctx, uri).first?.substringAfterLast('/')?.ifBlank { null } ?: "shared"
                val f = java.io.File(dir, System.nanoTime().toString() + "-" + name)
                ctx.contentResolver.openInputStream(uri)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                FileShare.uriFor(ctx, f)
            }.getOrNull()
        }
        return p.copy(uris = uris)
    }

    companion object {
        /** 启动 App 的 Intent 带这个标记，接线方据此打开新会话并取 [ShareInbox]。 */
        const val EXTRA_FROM_SHARE = "io.github.zr987459.pigo.FROM_SHARE"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}

@Suppress("DEPRECATION")
private fun Intent.streamExtra(): Uri? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableExtra(Intent.EXTRA_STREAM)

@Suppress("DEPRECATION")
private fun Intent.streamListExtra(): List<Uri> =
    (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
