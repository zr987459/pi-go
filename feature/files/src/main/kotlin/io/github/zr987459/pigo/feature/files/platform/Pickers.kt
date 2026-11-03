package io.github.zr987459.pigo.feature.files.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 系统选择器（相册、拍照、SAF 打开 / 新建文档）的挂起式封装。
 *
 * feature/files 不依赖 activity-compose，也不要求宿主 Activity 注册 ActivityResult：这里用一个透明的 [PickerActivity]
 * 代为启动系统界面并取回结果，调用方只需 `Pickers.pickImages(context)`。进程被系统回收时结果丢失，返回空列表。
 */
object Pickers {
    enum class Kind { IMAGES, CAMERA, DOCUMENTS, CREATE_DOCUMENT, OPEN_TREE }

    private val seq = AtomicInteger(1)
    internal val pending = ConcurrentHashMap<Int, CompletableDeferred<List<Uri>>>()
    internal val cameraTargets = ConcurrentHashMap<Int, Uri>()

    suspend fun pickImages(ctx: Context, multiple: Boolean = true): List<Uri> = launch(ctx, Kind.IMAGES) { putExtra(EXTRA_MULTIPLE, multiple) }

    /** 拍照：照片写到应用缓存（经 FileProvider 交给相机），返回该 Uri；取消返回空。 */
    suspend fun takePhoto(ctx: Context): List<Uri> {
        val dir = File(ctx.cacheDir, FileShare.CAMERA_DIR).apply { mkdirs() }
        val f = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
        val uri = FileShare.uriFor(ctx, f)
        return launch(ctx, Kind.CAMERA) { putExtra(EXTRA_OUTPUT, uri) }
    }

    suspend fun pickDocuments(ctx: Context, mimeTypes: Array<String> = arrayOf("*/*"), multiple: Boolean = true): List<Uri> =
        launch(ctx, Kind.DOCUMENTS) { putExtra(EXTRA_MIME, mimeTypes); putExtra(EXTRA_MULTIPLE, multiple) }

    /** 「另存为」：返回用户选的目标 Uri（可写），取消返回 null。 */
    suspend fun createDocument(ctx: Context, fileName: String, mimeType: String): Uri? =
        launch(ctx, Kind.CREATE_DOCUMENT) { putExtra(EXTRA_NAME, fileName); putExtra(EXTRA_MIME, arrayOf(mimeType)) }.firstOrNull()

    private suspend fun launch(ctx: Context, kind: Kind, extras: Intent.() -> Unit): List<Uri> {
        val id = seq.getAndIncrement()
        val d = CompletableDeferred<List<Uri>>()
        pending[id] = d
        val i = Intent(ctx, PickerActivity::class.java).apply {
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_KIND, kind.name)
            if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            extras()
        }
        try {
            ctx.startActivity(i)
            return d.await()
        } finally {
            pending.remove(id)
        }
    }

    internal fun deliver(id: Int, uris: List<Uri>) {
        pending.remove(id)?.complete(uris)
    }

    internal const val EXTRA_ID = "pigo.picker.id"
    internal const val EXTRA_KIND = "pigo.picker.kind"
    internal const val EXTRA_MULTIPLE = "pigo.picker.multiple"
    internal const val EXTRA_MIME = "pigo.picker.mime"
    internal const val EXTRA_NAME = "pigo.picker.name"
    internal const val EXTRA_OUTPUT = "pigo.picker.output"
}

/** 透明的中转 Activity：启动系统选择器，把结果交回 [Pickers]。 */
class PickerActivity : Activity() {
    private var id = 0
    private var output: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getIntExtra(Pickers.EXTRA_ID, 0)
        if (savedInstanceState != null) {
            output = savedInstanceState.getParcelableCompat(KEY_OUTPUT)
            return // 正在等结果（旋转等重建）
        }
        val kind = runCatching { Pickers.Kind.valueOf(intent.getStringExtra(Pickers.EXTRA_KIND)!!) }.getOrNull()
        val multiple = intent.getBooleanExtra(Pickers.EXTRA_MULTIPLE, false)
        val target: Intent = when (kind) {
            Pickers.Kind.IMAGES -> if (Build.VERSION.SDK_INT >= 33) {
                Intent(MediaStore.ACTION_PICK_IMAGES).apply { if (multiple) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit().coerceAtMost(20)) }
            } else {
                Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple) }
            }
            Pickers.Kind.CAMERA -> {
                output = intent.getParcelableCompat(Pickers.EXTRA_OUTPUT)
                Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, output)
                    clipData = ClipData.newRawUri("output", output)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            Pickers.Kind.DOCUMENTS -> Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                val mimes = intent.getStringArrayExtra(Pickers.EXTRA_MIME) ?: arrayOf("*/*")
                type = if (mimes.size == 1) mimes[0] else "*/*"
                if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            }
            Pickers.Kind.CREATE_DOCUMENT -> Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = intent.getStringArrayExtra(Pickers.EXTRA_MIME)?.firstOrNull() ?: "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, intent.getStringExtra(Pickers.EXTRA_NAME))
            }
            Pickers.Kind.OPEN_TREE -> Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            null -> { finishWith(emptyList()); return }
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(target, REQ)
        } catch (_: ActivityNotFoundException) {
            finishWith(emptyList())
        } catch (_: SecurityException) {
            finishWith(emptyList())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(KEY_OUTPUT, output)
    }

    @Deprecated("Activity 结果 API 的经典写法；本模块不依赖 activity-compose")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        if (resultCode != RESULT_OK) { finishWith(emptyList()); return }
        val out = output
        if (out != null) { finishWith(listOf(out)); return }
        val uris = ArrayList<Uri>()
        data?.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let(uris::add) }
        if (uris.isEmpty()) data?.data?.let(uris::add)
        // 持久化读权限（能拿到就拿，拿不到不影响本次读取）
        val flags = (data?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (flags != 0) uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, flags) } }
        finishWith(uris)
    }

    private fun finishWith(uris: List<Uri>) {
        Pickers.deliver(id, uris)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) Pickers.deliver(id, emptyList()) // 已交付时为空操作
    }

    private companion object {
        const val REQ = 4711
        const val KEY_OUTPUT = "output"
    }
}

@Suppress("DEPRECATION")
internal inline fun <reified T : android.os.Parcelable> Intent.getParcelableCompat(key: String): T? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else getParcelableExtra(key)

@Suppress("DEPRECATION")
internal inline fun <reified T : android.os.Parcelable> Bundle.getParcelableCompat(key: String): T? =
    if (Build.VERSION.SDK_INT >= 33) getParcelable(key, T::class.java) else getParcelable(key)
