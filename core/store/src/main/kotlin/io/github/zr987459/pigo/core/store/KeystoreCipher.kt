package io.github.zr987459.pigo.core.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM，密钥由 Android Keystore 生成且不可导出（DESIGN.md §1.1：`security-crypto` 已弃用，不用）。
 * 密文格式：`[12 字节 IV][密文+16 字节 tag]`。
 *
 * - [SecretKey] 句柄首次用到时取一次并缓存（加载 AndroidKeyStore、查找/生成密钥都较慢）；
 *   密钥被系统作废（[KeyPermanentlyInvalidatedException]）时丢弃缓存，下次重新获取。
 * - 本类的方法是阻塞调用，**不要在主线程直接调用**；[KeystoreSecretStore] 已统一切到 IO 调度器。
 */
class KeystoreCipher(private val keyAlias: String = "pigo.secrets.v1") : SecretCipher {
    @Volatile private var cached: SecretKey? = null
    private val lock = Any()

    private fun key(): SecretKey = cached ?: synchronized(lock) {
        cached ?: loadOrCreate().also { cached = it }
    }

    private fun loadOrCreate(): SecretKey {
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

    private inline fun <T> withKey(block: (SecretKey) -> T): T = try {
        block(key())
    } catch (e: KeyPermanentlyInvalidatedException) {
        cached = null
        throw e
    }

    override fun encrypt(plain: ByteArray): ByteArray = withKey { k ->
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k)
        c.iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray = withKey { k ->
        require(blob.size > IV_LEN) { "密文损坏" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, blob, 0, IV_LEN))
        c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    private companion object { const val IV_LEN = 12 }
}
