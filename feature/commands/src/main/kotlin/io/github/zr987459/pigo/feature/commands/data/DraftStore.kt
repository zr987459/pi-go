package io.github.zr987459.pigo.feature.commands.data

import io.github.zr987459.pigo.contract.DraftStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 按会话保存的草稿（S7）：`Map<key, draft>` 存宿主文件；key 为会话文件路径，新会话用 [DraftStore.newSessionKey]。
 * 空草稿即删除；最多保留 [MAX_DRAFTS] 条（按最近写入），单条最多 [MAX_CHARS] 字符。
 */
class FileDraftStore(file: File) : DraftStore {
    private val store = JsonFileStore(file)
    private val lock = Any()
    private var cache: LinkedHashMap<String, String>? = null

    private fun loaded(): LinkedHashMap<String, String> = cache ?: LinkedHashMap<String, String>().also { m ->
        for ((k, v) in store.read()) (v as? JsonPrimitive)?.contentOrNull?.let { m[k] = it }
        cache = m
    }

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) { synchronized(lock) { loaded()[key] } }

    override suspend fun put(key: String, draft: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val m = loaded()
            val text = draft.take(MAX_CHARS)
            val prev = m[key]
            if (text.isBlank()) {
                if (prev == null) return@synchronized
                m.remove(key)
            } else {
                if (prev == text) return@synchronized
                m.remove(key)
                m[key] = text
                while (m.size > MAX_DRAFTS) m.remove(m.keys.first())
            }
            flush(m)
        }
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val m = loaded()
            if (m.remove(key) != null) flush(m)
        }
    }

    private fun flush(m: Map<String, String>) = store.write(JsonObject(m.mapValues { JsonPrimitive(it.value) }))

    companion object {
        const val MAX_DRAFTS = 200
        const val MAX_CHARS = 100_000
    }
}
