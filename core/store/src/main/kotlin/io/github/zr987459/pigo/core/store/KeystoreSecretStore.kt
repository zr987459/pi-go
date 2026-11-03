package io.github.zr987459.pigo.core.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.zr987459.pigo.contract.SecretStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first

/** 加解密抽象，便于 JVM 单测用纯 JCE 实现替代 Android Keystore。 */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM，密钥由 Android Keystore 生成且不可导出（DESIGN.md §1.1：`security-crypto` 已弃用，不用）。
 * 密文格式：`[12 字节 IV][密文+16 字节 tag]`。
 */
class KeystoreCipher(private val keyAlias: String = "pigo.secrets.v1") : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        return iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_LEN) { "密文损坏" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_LEN))
        return c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    private companion object { const val IV_LEN = 12 }
}

private val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(name = "pigo_secrets")

/**
 * [SecretStore] 实现：密文（Base64）存 DataStore（App 私有目录），明文只在内存里短暂存在。
 * 解密失败（例如系统清掉了 Keystore 密钥）时视为未设置，返回 null，界面会提示重新填写。
 */
class KeystoreSecretStore(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCipher = KeystoreCipher(),
) : SecretStore {

    constructor(context: Context) : this(context.applicationContext.secretsDataStore)

    private fun prefKey(alias: String) = stringPreferencesKey("s.$alias")

    override suspend fun put(alias: String, plain: String) {
        val blob = Base64.encodeToString(cipher.encrypt(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        store.edit { it[prefKey(alias)] = blob }
    }

    override suspend fun get(alias: String): String? {
        val blob = store.data.first()[prefKey(alias)] ?: return null
        return try {
            String(cipher.decrypt(Base64.decode(blob, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun remove(alias: String) {
        store.edit { it.remove(prefKey(alias)) }
    }

    override suspend fun last4(alias: String): String? = KeyRedaction.last4(get(alias))
}
