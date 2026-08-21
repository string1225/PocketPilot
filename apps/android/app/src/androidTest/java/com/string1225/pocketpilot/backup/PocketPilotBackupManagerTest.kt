package com.string1225.pocketpilot.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.string1225.pocketpilot.data.CheckpointRepository
import com.string1225.pocketpilot.data.ConversationRepository
import com.string1225.pocketpilot.data.PocketPilotDatabase
import com.string1225.pocketpilot.data.ProjectRepository
import com.string1225.pocketpilot.data.SettingsRepository
import com.string1225.pocketpilot.data.WorkspaceRepository
import com.string1225.pocketpilot.model.ConversationMessageRole
import com.string1225.pocketpilot.model.TokenUsage
import com.string1225.pocketpilot.runtime.PluginRunCoordinationGate
import com.string1225.pocketpilot.security.CredentialIds
import com.string1225.pocketpilot.security.SecureCredentialStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class PocketPilotBackupManagerTest {
    @Test
    fun roundTripRestoresDatabaseAndFilesButNeverSecrets() = runBlocking {
        val fixture = Fixture()
        try {
            val project = fixture.projects.create("Backup project")
            val conversation = fixture.conversations.create(project.id, "History")
            fixture.workspace.write(project.id, "src/main.txt", "before backup")
            fixture.conversations.appendMessage(
                conversationId = conversation.id,
                role = ConversationMessageRole.ASSISTANT,
                title = "Agent",
                content = "persisted answer",
                tokenUsage = TokenUsage(100, 20, 120, 75),
            )
            fixture.settings.save(
                fixture.settings.load().copy(
                    personalization = "restored persona",
                    maxConcurrentSessions = 5,
                ),
            )
            fixture.insertSshServer("ssh.backup")
            fixture.credentials.put(CredentialIds.DEFAULT_LLM, "llm-secret".toCharArray())
            fixture.credentials.put(CredentialIds.DEFAULT_GIT_TOKEN, "git-secret".toCharArray())
            fixture.credentials.put("ssh.backup", "ssh-secret".toCharArray())

            val exported = fixture.manager.exportTo(Uri.fromFile(fixture.zip))
            assertEquals(1, exported.projectCount)
            val archiveText = ZipInputStream(FileInputStream(fixture.zip)).use { zip ->
                buildString {
                    while (zip.nextEntry != null) append(zip.readBytes().toString(Charsets.ISO_8859_1))
                }
            }
            assertFalse(archiveText.contains("llm-secret"))
            assertFalse(archiveText.contains("git-secret"))
            assertFalse(archiveText.contains("ssh-secret"))

            fixture.workspace.write(project.id, "src/main.txt", "mutated")
            fixture.settings.save(fixture.settings.load().copy(personalization = "mutated"))
            fixture.conversations.delete(conversation.id)

            val imported = fixture.manager.importFrom(Uri.fromFile(fixture.zip))
            assertEquals(1, imported.projectCount)
            assertEquals("before backup", fixture.workspace.read(project.id, "src/main.txt"))
            assertEquals("restored persona", fixture.settings.load().personalization)
            assertEquals(5, fixture.settings.load().maxConcurrentSessions)
            assertEquals("persisted answer", fixture.conversations.listMessages(conversation.id).single().content)
            assertEquals(75L, fixture.conversations.listMessages(conversation.id).single().tokenUsage?.cachedPromptTokens)
            assertFalse(fixture.credentials.contains(CredentialIds.DEFAULT_LLM))
            assertFalse(fixture.credentials.contains(CredentialIds.DEFAULT_GIT_TOKEN))
            assertFalse(fixture.credentials.contains("ssh.backup"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun rejectsZipTraversalWithoutTouchingLiveState() {
        val fixture = Fixture()
        try {
            val project = fixture.projects.create("Safe")
            fixture.workspace.write(project.id, "keep.txt", "sentinel")
            ZipOutputStream(FileOutputStream(fixture.zip)).use { zip ->
                zip.putNextEntry(ZipEntry("../escape"))
                zip.write("bad".toByteArray())
                zip.closeEntry()
            }

            val rejection = runCatching {
                runBlocking { fixture.manager.importFrom(Uri.fromFile(fixture.zip)) }
            }.exceptionOrNull()
            assertTrue(
                "Expected unsafe ZIP path to be rejected, got $rejection",
                rejection is IllegalArgumentException || rejection is java.util.zip.ZipException,
            )
            assertEquals("sentinel", fixture.workspace.read(project.id, "keep.txt"))
            assertFalse(File(fixture.root.parentFile, "escape").exists())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun rejectsInventoryMismatchWithoutTouchingLiveState() {
        val fixture = Fixture()
        try {
            val project = fixture.projects.create("Safe")
            fixture.workspace.write(project.id, "keep.txt", "sentinel")
            val manifest = JSONObject()
                .put("formatVersion", 1)
                .put("packageName", fixture.context.packageName)
                .put("containsCredentials", false)
                .put(
                    "entries",
                    JSONArray().put(
                        JSONObject()
                            .put("path", "data/pocketpilot.db")
                            .put("size", 1)
                            .put("sha256", "0".repeat(64)),
                    ),
                )
            ZipOutputStream(FileOutputStream(fixture.zip)).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("data/pocketpilot.db"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }

            val rejection = runCatching {
                runBlocking { fixture.manager.importFrom(Uri.fromFile(fixture.zip)) }
            }.exceptionOrNull()
            assertTrue("Expected checksum mismatch to be rejected", rejection is IllegalArgumentException)
            assertEquals("sentinel", fixture.workspace.read(project.id, "keep.txt"))
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "backup-test-$id").also { check(it.mkdir()) }
        val zip = File(root, "backup.zip")
        val databaseName = "backup-test-$id.db"
        val database = PocketPilotDatabase(context, databaseName)
        val credentials = FakeCredentialStore()
        val storage = File(root, "storage").also { check(it.mkdir()) }
        val projectsRoot = File(storage, "projects")
        val projects = ProjectRepository(database, projectsRoot)
        val checkpoints = CheckpointRepository(database, projectsRoot, projects::exists)
        val workspace: WorkspaceRepository
        val conversations = ConversationRepository(database)
        val settings = SettingsRepository(database)
        val manager: PocketPilotBackupManager

        init {
            projects.checkpoints = checkpoints
            workspace = WorkspaceRepository(projectsRoot, projects::exists, checkpoints) { projects.touch(it) }
            // The production backup roots live under filesDir. Tests inject a
            // private temporary equivalent so no installed app data is touched.
            manager = PocketPilotBackupManager(
                context,
                database,
                credentials,
                PluginRunCoordinationGate(),
                storage,
            )
        }

        fun insertSshServer(credentialId: String) {
            val values = ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("name", "Server")
                put("host", "example.com")
                put("port", 22)
                put("username", "user")
                put("credential_id", credentialId)
                put("auth_type", "password")
                put("host_key_fingerprint", "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                put("description", "")
            }
            database.writableDatabase.insertOrThrow("ssh_servers", null, values)
        }

        fun close() {
            database.close()
            context.deleteDatabase(databaseName)
            deleteNoFollow(root.toPath())
        }
    }

    private class FakeCredentialStore : SecureCredentialStore {
        private val values = mutableMapOf<String, CharArray>()
        override fun put(credentialId: String, secret: CharArray) {
            values.remove(credentialId)?.fill('\u0000')
            values[credentialId] = secret.copyOf()
        }
        override fun get(credentialId: String): CharArray? = values[credentialId]?.copyOf()
        override fun contains(credentialId: String): Boolean = values.containsKey(credentialId)
        override fun remove(credentialId: String) {
            values.remove(credentialId)?.fill('\u0000')
        }
    }

    private companion object {
        fun deleteNoFollow(root: Path) {
            if (!Files.exists(root)) return
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
