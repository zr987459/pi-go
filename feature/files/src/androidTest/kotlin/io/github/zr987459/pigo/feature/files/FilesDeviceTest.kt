package io.github.zr987459.pigo.feature.files

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.metrics.performance.JankStats
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zr987459.pigo.contract.Attachment
import io.github.zr987459.pigo.contract.FilePreview
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.fake.FakeAttachmentProcessor
import io.github.zr987459.pigo.contract.fake.FakeWorkspaceFs
import io.github.zr987459.pigo.feature.files.browser.FilesController
import io.github.zr987459.pigo.feature.files.output.OutputViewerScreen
import io.github.zr987459.pigo.feature.files.platform.FileShare
import io.github.zr987459.pigo.feature.files.share.ShareReceiver
import io.github.zr987459.pigo.feature.files.ui.FilesBrowserScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class FilesDeviceTest {
    @get:Rule
    val compose = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** 验收：Share Target 收到图片后产出一个 ImageInput。 */
    @Test
    fun shareTargetTurnsImageIntoImageInput() = runBlocking {
        val dir = File(ctx.cacheDir, FileShare.SHARE_DIR).apply { mkdirs() }
        val png = File(dir, "shot.png")
        png.outputStream().use { Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileShare.uriFor(ctx, png)
        val intent = Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_TEXT, "看看这张图") }
        val payload = ShareReceiver.parse(intent)!!
        assertEquals(listOf(uri), payload.uris)
        val d = ShareReceiver.process(ctx, payload, FakeAttachmentProcessor(), "/root/pi-cwd")
        assertEquals("看看这张图", d.text)
        assertTrue(d.errors.isEmpty())
        val img = (d.attachments.single() as Attachment.Image).image
        assertEquals("image/png", img.mimeType)
        assertTrue(img.base64Data.isNotEmpty())
    }

    /** 验收：从 SAF（content://）导入一个文本文件后，它出现在文件树里，预览内容一致。 */
    @Test
    fun importedTextFileAppearsInTreeAndPreviewMatches() {
        val dir = File(ctx.cacheDir, FileShare.SHARE_DIR).apply { mkdirs() }
        val src = File(dir, "notes.txt").apply { writeText("第一行\nsecond line\n") }
        val uri = FileShare.uriFor(ctx, src)
        val fs = FakeWorkspaceFs()
        val controller = FilesController(fs, CoroutineScope(Dispatchers.Main), "/root/pi-cwd")
        compose.setContent { FilesBrowserScreen(controller, onOpenFile = {}, onOpenGit = {}, onClose = {}) }
        compose.runOnIdle { controller.import(listOf("notes.txt" to { ctx.contentResolver.openInputStream(uri) })) }
        compose.waitUntil(5_000) { controller.state.value.entries.any { it.name == "notes.txt" } }
        compose.onNodeWithTag("files.row:notes.txt").assertIsDisplayed()
        val p = runBlocking { (fs.preview("/root/pi-cwd/notes.txt") as Outcome.Ok).value } as FilePreview.Text
        assertEquals(src.readText(), p.chunk.text)
    }

    /** 验收：1 万行日志在输出查看器里滚动不卡（慢帧 < 5%），搜索能定位。 */
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
        frames.set(0); janky.set(0)
        for (i in 0..10_000 step 250) compose.onNodeWithTag("output.lines").performScrollToIndex(i.coerceAtMost(9_999))
        compose.waitForIdle()
        val ratio = if (frames.get() == 0) 0.0 else janky.get().toDouble() / frames.get()
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
