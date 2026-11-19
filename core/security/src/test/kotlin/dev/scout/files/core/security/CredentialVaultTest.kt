package dev.scout.files.core.security

import android.content.Context
import android.content.SharedPreferences
import dev.scout.files.core.model.ServerCredentials
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class CredentialVaultTest {

    private lateinit var inMemoryVault: InMemoryCredentialVault
    private lateinit var keystoreVault: KeystoreCredentialVault
    private lateinit var mockContext: Context
    private lateinit var fakeSharedPreferences: FakeSharedPreferences

    @Before
    fun setup() {
        inMemoryVault = InMemoryCredentialVault()
        fakeSharedPreferences = FakeSharedPreferences()
        mockContext = mockk(relaxed = true) {
            every { getSharedPreferences(any(), any()) } returns fakeSharedPreferences
        }
        keystoreVault = KeystoreCredentialVault(mockContext)
    }

    // --- ServerCredentialsSerializer Tests ---

    @Test
    fun testSerializerRoundTripWithFullCredentials() {
        val original = ServerCredentials(
            password = "SuperSecretPassword!@#123",
            privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAACFwAAAAdzc2gtcn\n-----END OPENSSH PRIVATE KEY-----",
            privateKeyPassphrase = "passphrase-for-ssh-key-456",
            oauthAccessToken = "ya29.a0AfH6SMD_ACCESS_TOKEN",
            oauthRefreshToken = "1//04_REFRESH_TOKEN",
            oauthClientId = "scout-app-client-id.apps.googleusercontent.com",
            s3SecretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        )

        val serialized = ServerCredentialsSerializer.serialize(original)
        assertTrue(serialized.isNotEmpty())

        val deserialized = ServerCredentialsSerializer.deserialize(serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun testSerializerRoundTripWithNullFields() {
        val empty = ServerCredentials()
        val serialized = ServerCredentialsSerializer.serialize(empty)
        val deserialized = ServerCredentialsSerializer.deserialize(serialized)
        assertEquals(empty, deserialized)
    }

    @Test
    fun testSerializerRoundTripWithPartialFields() {
        val partial = ServerCredentials(
            password = "only-password"
        )
        val serialized = ServerCredentialsSerializer.serialize(partial)
        val deserialized = ServerCredentialsSerializer.deserialize(serialized)
        assertEquals(partial, deserialized)
        assertEquals("only-password", deserialized.password)
        assertNull(deserialized.privateKeyPem)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSerializerInvalidVersionThrowsException() {
        val invalidBytes = byteArrayOf(0, 0, 0, 99, 0, 0) // version 99
        ServerCredentialsSerializer.deserialize(invalidBytes)
    }

    // --- InMemoryCredentialVault Tests ---

    @Test
    fun testInMemoryCredentialVaultCrud() = runTest {
        val creds1 = ServerCredentials(password = "pass1")
        val creds2 = ServerCredentials(s3SecretKey = "secret2")

        inMemoryVault.storeCredentials("conn-1", creds1)
        inMemoryVault.storeCredentials("conn-2", creds2)

        assertEquals(creds1, inMemoryVault.getCredentials("conn-1"))
        assertEquals(creds2, inMemoryVault.getCredentials("conn-2"))
        assertNull(inMemoryVault.getCredentials("conn-unknown"))

        // Overwrite
        val updatedCreds1 = ServerCredentials(password = "pass1-updated")
        inMemoryVault.storeCredentials("conn-1", updatedCreds1)
        assertEquals(updatedCreds1, inMemoryVault.getCredentials("conn-1"))

        // Delete
        inMemoryVault.deleteCredentials("conn-1")
        assertNull(inMemoryVault.getCredentials("conn-1"))
        assertNotNull(inMemoryVault.getCredentials("conn-2"))

        // Clear All
        inMemoryVault.clearAll()
        assertNull(inMemoryVault.getCredentials("conn-2"))
    }

    // --- KeystoreCredentialVault Tests ---

    @Test
    fun testKeystoreCredentialVaultEncryptDecrypt() = runTest {
        val creds = ServerCredentials(
            password = "mypassword",
            privateKeyPem = "-----BEGIN RSA PRIVATE KEY-----\nMIICXAIBAAKCAQEA0...\n-----END RSA PRIVATE KEY-----",
            privateKeyPassphrase = "pass"
        )

        keystoreVault.storeCredentials("server-123", creds)

        // Raw stored value in SharedPreferences must NOT be plaintext!
        val storedRaw = fakeSharedPreferences.getString("server-123", null)
        assertNotNull(storedRaw)
        assertTrue(storedRaw!!.isNotEmpty())
        assertTrue(!storedRaw.contains("mypassword"))
        assertTrue(!storedRaw.contains("BEGIN RSA PRIVATE KEY"))

        // Decrypt
        val decrypted = keystoreVault.getCredentials("server-123")
        assertNotNull(decrypted)
        assertEquals(creds, decrypted)

        // Delete
        keystoreVault.deleteCredentials("server-123")
        assertNull(keystoreVault.getCredentials("server-123"))
    }

    @Test
    fun testKeystoreCredentialVaultCorruptedDataReturnsNull() = runTest {
        fakeSharedPreferences.edit().putString("corrupt-conn", "not-valid-base64").apply()
        val result = keystoreVault.getCredentials("corrupt-conn")
        assertNull(result)
    }

    @Test
    fun testKeystoreCredentialVaultClearAll() = runTest {
        keystoreVault.storeCredentials("c1", ServerCredentials(password = "p1"))
        keystoreVault.storeCredentials("c2", ServerCredentials(password = "p2"))

        keystoreVault.clearAll()
        assertNull(keystoreVault.getCredentials("c1"))
        assertNull(keystoreVault.getCredentials("c2"))
    }

    // --- Fake SharedPreferences implementation for hermetic JVM testing ---
    private class FakeSharedPreferences : SharedPreferences {
        private val map = ConcurrentHashMap<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Editor(private val target: ConcurrentHashMap<String, Any>) : SharedPreferences.Editor {
            private val temp = HashMap<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) temp[key] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) temp[key] = this // sentinel for removal
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearFlag) target.clear()
                temp.forEach { (k, v) ->
                    if (v === this) {
                        target.remove(k)
                    } else if (v != null) {
                        target[k] = v
                    } else {
                        target.remove(k)
                    }
                }
            }
        }
    }
}
