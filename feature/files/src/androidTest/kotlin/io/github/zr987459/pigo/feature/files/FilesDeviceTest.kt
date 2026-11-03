package io.github.zr987459.pigo.feature.files

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.metrics.performance.JankStats
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zr987459.pigo.contract.Attachment
import io.github.zr987459.pigo.contract.AttachmentProcessor
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.FilePreview
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.fake.FakeSessionExporter
import io.github.zr987459.pigo.contract.fake.FakeWorkspaceFs
import io.github.zr987459.pigo.feature.files.browser.FilesController
import io.github.zr987459.pigo.feature.files.output.OutputViewerScreen
import io.github.zr987459.pigo.feature.files.platform.FileShare
import io.github.zr987459.pigo.feature.files.share.ShareDelivery
import io.github.zr987459.pigo.feature.files.share.ShareInbox
import io.github.zr987459.pigo.feature.files.share.ShareTargetActivity
import io.github.zr987459.pigo.feature.files.share.ShareUriPolicy
import io.github.zr987459.pigo.feature.files.ui.FilesBrowserScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * feature/files 的仪器 / 界面验收（§3.2 W3）。
 *
 * 模块边界：feature/files 不依赖 core/workspace（构建文件不归 W3 改），所以这里的附件处理器是
 * [BitmapProcessor]（真实 Android BitmapFactory 解码 + 重编码），不是 FakeAttachmentProcessor 的原字节直转；
 * 真实 `WorkspaceAttachments + AndroidImageCodec` 与真实 `RootfsWorkspace` 经 content:// 导入见 core/workspace 的 WorkspaceDeviceTest。
 * 三者串起来的端到端（Share → 真实处理器 → 真实工作区）在 W5 接线后由 app 的仪器测试覆盖。
 */
@RunWith(AndroidJUnit4::class)
class FilesDeviceTest {
    @get:Rule
    val compose = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val inserted = ArrayList<Uri>()

    /** 用真实 BitmapFactory 解码、按长边 ≤1568 重编码 JPEG 的处理器（验证收到的确实是可解码的图片）。 */
    private class BitmapProcessor : AttachmentProcessor {
        val images = AtomicInteger()
        override suspend fun prepareImage(input: InputStream, mimeType: String?, displayName: String?, maxEdgePx: Int, maxBytes: Long): Outcome<ImageInput> {
            val bmp = BitmapFactory.decodeStream(input) ?: return Outcome.Err(ErrCode.UNKNOWN, "图片解码失败")
            val scale = minOf(1f, maxEdgePx.toFloat() / maxOf(bmp.width, bmp.height))
            val out = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
            val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            images.incrementAndGet()
            return Outcome.Ok(ImageInput("image/jpeg", Base64.getEncoder().encodeToString(bytes), out.width, out.height, displayName))
        }

        override suspend fun importFile(fileName: String, input: InputStream, cwd: String, maxBytes: Long): Outcome<Attachment.FileRef> =
            Outcome.Err(ErrCode.UNKNOWN, "本用例不导入文件")
    }

    private lateinit var processor: BitmapProcessor

    @Before
    fun setUp() {
        processor = BitmapProcessor()
        FilesRuntime.install(FilesDeps(FakeWorkspaceFs(), processor, FakeSessionExporter()))
        ShareInbox.consume()
    }

    @After
    fun tearDown() {
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        ShareInbox.consume()
    }

    /** 其他应用（MediaStore，authority `media`）提供的图片。API 29+ 插入自己的媒体不需要存储权限。 */
    private fun mediaStorePng(w: Int, h: Int): Uri {
        assumeTrue("MediaStore 免权限插入需要 API 29+", Build.VERSION.SDK_INT >= 29)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "pigo-share-test-${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/pigo-test")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        inserted += uri
        ctx.contentResolver.openOutputStream(uri)!!.use { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return uri
    }

