package com.string1225.pocketpilot.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores only AES-GCM ciphertext in SharedPreferences. The non-exportable AES
 * key is generated and retained by Android Keystore; plaintext credentials are
 * never written to disk or logged.
 */
class AndroidKeystoreCredentialStore(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : SecureCredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )
    private val lock = Any()

    override fun put(credentialId: String, secret: CharArray) = synchronized(lock) {
        validateCredentialId(credentialId)
        require(secret.isNotEmpty() && secret.size <= MAX_SECRET_CHARACTERS) {
            "Credential must contain between 1 and $MAX_SECRET_CHARACTERS characters"
        }
        require(secret.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "Credential contains unsupported control characters"
        }

        val plaintext = secret.concatToString().toByteArray(StandardCharsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            cipher.updateAAD(credentialId.toByteArray(StandardCharsets.UTF_8))
            val ciphertext = cipher.doFinal(plaintext)
            val record = listOf(
                RECORD_VERSION,
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            ).joinToString(RECORD_SEPARATOR)
            if (!preferences.edit().putString(preferenceKey(credentialId), record).commit()) {
                throw CredentialStoreException(
                    "CREDENTIAL_WRITE_FAILED",
                    "Encrypted credential could not be persisted",
                )
            }
        } catch (error: CredentialStoreException) {
            throw error
        } catch (error: Exception) {
            throw CredentialStoreException(
                "CREDENTIAL_ENCRYPTION_FAILED",
                "Credential encryption failed",
                error,
            )
        } finally {
            plaintext.fill(0)
        }
    }

    override fun get(credentialId: String): CharArray? = synchronized(lock) {
        validateCredentialId(credentialId)
        val record = preferences.getString(preferenceKey(credentialId), null) ?: return@synchronized null
        val parts = record.split(RECORD_SEPARATOR, limit = 3)
        if (parts.size != 3 || parts[0] != RECORD_VERSION) {
            throw CredentialStoreException("CREDENTIAL_CORRUPTED", "Encrypted credential is invalid")
        }
        val iv: ByteArray
        val ciphertext: ByteArray
        try {
            iv = Base64.decode(parts[1], Base64.NO_WRAP)
            ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw CredentialStoreException("CREDENTIAL_CORRUPTED", "Encrypted credential is invalid", error)
        }
        if (iv.size !in MIN_GCM_IV_BYTES..MAX_GCM_IV_BYTES || ciphertext.size > MAX_CIPHERTEXT_BYTES) {
            iv.fill(0)
            ciphertext.fill(0)
            throw CredentialStoreException("CREDENTIAL_CORRUPTED", "Encrypted credential is invalid")
        }

        var plaintext: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getExistingSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(credentialId.toByteArray(StandardCharsets.UTF_8))
            plaintext = cipher.doFinal(ciphertext)
            val decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plaintext))
                .toString()
                .toCharArray()
            if (decoded.isEmpty() || decoded.size > MAX_SECRET_CHARACTERS) {
                decoded.fill('\u0000')
                throw CredentialStoreException("CREDENTIAL_CORRUPTED", "Encrypted credential is invalid")
            }
            decoded
        } catch (error: CredentialStoreException) {
            throw error
        } catch (error: Exception) {
            throw CredentialStoreException(
                "CREDENTIAL_DECRYPTION_FAILED",
                "Credential decryption failed",
                error,
            )
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
            plaintext?.fill(0)
        }
    }

    override fun contains(credentialId: String): Boolean = synchronized(lock) {
        validateCredentialId(credentialId)
        preferences.contains(preferenceKey(credentialId))
    }

    override fun remove(credentialId: String) = synchronized(lock) {
        validateCredentialId(credentialId)
        if (!preferences.edit().remove(preferenceKey(credentialId)).commit()) {
            throw CredentialStoreException(
                "CREDENTIAL_DELETE_FAILED",
                "Encrypted credential could not be removed",
            )
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val existing = loadSecretKey()
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(AES_KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun getExistingSecretKey(): SecretKey = loadSecretKey()
        ?: throw CredentialStoreException(
            "CREDENTIAL_KEY_MISSING",
            "Encrypted credential key is unavailable",
        )

    private fun loadSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }

    private fun preferenceKey(credentialId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(credentialId.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP or Base64.URL_SAFE)
            .trimEnd('=')
            .also { digest.fill(0) }
    }

    private fun validateCredentialId(credentialId: String) {
        require(credentialId.isNotBlank() && credentialId.length <= MAX_CREDENTIAL_ID_CHARACTERS) {
            "Credential id must contain between 1 and $MAX_CREDENTIAL_ID_CHARACTERS characters"
        }
        require(credentialId.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "Credential id contains unsupported control characters"
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val MIN_GCM_IV_BYTES = 12
        private const val MAX_GCM_IV_BYTES = 32
        private const val MAX_CIPHERTEXT_BYTES = 64 * 1024
        private const val MAX_SECRET_CHARACTERS = 16 * 1024
        private const val MAX_CREDENTIAL_ID_CHARACTERS = 256
        private const val RECORD_VERSION = "v1"
        private const val RECORD_SEPARATOR = "."
        private const val DEFAULT_KEY_ALIAS = "pocketpilot.credentials.aes.v1"
        private const val DEFAULT_PREFERENCES_NAME = "pocketpilot.credentials.v1"
    }
}
