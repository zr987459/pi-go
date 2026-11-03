package io.github.zr987459.pigo.core.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.zr987459.pigo.contract.SecretStore
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 加解密抽象，便于 JVM 单测用纯 JCE 实现替代 Android Keystore。实现可以是阻塞的。 */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

private val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(name = "pigo_secrets")

/**
 * [SecretStore] 实现：密文（Base64）存 DataStore（App 私有目录），明文只在内存里短暂存在。
 * 解密失败（例如系统清掉了 Keystore 密钥）时视为未设置，返回 null，界面会提示重新填写。
 *
 * 线程：所有方法内部都切到 [io]（默认 [Dispatchers.IO]）执行 Keystore 加解密和 DataStore 读写，
 * 在主线程调用也不会卡界面。Base64 用 `java.util.Base64`（minSdk 26 可用，JVM 单测也能跑）。
 */
class KeystoreSecretStore(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCipher = KeystoreCipher(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SecretStore {

    constructor(context: Context) : this(context.applicationContext.secretsDataStore)

    private fun prefKey(alias: String) = stringPreferencesKey("s.$alias")

    override suspend fun put(alias: String, plain: String) = withContext(io) {
        val blob = Base64.getEncoder().encodeToString(cipher.encrypt(plain.toByteArray(Charsets.UTF_8)))
        store.edit { it[prefKey(alias)] = blob }
        Unit
    }

    override suspend fun get(alias: String): String? = withContext(io) {
        val blob = store.data.first()[prefKey(alias)] ?: return@withContext null
        try {
            String(cipher.decrypt(Base64.getDecoder().decode(blob)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun remove(alias: String) = withContext(io) {
        store.edit { it.remove(prefKey(alias)) }
        Unit
    }

    override suspend fun last4(alias: String): String? = KeyRedaction.last4(get(alias))
}
