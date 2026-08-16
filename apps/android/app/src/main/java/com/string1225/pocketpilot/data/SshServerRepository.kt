package com.string1225.pocketpilot.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.string1225.pocketpilot.integrations.ssh.SshCredentialRef
import com.string1225.pocketpilot.integrations.ssh.SshHostKeyPolicy
import com.string1225.pocketpilot.integrations.ssh.SshServer
import com.string1225.pocketpilot.integrations.ssh.SshStoredCredentialCodec
import com.string1225.pocketpilot.model.RemoteServerProfile
import com.string1225.pocketpilot.model.SshAuthType
import com.string1225.pocketpilot.security.SecureCredentialStore
import java.util.UUID

class SshServerRepository(
    private val database: PocketPilotDatabase,
    private val credentials: SecureCredentialStore,
) {
    private val lock = Any()

    fun list(): List<RemoteServerProfile> = synchronized(lock) { listUnlocked() }

    private fun listUnlocked(): List<RemoteServerProfile> {
        val result = mutableListOf<RemoteServerProfile>()
        database.readableDatabase.query(
            "ssh_servers",
            COLUMNS,
            null,
            null,
            null,
            null,
            "name COLLATE NOCASE ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val credentialId = cursor.getString(5)
                result += RemoteServerProfile(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    host = cursor.getString(2),
                    port = cursor.getInt(3),
                    username = cursor.getString(4),
                    credentialId = credentialId,
                    authType = requireNotNull(SshAuthType.entries.firstOrNull { it.value == cursor.getString(6) }) {
                        "Stored SSH authentication type is invalid"
                    },
                    hostKeyFingerprint = cursor.getString(7),
                    description = cursor.getString(8),
                    hasCredential = credentials.contains(credentialId),
                )
            }
        }
        return result
    }

    fun find(idOrName: String): RemoteServerProfile? = synchronized(lock) {
        val profiles = listUnlocked()
        profiles.firstOrNull { it.id == idOrName }
            ?: profiles.firstOrNull { it.name.equals(idOrName, ignoreCase = true) }
    }

    fun save(profile: RemoteServerProfile, secret: CharArray? = null): RemoteServerProfile = synchronized(lock) {
        validate(profile)
        require(listUnlocked().none {
            it.id != profile.id && it.name.equals(profile.name.trim(), ignoreCase = true)
        }) { "SSH server name is already in use" }
        val previous = findByIdUnlocked(profile.id)
        require(previous != null || secret != null) { "SSH credential is required for a new server" }
        require(previous == null || previous.authType == profile.authType || secret != null) {
            "A new SSH credential is required when authentication type changes"
        }

        var previousSecret: CharArray? = null
        var storedSecret: CharArray? = null
        try {
            val hadCredential = credentials.contains(profile.credentialId)
            previousSecret = if (secret != null && hadCredential) {
                credentials.get(profile.credentialId)
            } else {
                null
            }
            storedSecret = secret?.let {
                when (profile.authType) {
                    SshAuthType.PASSWORD -> it.copyOf()
                    SshAuthType.PRIVATE_KEY -> SshStoredCredentialCodec.encodePrivateKey(it)
                }
            }
            if (storedSecret != null) credentials.put(profile.credentialId, storedSecret)
            require(credentials.contains(profile.credentialId)) { "SSH credential is required" }
            check(
                database.writableDatabase.insertWithOnConflict(
                    "ssh_servers",
                    null,
                    profile.toContentValues(),
                    SQLiteDatabase.CONFLICT_REPLACE,
                ) != -1L,
            ) { "Unable to save SSH server" }
        } catch (failure: Throwable) {
            if (storedSecret != null) {
                runCatching {
                    if (previousSecret != null) {
                        credentials.put(profile.credentialId, previousSecret)
                    } else {
                        credentials.remove(profile.credentialId)
                    }
                }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        } finally {
            storedSecret?.fill('\u0000')
            previousSecret?.fill('\u0000')
        }
        profile.copy(hasCredential = true)
    }

    fun delete(id: String) = synchronized(lock) {
        val profile = findByIdUnlocked(id) ?: return@synchronized
        val backup = credentials.get(profile.credentialId)
        var credentialRemoved = false
        try {
            if (backup != null) {
                credentials.remove(profile.credentialId)
                credentialRemoved = true
            }
            check(database.writableDatabase.delete("ssh_servers", "id = ?", arrayOf(profile.id)) == 1) {
                "Unable to delete SSH server"
            }
        } catch (failure: Throwable) {
            if (credentialRemoved && backup != null) {
                runCatching { credentials.put(profile.credentialId, backup) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
            throw failure
        } finally {
            backup?.fill('\u0000')
        }
    }

    fun newProfile(
        name: String,
        host: String,
        port: Int,
        username: String,
        authType: SshAuthType,
        hostKeyFingerprint: String,
        description: String,
    ): RemoteServerProfile {
        val id = UUID.randomUUID().toString()
        return RemoteServerProfile(
            id = id,
            name = name,
            host = host,
            port = port,
            username = username,
            authType = authType,
            hostKeyFingerprint = hostKeyFingerprint,
            credentialId = "ssh.$id",
            description = description,
        )
    }

    private fun validate(profile: RemoteServerProfile) {
        require(runCatching { UUID.fromString(profile.id) }.isSuccess) { "SSH server id is invalid" }
        require(profile.name.trim().length in 1..120) { "SSH server name is invalid" }
        require(profile.host.trim().length in 1..253) { "SSH host is invalid" }
        require(profile.port in 1..65535) { "SSH port is invalid" }
        require(profile.username.trim().length in 1..128) { "SSH username is invalid" }
        require(profile.credentialId == "ssh.${profile.id}") { "SSH credential reference is invalid" }
        require(profile.description.length <= 4_096) { "SSH server description is too long" }
        val credential = when (profile.authType) {
            SshAuthType.PASSWORD -> SshCredentialRef.Password(profile.credentialId)
            SshAuthType.PRIVATE_KEY -> SshCredentialRef.PrivateKey(profile.credentialId)
        }
        SshServer(
            id = profile.id,
            name = profile.name.trim(),
            host = profile.host.trim(),
            port = profile.port,
            username = profile.username.trim(),
            credential = credential,
            hostKeyPolicy = SshHostKeyPolicy.Sha256Fingerprint(profile.hostKeyFingerprint.trim()),
            description = profile.description.trim(),
        )
    }

    private fun findByIdUnlocked(id: String): RemoteServerProfile? = listUnlocked().firstOrNull { it.id == id }

    private fun RemoteServerProfile.toContentValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("name", name.trim())
        put("host", host.trim())
        put("port", port)
        put("username", username.trim())
        put("credential_id", credentialId)
        put("auth_type", authType.value)
        put("host_key_fingerprint", hostKeyFingerprint.trim())
        put("description", description.trim())
    }

    private companion object {
        val COLUMNS = arrayOf(
            "id",
            "name",
            "host",
            "port",
            "username",
            "credential_id",
            "auth_type",
            "host_key_fingerprint",
            "description",
        )
    }
}
