package dev.scout.files.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.scout.files.core.model.ServerCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeystoreCredentialVault @Inject constructor(
    @ApplicationContext private val context: Context
) : CredentialVault {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var fallbackKey: SecretKey? = null

    override suspend fun storeCredentials(
        connectionId: String,
        credentials: ServerCredentials
    ): Unit = withContext(Dispatchers.IO) {
        val plainBytes = ServerCredentialsSerializer.serialize(credentials)
        val secretKey = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plainBytes)

        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

        val encoded = Base64.getEncoder().encodeToString(combined)
        prefs.edit().putString(connectionId, encoded).apply()
    }

    override suspend fun getCredentials(
        connectionId: String
    ): ServerCredentials? = withContext(Dispatchers.IO) {
        val encoded = prefs.getString(connectionId, null) ?: return@withContext null
        return@withContext runCatching {
            val combined = Base64.getDecoder().decode(encoded)
            if (combined.size < GCM_IV_LENGTH_BYTES) return@runCatching null

            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            val ciphertext = ByteArray(combined.size - GCM_IV_LENGTH_BYTES)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH_BYTES)
            System.arraycopy(combined, GCM_IV_LENGTH_BYTES, ciphertext, 0, ciphertext.size)

            val secretKey = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val plainBytes = cipher.doFinal(ciphertext)

            ServerCredentialsSerializer.deserialize(plainBytes)
        }.getOrNull()
    }

    override suspend fun deleteCredentials(
        connectionId: String
    ): Unit = withContext(Dispatchers.IO) {
        prefs.edit().remove(connectionId).apply()
    }

    override suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            } else {
                val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
                entry.secretKey
            }
        } catch (e: Exception) {
            // Fallback for JVM unit tests or environments without AndroidKeyStore
            fallbackKey ?: synchronized(this) {
                fallbackKey ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also {
                    fallbackKey = it
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "scout_credentials_vault"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "scout_credential_vault_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
