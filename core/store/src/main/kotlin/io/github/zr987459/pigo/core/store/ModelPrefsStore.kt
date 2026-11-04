package io.github.zr987459.pigo.core.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * 模型选择相关的界面偏好（宿主私有文件，不进容器、不含任何凭据）：
 * - 费用一律照 pi 显示美元，这里不再保存任何换算设置（DESIGN-r3 X2；旧文件里的相关键读取时忽略、下次保存时丢弃）；
 * - 最近使用的模型（最多 [MAX_RECENT] 个，选择页置顶）。
 */
class ModelPrefsStore(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    data class Prefs(
        /** `provider/modelId`，最近的在前。 */
        val recent: List<String> = emptyList(),
    )

    private val mutex = Mutex()
    private val _prefs = MutableStateFlow(load())
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()

    suspend fun touchRecent(provider: String, modelId: String) {
        val key = "$provider/$modelId"
        update { p -> p.copy(recent = (listOf(key) + p.recent.filter { it != key }).take(MAX_RECENT)) }
    }

    private suspend fun update(f: (Prefs) -> Prefs) = mutex.withLock {
        val next = f(_prefs.value)
        withContext(io) { save(next) }
        _prefs.value = next
    }

    private fun load(): Prefs {
        val o = try {
            JsonFiles.readObject(file)
        } catch (_: Exception) {
            return Prefs()
        }
        return Prefs(
            recent = (o["recent"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.take(MAX_RECENT) ?: emptyList(),
        )
    }

    private fun save(p: Prefs) {
        JsonFiles.writeObject(
            file,
            JsonObject(
                mapOf(
                    "recent" to JsonArray(p.recent.map { JsonPrimitive(it) }),
                ),
            ),
        )
    }

    companion object {
        const val MAX_RECENT = 5

    }
}
