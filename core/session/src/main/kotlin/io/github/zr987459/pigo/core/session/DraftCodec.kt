package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.session.BashMode
import io.github.zr987459.pigo.contract.session.DraftImage
import io.github.zr987459.pigo.contract.session.DraftState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 共享草稿 ↔ `DraftStore` 里的字符串（审查第 1 轮 I1：草稿按会话持久化，进程被杀可恢复）。
 *
 * - 只有文字时存纯文字，与旧版 `drafts.json` 完全兼容（DESIGN-r3 §8.2 第 7 条：须兼容读取旧格式）；
 * - 带光标、`@` 引用、`!` 模式、图片、「已撤回」芯片时存 `[MARK] + JSON`；
 * - 读不认识的内容一律当纯文字，不丢字。
 */
object DraftCodec {
    const val MARK = "\u0001pigo-draft-v1\u0001"

    fun encode(d: DraftState): String {
        val n = d.normalized()
        val plain = n.fileRefs.isEmpty() && n.images.isEmpty() && n.recalled.isEmpty() && n.bashMode == BashMode.NONE &&
            n.selectionStart == n.text.length && n.selectionEnd == n.text.length
        if (plain) return n.text
        return MARK + buildJsonObject {
            put("text", n.text)
            put("selStart", n.selectionStart)
            put("selEnd", n.selectionEnd)
            put("fileRefs", JsonArray(n.fileRefs.map { JsonPrimitive(it) }))
            put("bash", n.bashMode.name)
            put("recalled", JsonArray(n.recalled.map { JsonPrimitive(it) }))
            put("images", JsonArray(n.images.map { i -> buildJsonObject { put("uri", i.uri); i.mimeType?.let { put("mime", it) }; i.name?.let { put("name", it) } } }))
        }.toString()
    }

    fun decode(s: String?): DraftState {
        if (s.isNullOrEmpty()) return DraftState.EMPTY
        if (!s.startsWith(MARK)) return DraftState(text = s)
        return runCatching {
            val o = Json.parseToJsonElement(s.removePrefix(MARK)).jsonObject
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun list(k: String) = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val text = str("text").orEmpty()
            DraftState(
                text = text,
                selectionStart = (o["selStart"] as? JsonPrimitive)?.intOrNull ?: text.length,
                selectionEnd = (o["selEnd"] as? JsonPrimitive)?.intOrNull ?: text.length,
                images = (o["images"] as? JsonArray)?.mapNotNull { e ->
                    val io = e as? JsonObject ?: return@mapNotNull null
                    val uri = (io["uri"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    DraftImage(uri, (io["mime"] as? JsonPrimitive)?.contentOrNull, (io["name"] as? JsonPrimitive)?.contentOrNull)
                }.orEmpty(),
                fileRefs = list("fileRefs"),
                bashMode = runCatching { BashMode.valueOf(str("bash") ?: "NONE") }.getOrDefault(BashMode.NONE),
                recalled = list("recalled"),
            ).normalized()
        }.getOrElse { DraftState(text = s.removePrefix(MARK)) }
    }

    /** 草稿要不要落盘：什么都没有时删掉这个会话的键。 */
    fun isBlank(d: DraftState) = d.isEmpty
}