    private fun shareIntent(vararg uris: Uri, text: String? = null) = Intent(ctx, ShareTargetActivity::class.java).apply {
        if (uris.size == 1) { action = Intent.ACTION_SEND; putExtra(Intent.EXTRA_STREAM, uris[0]) }
        else { action = Intent.ACTION_SEND_MULTIPLE; putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.toList())) }
        type = "image/png"
        text?.let { putExtra(Intent.EXTRA_TEXT, it) }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun awaitDelivery(timeoutMs: Long = 10_000): ShareDelivery {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            ShareInbox.delivery.value?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("ShareTargetActivity 在 ${timeoutMs}ms 内没有交付")
    }

    /** 验收：经真正的 ShareTargetActivity（ActivityScenario 启动）收到图片后产出一个 ImageInput。 */
    @Test
    fun shareTargetActivityTurnsImageIntoImageInput() {
        val uri = mediaStorePng(2000, 1000)
        ActivityScenario.launch<ShareTargetActivity>(shareIntent(uri, text = "看看这张图")).use {
            val d = awaitDelivery()
            assertEquals("看看这张图", d.text)
            assertTrue(d.errors.toString(), d.errors.isEmpty())
            val img = (d.attachments.single() as Attachment.Image).image
            assertEquals("image/jpeg", img.mimeType)
            assertEquals(1568, img.widthPx)
            assertEquals(784, img.heightPx)
            assertEquals(1, processor.images.get())
        }
    }

    /** 审查第 1 轮：file:// 与本 App 自己 provider 的 Uri 被拒绝，处理器一次都不调用（防借权读取 auth.json 等私有文件）。 */
    @Test
    fun shareTargetRejectsFileAndOwnProviderUris() {
        val secret = File(ctx.filesDir, "secret-auth.json").apply { writeText("{\"key\":\"sk-should-not-leak\"}") }
        val own = FileShare.uriFor(ctx, File(ctx.cacheDir, FileShare.SHARE_DIR).apply { mkdirs() }.resolve("x.png").apply { writeBytes(ByteArray(10)) })
        val fileUri = Uri.fromFile(secret)
        assertTrue(!ShareUriPolicy.isAllowed(ctx, fileUri) && !ShareUriPolicy.isAllowed(ctx, own))
        ActivityScenario.launch<ShareTargetActivity>(shareIntent(fileUri, own, text = "附带文字")).use {
            val d = awaitDelivery()
            assertEquals("附带文字", d.text)
            assertTrue(d.attachments.isEmpty())
            assertTrue(d.errors.toString(), d.errors.any { it.contains("已拒绝不安全的来源") })
            assertEquals(0, processor.images.get())
        }
        secret.delete()
    }

    /** 其他应用（MediaStore Downloads，authority `media`）提供的文本文件，等同 SAF 选择器返回的外部 content:// Uri。 */
    private fun mediaStoreText(name: String, text: String): Uri {
        assumeTrue("MediaStore.Downloads 免权限插入需要 API 29+", Build.VERSION.SDK_INT >= 29)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/pigo-test")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        inserted += uri
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        return uri
    }

    /**
     * 验收：从 SAF（content://）导入一个文本文件后，它出现在文件树里，预览内容一致。
     * Uri 来自 MediaStore（别的应用的 provider），不是本 App 的 FileProvider。
     * 工作区仍是 FakeWorkspaceFs：feature/files 不依赖 core/workspace（构建文件不归 W3），
     * 真实 RootfsWorkspace 经 content:// 导入、列出、预览由 core/workspace 的
     * WorkspaceDeviceTest.importedThroughContentUriShowsInListAndPreviewMatches 覆盖。
     */
    @Test
    fun importedTextFileAppearsInTreeAndPreviewMatches() {
        val text = "第一行\nsecond line\n"
        val name = "pigo-notes-${System.nanoTime()}.txt"
        val uri = mediaStoreText(name, text)
        assertEquals("content", uri.scheme)
        assertTrue("应是别的应用的 provider（media），不是本 App 的 FileProvider", uri.authority == MediaStore.AUTHORITY && uri.authority != FileShare.authority(ctx))
        val fs = FakeWorkspaceFs()
        val controller = FilesController(fs, CoroutineScope(Dispatchers.Main), "/root/pi-cwd")
        compose.setContent { FilesBrowserScreen(controller, onOpenFile = {}, onOpenGit = {}, onClose = {}) }
        compose.waitUntil(5_000) { !controller.state.value.loading }
        compose.runOnIdle { controller.import(listOf(name to { ctx.contentResolver.openInputStream(uri) })) }
        compose.waitUntil(5_000) { controller.state.value.entries.any { it.name == name } }
        compose.onNodeWithTag("files.row:$name").assertIsDisplayed()
        val p = runBlocking { (fs.preview("/root/pi-cwd/$name") as Outcome.Ok).value } as FilePreview.Text
        assertEquals(text, p.chunk.text)
    }

    /** 验收：1 万行日志在输出查看器里连续滑动不卡（慢帧 < 5%，且必须真的统计到帧），搜索能定位。 */
    @Test
    fun tenThousandLineOutputScrollsSmoothlyAndSearchJumps() {
        val text = (1..10_000).joinToString("\n") { "\u001B[3${it % 8}mline $it\u001B[0m — some build output with path /root/pi-cwd/src/File$it.kt" }
        val frames = AtomicInteger()
        val janky = AtomicInteger()
        compose.setContent {
            val view = LocalView.current
            DisposableEffect(view) {
                val stats = JankStats.createAndTrack((view.context as Activity).window) { f ->
                    frames.incrementAndGet()
                    if (f.isJank) janky.incrementAndGet()
                }
                onDispose { stats.isTrackingEnabled = false }
            }
            OutputViewerScreen(title = "bash", text = text, onBack = {})
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTagExists("output.lines") }
        compose.waitForIdle()
        frames.set(0); janky.set(0)
        // 真实手势连续滑动（不是 performScrollToIndex 的跳转）。
        repeat(40) {
            compose.onNodeWithTag("output.lines").performTouchInput { swipeUp(durationMillis = 150) }
        }
        compose.waitForIdle()
        Thread.sleep(500) // 等 JankStats 回调落地
        assertTrue("没有统计到任何帧，测量无效", frames.get() > 0)
        val ratio = janky.get().toDouble() / frames.get()
        assertTrue("慢帧 ${janky.get()}/${frames.get()}", ratio < 0.05)

        compose.onNodeWithTag("output.searchButton").performClick()
        compose.onNodeWithTag("output.search").performTextInput("line 7777 ")
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("output.hits").assertTextEquals("1/1"); true }.getOrDefault(false) }
        compose.waitForIdle()
        compose.onNodeWithText("line 7777 — some build output", substring = true).assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagExists(tag: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
}
