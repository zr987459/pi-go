package io.github.zr987459.pigo.contract

import java.time.Instant
import kotlinx.coroutines.flow.Flow

data class SessionSummary(
    val guestPath: String,
    val id: String,
    val cwd: String,
    val name: String?,
    val preview: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val sizeBytes: Long,
)

interface SessionRepository {
    fun observe(): Flow<List<SessionSummary>>
    suspend fun refresh()

    /** 返回回收目录中的新路径。 */
    suspend fun moveToTrash(guestPath: String): Outcome<String>
    suspend fun restore(trashPath: String): Outcome<String>
    fun observeTrash(): Flow<List<SessionSummary>>
}

enum class PresetKind { PAY_AS_YOU_GO, CODING_PLAN, CUSTOM_OPENAI }

data class ProviderPreset(
    val provider: String,
    val label: String,
    val kind: PresetKind,
    val envVar: String?,
    val defaultBaseUrl: String?,
    val note: String,
)

/** Android Keystore + AES-GCM，只在宿主。 */
interface SecretStore {
    suspend fun put(alias: String, plain: String)
    suspend fun get(alias: String): String?
    suspend fun remove(alias: String)
    suspend fun last4(alias: String): String?
}

interface ProviderConfigStore {
    fun presets(): List<ProviderPreset>

    /** 去空格/去 "Bearer " 后存 SecretStore。 */
    suspend fun setBuiltInKey(provider: String, rawKey: String)

    /** 写 models.json，`apiKey="$PIGO_KEY_<ID>"`。 */
    suspend fun upsertCustom(id: String, baseUrl: String, rawKey: String, modelIds: List<String>)

    /** 合并写 settings.json。 */
    suspend fun setDefault(provider: String, modelId: String, thinking: String?)
    suspend fun configured(): List<String>
}

/** 由 `:core:store` 实现，engine 启动时取。 */
interface LaunchEnvProvider {
    suspend fun env(): Map<String, String>
}

/** 清洗用户粘贴的 Key：去首尾空白与开头的 `Bearer `（DESIGN.md §3.10）。 */
fun sanitizeApiKey(raw: String): String {
    var k = raw.trim()
    if (k.startsWith("Bearer ", ignoreCase = true)) k = k.substring(7).trim()
    return k
}
