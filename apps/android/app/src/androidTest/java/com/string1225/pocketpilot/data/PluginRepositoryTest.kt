package com.string1225.pocketpilot.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PluginRepositoryTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var root: File
    private lateinit var database: PocketPilotDatabase
    private lateinit var repository: PluginRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "plugin-test-${UUID.randomUUID()}.db"
        root = File(context.cacheDir, "plugin-test-${UUID.randomUUID()}")
        database = PocketPilotDatabase(context, databaseName)
        repository = PluginRepository(database, root)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
    }

    @Test
    fun installsDisabledVerifiesHashEnablesAndUninstalls() {
        val source = "async (toolName, input) => ({ toolName, input })"
        val installed = repository.installBundle(bundle(source = source))

        assertFalse(installed.enabled)
        assertEquals(sha256(source), installed.sourceSha256)
        val packageDirectory = packageDirectories(installed.id).single()
        assertTrue(File(packageDirectory, "manifest.json").isFile)
        assertEquals(source, File(packageDirectory, "source.js").readText())

        val enabled = repository.setEnabled(installed.id, true)
        assertTrue(enabled.enabled)
        assertEquals(source, repository.loadEnabledRuntimePackages().single().source)

        repository.delete(installed.id)
        assertTrue(repository.list().isEmpty())
        assertFalse(File(root, installed.id).exists())
    }

    @Test
    fun updateIsAtomicFromMetadataPerspectiveAndResetsEnabled() {
        val unchangedSource = "async () => ({ stableSource: true })"
        val first = repository.installBundle(bundle(source = unchangedSource))
        val firstPackage = packageDirectories(first.id).single()
        repository.setEnabled(first.id, true)
        val second = repository.installBundle(
            bundle(version = "2.0.0", source = unchangedSource),
        )
        val secondPackage = packageDirectories(second.id).single()

        assertEquals("2.0.0", second.version)
        assertEquals(first.sourceSha256, second.sourceSha256)
        assertFalse(second.enabled)
        assertEquals(first.installedAt, second.installedAt)
        assertFalse(firstPackage.exists())
        assertTrue(secondPackage.isDirectory)
        assertFalse(firstPackage.name == secondPackage.name)
    }

    @Test
    fun rejectsHashMismatchNonPureRiskUnsafeSchemaAndOversizeSource() {
        val source = "async () => true"
        assertThrows(IllegalArgumentException::class.java) {
            repository.previewBundle(bundle(source = source, sourceSha256 = "0".repeat(64)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.previewBundle(bundle(source = source, risk = "network"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.previewBundle(bundle(source = source, schemaKeyword = "pattern"))
        }
        listOf("title", "description", "minLength", "maxLength", "minimum", "maximum").forEach { key ->
            assertThrows("Explicit null must not cross the Native/TypeScript schema boundary: $key", IllegalArgumentException::class.java) {
                repository.previewBundle(bundleWithSchemaNull(source, key, onRoot = false))
            }
        }
        listOf("properties", "required").forEach { key ->
            assertThrows("Explicit null must not cross the Native/TypeScript schema boundary: $key", IllegalArgumentException::class.java) {
                repository.previewBundle(bundleWithSchemaNull(source, key, onRoot = true))
            }
        }

        val boundarySource = "x".repeat(PluginPackageValidator.MAX_SOURCE_BYTES)
        assertEquals(
            PluginPackageValidator.MAX_SOURCE_BYTES,
            repository.previewBundle(bundle(source = boundarySource)).sourceSizeBytes,
        )
        assertThrows(IllegalArgumentException::class.java) {
            repository.previewBundle(bundle(source = "$boundarySource!"))
        }
    }

    @Test
    fun detectsSourceTamperingBeforeRuntimeLoad() {
        val installed = repository.installBundle(bundle(source = "async () => true"))
        repository.setEnabled(installed.id, true)
        File(packageDirectories(installed.id).single(), "source.js").writeText("tampered")

        assertThrows(IllegalStateException::class.java) { repository.loadEnabledRuntimePackages() }
    }

    @Test
    fun uninstallDoesNotFollowAPluginDirectorySymlink() {
        val outside = File(context.cacheDir, "plugin-outside-${UUID.randomUUID()}").also { it.mkdirs() }
        val sentinel = File(outside, "keep.txt").also { it.writeText("keep") }
        root.mkdirs()
        val link = File(root, "com.example.link")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (error: Exception) {
            outside.deleteRecursively()
            assumeNoException("This device does not allow app-created symbolic links", error)
        }
        try {
            repository.delete("com.example.link")
            assertFalse(link.exists())
            assertTrue(sentinel.isFile)
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun upgradesVersionThreeDatabaseWithPluginMetadataTable() {
        database.close()
        context.deleteDatabase(databaseName)
        val path = context.getDatabasePath(databaseName)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            legacy.execSQL(
                """
                CREATE TABLE messages (
                    id TEXT PRIMARY KEY NOT NULL,
                    conversation_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    run_id TEXT,
                    is_error INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            legacy.version = 3
        }

        database = PocketPilotDatabase(context, databaseName)
        val tables = database.readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'plugins'",
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

        assertEquals(listOf("plugins"), tables)
        assertEquals(7, database.readableDatabase.version)
    }

    private fun bundle(
        version: String = "1.0.0",
        source: String,
        sourceSha256: String? = null,
        risk: String = "read",
        schemaKeyword: String? = null,
    ): String {
        val fieldSchema = JSONObject().put("type", "string")
        if (schemaKeyword != null) fieldSchema.put(schemaKeyword, "(a+)+$")
        val inputSchema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("text", fieldSchema))
            .put("required", JSONArray().put("text"))
            .put("additionalProperties", false)
        val manifest = JSONObject()
            .put("manifestVersion", 1)
            .put("id", "com.example.echo")
            .put("name", "Echo")
            .put("version", version)
            .put("description", "Pure computation")
            .put(
                "tools",
                JSONArray().put(
                    JSONObject()
                        .put("name", "echo")
                        .put("description", "Echo text")
                        .put("risk", risk)
                        .put("inputSchema", inputSchema),
                ),
            )
        if (sourceSha256 != null) manifest.put("sourceSha256", sourceSha256)
        return JSONObject().put("manifest", manifest).put("source", source).toString()
    }

    private fun packageDirectories(pluginId: String): List<File> =
        File(root, pluginId).listFiles()?.filter(File::isDirectory).orEmpty()

    private fun bundleWithSchemaNull(source: String, key: String, onRoot: Boolean): String =
        JSONObject(bundle(source = source)).also { root ->
            val inputSchema = root.getJSONObject("manifest")
                .getJSONArray("tools")
                .getJSONObject(0)
                .getJSONObject("inputSchema")
            val target = if (onRoot) inputSchema else inputSchema.getJSONObject("properties").getJSONObject("text")
            target.put(key, JSONObject.NULL)
        }.toString()

    private fun sha256(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
