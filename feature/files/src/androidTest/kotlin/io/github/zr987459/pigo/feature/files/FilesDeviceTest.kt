package io.github.zr987459.pigo.feature.files

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.metrics.performance.JankStats
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
 * 系统分享入口（ShareTargetActivity）已按 DESIGN-r3 X10 删除，其用例一并移除。
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
    }

    @After
    fun tearDown() {
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
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
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("output.lines").fetchSemanticsNodes().isNotEmpty() }
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

}
