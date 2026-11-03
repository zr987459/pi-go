package io.github.zr987459.pigo.core.store

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * KeystoreSecretStore 的 JVM 单测（不依赖 Android Keystore）：
 * 加解密与 DataStore 读写都在注入的 IO 调度器上执行，调用线程不被占用。
 */
class KeystoreSecretStoreJvmTest {
    /** 纯 JCE 的 AES-GCM，并记录每次调用所在线程。 */
    private class RecordingCipher : SecretCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val threads = mutableListOf<String>()
        override fun encrypt(plain: ByteArray): ByteArray {
            threads += Thread.currentThread().name
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return iv + c.doFinal(plain)
        }
        override fun decrypt(blob: ByteArray): ByteArray {
            threads += Thread.currentThread().name
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
            return c.doFinal(blob, 12, blob.size - 12)
        }
    }

    private lateinit var dir: File
    private val ioThread = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-io") }
    private val io = ioThread.asCoroutineDispatcher()
    private val dsScope = CoroutineScope(SupervisorJob() + io)

    @Before fun setUp() { dir = Files.createTempDirectory("pigo-secrets").toFile() }

    @After fun tearDown() {
        dsScope.cancel()
        io.close()
        dir.deleteRecursively()
    }

    private fun newStore(cipher: SecretCipher): Pair<KeystoreSecretStore, File> {
        val file = File(dir, "s.preferences_pb")
        val ds = PreferenceDataStoreFactory.create(scope = dsScope) { file }
        return KeystoreSecretStore(ds, cipher, io) to file
    }

    @Test fun cryptoRunsOnInjectedIoDispatcher() = runBlocking {
        val cipher = RecordingCipher()
        val (store, _) = newStore(cipher)
        val caller = Thread.currentThread().name
        store.put("key.deepseek", "sk-test-0123456789ABCD")
        assertEquals("sk-test-0123456789ABCD", store.get("key.deepseek"))
        assertEquals("ABCD", store.last4("key.deepseek"))
        assertTrue(cipher.threads.isNotEmpty())
        assertTrue("加解密应在 IO 线程：${cipher.threads}", cipher.threads.all { it.substringBefore(" @coroutine") == "fake-io" })
        assertFalse(cipher.threads.contains(caller))
    }

    @Test fun roundTripRemoveAndNoPlaintextOnDisk() = runBlocking {
        val (store, file) = newStore(RecordingCipher())
        val key = "sk-test-0123456789ABCD"
        store.put("key.deepseek", key)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(key))
        store.put("key.deepseek", "sk-another-key-9999")
        assertEquals("sk-another-key-9999", store.get("key.deepseek"))
        store.remove("key.deepseek")
        assertNull(store.get("key.deepseek"))
        assertNull(store.get("never.set"))
    }

    @Test fun undecryptableBlobIsTreatedAsUnset() = runBlocking {
        val file = File(dir, "s.preferences_pb")
        val ds = PreferenceDataStoreFactory.create(scope = dsScope) { file }
        KeystoreSecretStore(ds, RecordingCipher(), io).put("key.x", "sk-aaaaaaaaaaaa")
        // 换一把密钥（模拟系统清掉了 Keystore 密钥）
        assertNull(KeystoreSecretStore(ds, RecordingCipher(), io).get("key.x"))
    }
}
