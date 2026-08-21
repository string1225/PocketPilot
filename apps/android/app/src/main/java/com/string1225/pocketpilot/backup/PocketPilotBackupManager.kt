package com.string1225.pocketpilot.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.string1225.pocketpilot.data.PocketPilotDatabase
import com.string1225.pocketpilot.runtime.PluginRunCoordinationGate
import com.string1225.pocketpilot.security.CredentialIds
import com.string1225.pocketpilot.security.SecureCredentialStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.EnumSet
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class BackupResult(
    val projectCount: Int,
    val fileCount: Int,
    val totalBytes: Long,
)

/** Full local-state backup. Secrets are deliberately excluded and revoked on restore. */
class PocketPilotBackupManager(
    context: Context,
    private val database: PocketPilotDatabase,
    private val credentials: SecureCredentialStore,
    private val runGate: PluginRunCoordinationGate,
    private val filesRoot: File = context.filesDir,
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val databaseFile = appContext.getDatabasePath(database.databaseName)

    suspend fun exportTo(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        runGate.mutate { exportLocked(uri) }
    }

    suspend fun importFrom(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        runGate.mutate { importLocked(uri) }
    }

    private fun exportLocked(uri: Uri): BackupResult {
        val staging = newStagingDirectory("export")
        try {
            val stagedDatabase = File(staging, DATABASE_ENTRY)
            stagedDatabase.parentFile?.mkdirs()
            snapshotDatabase(stagedDatabase)
            val sourceFiles = buildList {
                add(BackupSource(DATABASE_ENTRY, stagedDatabase))
                BACKED_UP_ROOTS.forEach { rootName ->
                    val root = File(filesRoot, rootName)
                    if (root.exists()) collectRegularFiles(root, "files/$rootName", this)
                }
            }
            require(sourceFiles.size <= MAX_ENTRIES) { "Backup contains too many files" }
            val totalBytes = sourceFiles.sumOf { it.file.length() }
            require(totalBytes <= MAX_TOTAL_BYTES) { "Backup is larger than the supported limit" }
            val entries = sourceFiles.map { source ->
                require(source.file.length() <= MAX_FILE_BYTES) { "Backup file is too large: ${source.path}" }
                BackupEntry(source.path, source.file.length(), sha256(source.file))
            }
            val manifest = manifest(entries)
            val output = resolver.openOutputStream(uri, "w")
                ?: error("Unable to open the selected backup destination")
            ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                putBytes(zip, MANIFEST_ENTRY, manifest.toString().toByteArray(Charsets.UTF_8))
                sourceFiles.zip(entries).forEach { (source, expected) ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.putNextEntry(ZipEntry(source.path).apply { time = 0L })
                    FileInputStream(source.file).use { input ->
                        copyBounded(input, zip, MAX_FILE_BYTES, digest)
                    }
                    zip.closeEntry()
                    check(digest.hexDigest() == expected.sha256) { "A file changed while the backup was written" }
                }
            }
            return BackupResult(projectCount(stagedDatabase), entries.size, totalBytes)
        } finally {
            deleteTreeNoFollow(staging.toPath())
        }
    }

    private fun importLocked(uri: Uri): BackupResult {
        val staging = newStagingDirectory("import")
        try {
            val extracted = extractAndVerify(uri, staging)
            val stagedDatabase = File(staging, DATABASE_ENTRY)
            val sanitization = sanitizeDatabase(stagedDatabase)
            val projectCount = sanitization.projectCount
            val credentialIds = credentialIds(database.readableDatabase) +
                sanitization.credentialIds +
                setOf(CredentialIds.DEFAULT_LLM, CredentialIds.DEFAULT_GIT_TOKEN)
            credentialIds.forEach { id ->
                if (credentials.contains(id)) credentials.remove(id)
            }
            replaceLiveState(staging, stagedDatabase)
            return BackupResult(projectCount, extracted.size, extracted.sumOf { it.size })
        } finally {
            deleteTreeNoFollow(staging.toPath())
        }
    }

    private fun snapshotDatabase(destination: File) = synchronized(database) {
        val db = database.writableDatabase
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "Database is busy; try the backup again" }
        }
        database.close()
        try {
            Files.copy(databaseFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            database.writableDatabase
        }
    }

    private fun extractAndVerify(uri: Uri, staging: File): List<BackupEntry> {
        val seen = mutableSetOf<String>()
        var manifestBytes: ByteArray? = null
        var entryCount = 0
        var totalBytes = 0L
        val actual = mutableMapOf<String, BackupEntry>()
        val input = resolver.openInputStream(uri) ?: error("Unable to open the selected backup")
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                require(entryCount <= MAX_ENTRIES + 1) { "Backup contains too many entries" }
                val path = requireSafeEntryPath(entry.name)
                require(seen.add(path)) { "Backup contains duplicate entry: $path" }
                if (entry.isDirectory) {
                    require(path != MANIFEST_ENTRY) { "Backup manifest is invalid" }
                    resolveStaged(staging, path).mkdirs()
                } else if (path == MANIFEST_ENTRY) {
                    manifestBytes = zip.readBounded(MAX_MANIFEST_BYTES)
                } else {
                    require(isAllowedDataEntry(path)) { "Backup contains an unsupported entry: $path" }
                    val target = resolveStaged(staging, path)
                    target.parentFile?.mkdirs()
                    val digest = MessageDigest.getInstance("SHA-256")
                    val written = FileOutputStream(target).use { output ->
                        copyBounded(zip, output, MAX_FILE_BYTES, digest)
                    }
                    totalBytes += written
                    require(totalBytes <= MAX_TOTAL_BYTES) { "Expanded backup is too large" }
                    actual[path] = BackupEntry(path, written, digest.hexDigest())
                }
                zip.closeEntry()
            }
        }
        val manifest = parseManifest(manifestBytes ?: error("Backup manifest is missing"))
        val expected = manifest.associateBy(BackupEntry::path)
        require(expected.size == manifest.size && expected == actual) {
            "Backup contents do not match the verified inventory"
        }
        require(actual.containsKey(DATABASE_ENTRY)) { "Backup database is missing" }
        BACKED_UP_ROOTS.forEach { File(staging, "files/$it").mkdirs() }
        return actual.values.toList()
    }

    private fun sanitizeDatabase(file: File): SanitizationResult {
        val source = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        val sanitizedName = "backup-sanitized-${UUID.randomUUID()}.db"
        val sanitizedFile = appContext.getDatabasePath(sanitizedName)
        val clean = PocketPilotDatabase(appContext, sanitizedName)
        try {
            source.use {
            val quickCheck = it.rawQuery("PRAGMA quick_check", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == "ok"
            }
            require(quickCheck) { "Backup database failed its integrity check" }
            val version = it.rawQuery("PRAGMA user_version", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
            require(version == PocketPilotDatabase.DATABASE_VERSION) {
                "Backup database version is not supported by this backup format"
            }
            REQUIRED_TABLES.forEach { table ->
                require(it.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                    arrayOf(table),
                ).use { cursor -> cursor.moveToFirst() }) { "Backup database is missing $table" }
            }
            val projectIds = mutableSetOf<String>()
            it.rawQuery("SELECT id FROM projects", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val projectId = cursor.getString(0)
                    require(PROJECT_ID.matches(projectId)) { "Backup contains an invalid project id" }
                    require(projectIds.add(projectId)) { "Backup contains a duplicate project id" }
                }
            }
            val restoredCredentialIds = credentialIds(it)
            val destination = clean.writableDatabase
            destination.beginTransaction()
            try {
                destination.execSQL("PRAGMA defer_foreign_keys = ON")
                var totalRows = 0
                RESTORED_TABLES.forEach { table ->
                    totalRows += copyTable(
                        source = it,
                        destination = destination,
                        table = table,
                        clearGitCredential = table == "git_config",
                    )
                    require(totalRows <= MAX_DATABASE_ROWS) { "Backup database contains too many rows" }
                }
                destination.setTransactionSuccessful()
            } finally {
                destination.endTransaction()
            }
            destination.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                require(!cursor.moveToFirst()) { "Backup database contains broken relationships" }
            }
            val projectCount = destination.rawQuery("SELECT COUNT(*) FROM projects", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
            clean.close()
            Files.move(
                sanitizedFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            return SanitizationResult(projectCount, restoredCredentialIds)
            }
        } finally {
            runCatching { source.close() }
            runCatching { clean.close() }
            appContext.deleteDatabase(sanitizedName)
        }
    }

    private fun copyTable(
        source: SQLiteDatabase,
        destination: SQLiteDatabase,
        table: String,
        clearGitCredential: Boolean,
    ): Int {
        val columns = destination.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }
        val sourceColumns = source.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        require(sourceColumns.containsAll(columns)) { "Backup table $table has an incompatible shape" }
        var count = 0
        source.query(table, columns.toTypedArray(), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                count += 1
                val values = android.content.ContentValues(columns.size)
                columns.forEachIndexed { index, column ->
                    if (clearGitCredential && column == "credential_id") {
                        values.putNull(column)
                    } else {
                        when (cursor.getType(index)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> values.putNull(column)
                            android.database.Cursor.FIELD_TYPE_INTEGER -> values.put(column, cursor.getLong(index))
                            android.database.Cursor.FIELD_TYPE_FLOAT -> values.put(column, cursor.getDouble(index))
                            android.database.Cursor.FIELD_TYPE_STRING -> values.put(column, cursor.getString(index))
                            android.database.Cursor.FIELD_TYPE_BLOB -> values.put(column, cursor.getBlob(index))
                            else -> error("Unsupported SQLite value")
                        }
                    }
                }
                destination.insertOrThrow(table, null, values)
            }
        }
        return count
    }

    private fun replaceLiveState(staging: File, stagedDatabase: File) = synchronized(database) {
        val suffix = UUID.randomUUID().toString()
        val databaseRollback = File(databaseFile.parentFile, "${databaseFile.name}.restore-$suffix")
        val rolledRoots = mutableListOf<Pair<File, File>>()
        val installedRoots = mutableListOf<File>()
        database.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).close()
        database.close()
        try {
            if (databaseFile.exists()) Files.move(databaseFile.toPath(), databaseRollback.toPath())
            listOf("${databaseFile.name}-wal", "${databaseFile.name}-shm").forEach { name ->
                Files.deleteIfExists(File(databaseFile.parentFile, name).toPath())
            }
            BACKED_UP_ROOTS.forEach { rootName ->
                val live = File(filesRoot, rootName)
                val rollback = File(filesRoot, ".$rootName.restore-$suffix")
                if (live.exists()) {
                    Files.move(live.toPath(), rollback.toPath())
                    rolledRoots += live to rollback
                }
                Files.move(File(staging, "files/$rootName").toPath(), live.toPath())
                installedRoots += live
            }
            Files.copy(stagedDatabase.toPath(), databaseFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            database.writableDatabase.rawQuery("PRAGMA quick_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok")
            }
        } catch (error: Throwable) {
            runCatching { database.close() }
            installedRoots.asReversed().forEach { live ->
                if (live.exists()) runCatching { deleteTreeNoFollow(live.toPath()) }
            }
            rolledRoots.asReversed().forEach { (live, rollback) ->
                if (rollback.exists()) runCatching { Files.move(rollback.toPath(), live.toPath()) }
            }
            runCatching { Files.deleteIfExists(databaseFile.toPath()) }
            if (databaseRollback.exists()) {
                runCatching { Files.move(databaseRollback.toPath(), databaseFile.toPath()) }
            }
            runCatching { database.writableDatabase }
            throw error
        }
        rolledRoots.forEach { (_, rollback) -> deleteTreeNoFollow(rollback.toPath()) }
        Files.deleteIfExists(databaseRollback.toPath())
    }

    private fun manifest(entries: List<BackupEntry>): JSONObject = JSONObject()
        .put("formatVersion", FORMAT_VERSION)
        .put("packageName", appContext.packageName)
        .put("createdAt", System.currentTimeMillis())
        .put("containsCredentials", false)
        .put("entries", JSONArray().apply {
            entries.forEach { entry ->
                put(JSONObject().put("path", entry.path).put("size", entry.size).put("sha256", entry.sha256))
            }
        })

    private fun parseManifest(bytes: ByteArray): List<BackupEntry> {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.optInt("formatVersion") == FORMAT_VERSION) { "Backup format is not supported" }
        require(root.optString("packageName") == appContext.packageName) { "Backup belongs to another app" }
        require(!root.optBoolean("containsCredentials", true)) { "Backups containing plaintext secrets are rejected" }
        val entries = root.getJSONArray("entries")
        require(entries.length() <= MAX_ENTRIES) { "Backup inventory is too large" }
        return buildList(entries.length()) {
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                val path = requireSafeEntryPath(item.getString("path"))
                val size = item.getLong("size")
                val hash = item.getString("sha256")
                require(isAllowedDataEntry(path) && size in 0..MAX_FILE_BYTES && HASH.matches(hash)) {
                    "Backup inventory entry is invalid"
                }
                add(BackupEntry(path, size, hash))
            }
        }
    }

    private fun credentialIds(db: SQLiteDatabase): Set<String> = buildSet {
        listOf("ssh_servers", "git_config", "credentials").forEach { table ->
            if (!db.hasColumn(table, if (table == "credentials") "id" else "credential_id")) return@forEach
            val column = if (table == "credentials") "id" else "credential_id"
            db.rawQuery("SELECT $column FROM $table WHERE $column IS NOT NULL AND $column != ''", null)
                .use { cursor -> while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    private fun projectCount(file: File): Int =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM projects", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
        }

    private fun collectRegularFiles(root: File, zipRoot: String, output: MutableList<BackupSource>) {
        val rootPath = root.toPath()
        require(!Files.isSymbolicLink(rootPath)) { "Backup root must not be a symbolic link: $root" }
        Files.walkFileTree(
            rootPath,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(!attrs.isSymbolicLink && attrs.isRegularFile) {
                        "Backup does not support symbolic links or special files: $file"
                    }
                    val relative = rootPath.relativize(file).joinToString("/") { it.toString() }
                    output += BackupSource("$zipRoot/$relative", file.toFile())
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun requireSafeEntryPath(raw: String): String {
        require(raw.isNotEmpty() && raw.length <= 4_096 && '\u0000' !in raw && '\\' !in raw) {
            "Backup entry path is invalid"
        }
        val trimmed = raw.removeSuffix("/")
        require(trimmed.isNotEmpty() && !trimmed.startsWith('/') && !DRIVE_PREFIX.containsMatchIn(trimmed)) {
            "Backup entry path is absolute"
        }
        require(trimmed.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Backup entry escapes its root"
        }
        return trimmed
    }

    private fun isAllowedDataEntry(path: String): Boolean =
        path == DATABASE_ENTRY || BACKED_UP_ROOTS.any { path.startsWith("files/$it/") }

    private fun resolveStaged(staging: File, path: String): File {
        val target = File(staging, path).canonicalFile
        require(target.toPath().startsWith(staging.canonicalFile.toPath())) { "Backup entry escapes staging" }
        return target
    }

    private fun newStagingDirectory(operation: String): File =
        File(appContext.cacheDir, "backup-$operation-${UUID.randomUUID()}").also {
            check(it.mkdir()) { "Unable to create backup staging directory" }
        }

    private fun putBytes(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path).apply { time = 0L })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun java.io.InputStream.readBounded(limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        copyBounded(this, output, limit)
        return output.toByteArray()
    }

    private fun copyBounded(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        limit: Long,
        digest: MessageDigest? = null,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "Backup entry exceeds its size limit" }
            digest?.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input -> copyBounded(input, DISCARD_OUTPUT, MAX_FILE_BYTES, digest) }
        return digest.hexDigest()
    }

    private fun MessageDigest.hexDigest(): String =
        digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean = runCatching {
        rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }.any { it == column }
        }
    }.getOrDefault(false)

    private fun deleteTreeNoFollow(root: Path) {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(root)) {
            Files.delete(root)
            return
        }
        Files.walkFileTree(
            root,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private data class BackupSource(val path: String, val file: File)
    private data class BackupEntry(val path: String, val size: Long, val sha256: String)
    private data class SanitizationResult(val projectCount: Int, val credentialIds: Set<String>)

    private companion object {
        const val FORMAT_VERSION = 1
        const val MANIFEST_ENTRY = "manifest.json"
        const val DATABASE_ENTRY = "data/pocketpilot.db"
        const val MAX_ENTRIES = 100_000
        const val MAX_MANIFEST_BYTES = 8L * 1024L * 1024L
        const val MAX_FILE_BYTES = 512L * 1024L * 1024L
        const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L
        const val MAX_DATABASE_ROWS = 1_000_000
        val BACKED_UP_ROOTS = listOf("projects", "attachments", "plugins")
        val REQUIRED_TABLES = listOf("projects", "conversations", "messages", "settings")
        val RESTORED_TABLES = listOf(
            "projects",
            "checkpoints",
            "checkpoint_files",
            "conversations",
            "messages",
            "agent_runs",
            "agent_events",
            "git_config",
            "ssh_servers",
            "settings",
            "plugins",
        )
        val HASH = Regex("[0-9a-f]{64}")
        val DRIVE_PREFIX = Regex("^[A-Za-z]:")
        val PROJECT_ID = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-" +
                "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
        )
        val DISCARD_OUTPUT = object : java.io.OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
        }
    }
}
