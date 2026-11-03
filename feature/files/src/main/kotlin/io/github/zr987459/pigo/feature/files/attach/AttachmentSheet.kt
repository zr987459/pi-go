package io.github.zr987459.pigo.feature.files.attach

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zr987459.pigo.contract.Attachment
import io.github.zr987459.pigo.feature.files.platform.FileShare
import io.github.zr987459.pigo.feature.files.platform.Pickers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64

/**
 * 输入框「+」的附件弹层（C1、C2）：拍照、相册、文件；下方是已选附件（缩略图 / 文件名、处理进度、错误、移除）。
 * W5 把 `onAttachClick` 接到这里，发送时用 [AttachmentController.compose]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentSheet(controller: AttachmentController, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by controller.items.collectAsState()
    val notice by controller.notice.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("attach.sheet")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("添加附件", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { scope.launch { controller.add(sources(ctx, Pickers.takePhoto(ctx)), asImages = true) } }, modifier = Modifier.weight(1f)) { Text("拍照") }
                FilledTonalButton(onClick = { scope.launch { controller.add(sources(ctx, Pickers.pickImages(ctx)), asImages = true) } }, modifier = Modifier.weight(1f).testTag("attach.gallery")) { Text("相册") }
                FilledTonalButton(onClick = { scope.launch { controller.add(sources(ctx, Pickers.pickDocuments(ctx)), asImages = false) } }, modifier = Modifier.weight(1f).testTag("attach.files")) { Text("文件") }
            }
            Text("图片会压缩到长边 1568 像素以内再发送；文件复制到工作目录的 .pigo/uploads/，以 @路径 引用（单个不超过 20 MB）。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            notice?.let {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) {
                    Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
                }
            }
            if (items.isNotEmpty()) {
                LazyRow(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.id }) { p -> AttachmentTile(p, onRemove = { controller.remove(p.id) }) }
                }
            }
            Button(onClick = onDismiss, enabled = items.none { it.busy }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(if (items.any { it.busy }) "处理中…" else "完成")
            }
        }
    }
}

@Composable
fun AttachmentTile(p: PendingAttachment, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(88.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).testTag("attach.tile")) {
        val img = (p.result as? Attachment.Image)?.image
        val thumb by produceState<ImageBitmap?>(null, img) {
            value = img?.let { withContext(Dispatchers.Default) { runCatching { thumbnail(it.base64Data) }.getOrNull() } }
        }
        when {
            thumb != null -> Image(thumb!!, p.displayName, contentScale = ContentScale.Crop, modifier = Modifier.size(88.dp))
            p.busy -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
            else -> Text(
                if (p.error != null) "⚠ " + p.error else "📄 " + p.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = if (p.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(6.dp),
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) { Icon(Icons.Filled.Close, "移除") }
    }
}

private fun thumbnail(b64: String): ImageBitmap? {
    val bytes = Base64.getDecoder().decode(b64)
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 176) s *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}

/** 系统选择器返回的 Uri → [SourceItem]。 */
suspend fun sources(ctx: Context, uris: List<Uri>): List<SourceItem> = withContext(Dispatchers.IO) {
    uris.map { u ->
        val (name, size) = FileShare.displayName(ctx, u)
        SourceItem(name ?: "附件", ctx.contentResolver.getType(u), size) { ctx.contentResolver.openInputStream(u) }
    }
}
