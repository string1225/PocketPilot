package com.string1225.pocketpilot.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.string1225.pocketpilot.integrations.ssh.SshStoredCredentialCodec
import com.string1225.pocketpilot.model.SshAuthType
import com.string1225.pocketpilot.security.SecureCredentialStore
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SshServerRepositoryTest {
    @Test
    fun privateKeyStorageAndCrossStoreFailuresAreCompensated() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(DATABASE_NAME)
        val database = PocketPilotDatabase(context)
        val credentials = SingleLineCredentialStore()
        val repository = SshServerRepository(database, credentials)
        val profile = repository.newProfile(
            name = "test-server",
            host = "example.com",
            port = 22,
            username = "pilot",
            authType = SshAuthType.PRIVATE_KEY,
            hostKeyFingerprint = validFingerprint(),
            description = "instrumented test",
        )
        val original = pem("dGVzdC1vbmx5LWtleQ==")
        val replacement = pem("cmVwbGFjZW1lbnQta2V5")
        try {
            repository.save(profile, original)
            val stored = requireNotNull(credentials.get(profile.credentialId))
            try {
                assertFalse(stored.any { it == '\r' || it == '\n' || it == '\u0000' })
                val decoded = SshStoredCredentialCodec.decodePrivateKey(stored)
                try {
                    assertArrayEquals(original.toTypedArray(), decoded.toTypedArray())
                } finally {
                    decoded.fill('\u0000')
                }
            } finally {
                stored.fill('\u0000')
            }

            val previous = requireNotNull(credentials.get(profile.credentialId))
            database.writableDatabase.execSQL(
                "CREATE TRIGGER reject_ssh_save BEFORE INSERT ON ssh_servers " +
                    "BEGIN SELECT RAISE(ABORT, 'test save failure'); END",
            )
            runCatching { repository.save(profile.copy(description = "changed"), replacement) }
                .onSuccess { throw AssertionError("Expected repository save to fail") }
            val afterFailedSave = requireNotNull(credentials.get(profile.credentialId))
            try {
                assertArrayEquals(previous.toTypedArray(), afterFailedSave.toTypedArray())
            } finally {
                previous.fill('\u0000')
                afterFailedSave.fill('\u0000')
            }

            database.writableDatabase.execSQL("DROP TRIGGER reject_ssh_save")
            database.writableDatabase.execSQL(
                "CREATE TRIGGER reject_ssh_delete BEFORE DELETE ON ssh_servers " +
                    "BEGIN SELECT RAISE(ABORT, 'test delete failure'); END",
            )
            runCatching { repository.delete(profile.id) }
                .onSuccess { throw AssertionError("Expected repository delete to fail") }
            assertNotNull(repository.find(profile.id))
            assertTrue(credentials.contains(profile.credentialId))
        } finally {
            original.fill('\u0000')
            replacement.fill('\u0000')
            credentials.clear()
            database.close()
            context.deleteDatabase(DATABASE_NAME)
        }
    }

    private fun pem(body: String): CharArray = (
        "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
        ).toCharArray()

    private fun validFingerprint(): String =
        "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32))}"

    private class SingleLineCredentialStore : SecureCredentialStore {
        private val values = mutableMapOf<String, CharArray>()

        override fun put(credentialId: String, secret: CharArray) {
            require(secret.none { it == '\r' || it == '\n' || it == '\u0000' })
            values.remove(credentialId)?.fill('\u0000')
            values[credentialId] = secret.copyOf()
        }

        override fun get(credentialId: String): CharArray? = values[credentialId]?.copyOf()

        override fun contains(credentialId: String): Boolean = credentialId in values

        override fun remove(credentialId: String) {
            values.remove(credentialId)?.fill('\u0000')
        }

        fun clear() {
            values.values.forEach { it.fill('\u0000') }
            values.clear()
        }
    }

    private companion object {
        const val DATABASE_NAME = "pocketpilot.db"
    }
}
