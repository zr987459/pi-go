package io.github.zr987459.pigo.core.store

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** SecretStore 仪器测试：Android Keystore AES-GCM 加密往返（DESIGN.md §6）。 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun roundTripAndCiphertextIsNotPlain() = runBlocking {
        val file = File(ctx.filesDir, "datastore/test_secrets_${System.nanoTime()}.preferences_pb")
        val ds = PreferenceDataStoreFactory.create { file }
        val store = KeystoreSecretStore(ds, KeystoreCipher("pigo.test.${System.nanoTime()}"))
        val key = "sk-test-0123456789ABCD"

        store.put("key.deepseek", key)
        assertEquals(key, store.get("key.deepseek"))
        assertEquals("ABCD", store.last4("key.deepseek"))
        // 落盘内容里不出现明文
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(key))

        store.put("key.deepseek", "sk-another-key-9999")
        assertEquals("sk-another-key-9999", store.get("key.deepseek"))

        store.remove("key.deepseek")
        assertNull(store.get("key.deepseek"))
        assertNull(store.get("never.set"))
        file.delete()
    }

    @Test fun eachEncryptionUsesFreshIv() {
        val c = KeystoreCipher("pigo.test.iv.${System.nanoTime()}")
        val a = c.encrypt("same".toByteArray())
        val b = c.encrypt("same".toByteArray())
        assertNotEquals(a.toList(), b.toList())
        assertEquals("same", String(c.decrypt(a)))
    }
}
