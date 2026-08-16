package com.string1225.pocketpilot.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.string1225.pocketpilot.model.InstalledPlugin
import com.string1225.pocketpilot.model.PluginInstallPreview
import com.string1225.pocketpilot.model.PluginToolManifest
import com.string1225.pocketpilot.model.PluginToolRisk
import com.string1225.pocketpilot.model.RuntimePluginPackage
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Installs user-supplied plugins into immutable, content-addressed packages in
 * app-private storage. SQLite only points at a package after its files have
 * been durably written, so an interrupted update keeps the previous package.
 */
class PluginRepository(
    private val database: PocketPilotDatabase,
    private val pluginsRoot: File,
) {
    @Synchronized
    fun previewInstall(manifestJson: String, source: String): PluginInstallPreview =
        PluginPackageValidator.validate(manifestJson, source)

    @Synchronized
    fun previewBundle(bundleJson: String): PluginInstallPreview {
        val (manifestJson, source) = PluginPackageValidator.parseBundle(bundleJson)
        return PluginPackageValidator.validate(manifestJson, source)
    }

    @Synchronized
    fun install(manifestJson: String, source: String): InstalledPlugin {
        val preview = PluginPackageValidator.validate(manifestJson, source)
        val manifest = preview.plugin
        val installedIds = list().mapTo(mutableSetOf()) { it.id }
        require(manifest.id in installedIds || installedIds.size < MAX_INSTALLED_PLUGINS) {
            "At most $MAX_INSTALLED_PLUGINS plugins can be installed."
        }
        val previous = list().firstOrNull { it.id == manifest.id }
        val packageDirectory = writeImmutablePackage(manifest, source)
        val now = System.currentTimeMillis()
        val db = database.writableDatabase
        val installedAt = findInstalledAt(db, manifest.id) ?: now
        val installed = manifest.copy(
            enabled = false,
            installedAt = installedAt,
            updatedAt = now,
        )
        try {
            try {
                db.beginTransaction()
                db.insertWithOnConflict(
                    TABLE_PLUGINS,
                    null,
                    installed.toValues(PluginPackageValidator.canonicalManifest(installed)),
                    SQLiteDatabase.CONFLICT_REPLACE,
                ).also { rowId -> check(rowId != -1L) { "Could not save plugin metadata." } }
                db.setTransactionSuccessful()
            } finally {
                if (db.inTransaction()) db.endTransaction()
            }
        } catch (error: Throwable) {
            if (previous == null || pluginPackageIdentity(previous) != pluginPackageIdentity(installed)) {
                safeDeletePluginTree(packageDirectory.toPath())
            }
            throw error
        }
        cleanupOldPackages(installed.id, pluginPackageIdentity(installed))
        check(Files.isDirectory(packageDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Plugin package disappeared during installation."
        }
        return installed
    }

    @Synchronized
    fun installBundle(bundleJson: String): InstalledPlugin {
        val (manifestJson, source) = PluginPackageValidator.parseBundle(bundleJson)
        return install(manifestJson, source)
    }

    @Synchronized
    fun list(): List<InstalledPlugin> = database.readableDatabase.query(
        TABLE_PLUGINS,
        PLUGIN_COLUMNS,
        null,
        null,
        null,
        null,
        "name COLLATE NOCASE ASC, id ASC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    InstalledPlugin(
                        id = cursor.getString(0),
                        name = cursor.getString(1),
                        version = cursor.getString(2),
                        description = cursor.getString(3),
                        enabled = cursor.getInt(4) == 1,
                        sourceSha256 = cursor.getString(5),
                        tools = PluginPackageValidator.parseStoredTools(cursor.getString(6)),
                        installedAt = cursor.getLong(7),
                        updatedAt = cursor.getLong(8),
                    ),
                )
            }
        }
    }

    @Synchronized
    fun setEnabled(pluginId: String, enabled: Boolean): InstalledPlugin {
        val normalizedId = PluginPackageValidator.requirePluginId(pluginId)
        val installed = list()
        val existing = installed.firstOrNull { it.id == normalizedId }
            ?: throw IllegalArgumentException("Plugin is not installed: $normalizedId")
        if (enabled) {
            val enabledPlugins = installed.filter { it.enabled && it.id != normalizedId } + existing
            require(enabledPlugins.sumOf { it.tools.size } <= MAX_ENABLED_PLUGIN_TOOLS) {
                "Enabled plugins can expose at most $MAX_ENABLED_PLUGIN_TOOLS tools."
            }
            val totalBytes = enabledPlugins.sumOf {
                readAndVerifySource(it).toByteArray(Charsets.UTF_8).size
            }
            require(totalBytes <= MAX_ENABLED_SOURCE_BYTES) {
                "Enabled plugin source exceeds the $MAX_ENABLED_SOURCE_BYTES-byte run limit."
            }
        }
        val values = ContentValues().apply {
            put("enabled", if (enabled) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }
        val changed = database.writableDatabase.update(
            TABLE_PLUGINS,
            values,
            "id = ?",
            arrayOf(normalizedId),
        )
        check(changed == 1) { "Plugin state changed concurrently: $normalizedId" }
        return list().first { it.id == normalizedId }
    }

    @Synchronized
    fun delete(pluginId: String) {
        val normalizedId = PluginPackageValidator.requirePluginId(pluginId)
        database.writableDatabase.delete(TABLE_PLUGINS, "id = ?", arrayOf(normalizedId))
        safeDeletePluginTree(File(pluginsRoot, normalizedId).toPath())
    }

    /** Reads enabled source immediately before each Agent Run and verifies it. */
    @Synchronized
    fun loadEnabledRuntimePackages(): List<RuntimePluginPackage> = list()
        .filter { it.enabled }
        .map { plugin -> RuntimePluginPackage(plugin, readAndVerifySource(plugin)) }
        .also { packages ->
            val totalBytes = packages.sumOf { it.source.toByteArray(Charsets.UTF_8).size }
            require(totalBytes <= MAX_ENABLED_SOURCE_BYTES) {
                "Enabled plugin source exceeds the $MAX_ENABLED_SOURCE_BYTES-byte run limit."
            }
        }

    private fun writeImmutablePackage(plugin: InstalledPlugin, source: String): File {
        val idDirectory = pluginDirectory(plugin.id).also { directory ->
            check(!Files.isSymbolicLink(directory.toPath())) { "Plugin storage must not be a symbolic link." }
            check(directory.mkdirs() || directory.isDirectory) { "Could not create plugin storage." }
        }
        val finalDirectory = File(idDirectory, pluginPackageIdentity(plugin))
        if (Files.isDirectory(finalDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            verifyPackageFiles(finalDirectory, plugin)
            return finalDirectory
        }

        val temporaryDirectory = File(idDirectory, ".install-${UUID.randomUUID()}")
        check(temporaryDirectory.mkdir()) { "Could not create temporary plugin package." }
        try {
            durableWrite(
                File(temporaryDirectory, MANIFEST_FILE),
                PluginPackageValidator.canonicalManifest(plugin).toByteArray(Charsets.UTF_8),
            )
            durableWrite(File(temporaryDirectory, SOURCE_FILE), source.toByteArray(Charsets.UTF_8))
            moveAtomically(temporaryDirectory, finalDirectory)
        } finally {
            if (temporaryDirectory.exists()) safeDeletePluginTree(temporaryDirectory.toPath())
        }
        verifyPackageFiles(finalDirectory, plugin)
        return finalDirectory
    }

    private fun verifyPackageFiles(directory: File, plugin: InstalledPlugin) {
        val manifestFile = File(directory, MANIFEST_FILE)
        val sourceFile = File(directory, SOURCE_FILE)
        check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Plugin package directory is missing or is a symbolic link."
        }
        check(directory.name == pluginPackageIdentity(plugin)) {
            "Plugin package identity does not match its source and manifest."
        }
        check(
            Files.isRegularFile(manifestFile.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                Files.isRegularFile(sourceFile.toPath(), LinkOption.NOFOLLOW_LINKS),
        ) { "Plugin package is incomplete or contains symbolic links: ${plugin.id}" }
        val sourceBytes = boundedRead(sourceFile, PluginPackageValidator.MAX_SOURCE_BYTES)
        check(sha256(sourceBytes) == plugin.sourceSha256) { "Plugin source integrity check failed: ${plugin.id}" }
        val stored = boundedRead(manifestFile, PluginPackageValidator.MAX_MANIFEST_BYTES)
            .toString(Charsets.UTF_8)
        val parsed = PluginPackageValidator.parseManifest(stored, plugin.sourceSha256)
        check(
            parsed.id == plugin.id &&
                parsed.version == plugin.version &&
                stored == PluginPackageValidator.canonicalManifest(plugin),
        ) {
            "Plugin manifest integrity check failed: ${plugin.id}"
        }
    }

    private fun readAndVerifySource(plugin: InstalledPlugin): String {
        val directory = File(pluginDirectory(plugin.id), pluginPackageIdentity(plugin))
        verifyPackageFiles(directory, plugin)
        return boundedRead(File(directory, SOURCE_FILE), PluginPackageValidator.MAX_SOURCE_BYTES)
            .toString(Charsets.UTF_8)
    }

    private fun pluginDirectory(pluginId: String): File {
        val directory = File(pluginsRoot, pluginId)
        check(!Files.isSymbolicLink(pluginsRoot.toPath())) { "Plugin storage root must not be a symbolic link." }
        check(!Files.isSymbolicLink(directory.toPath())) { "Plugin storage must not be a symbolic link." }
        val rootPath = pluginsRoot.toPath().toAbsolutePath().normalize()
        check(directory.toPath().toAbsolutePath().normalize().startsWith(rootPath)) {
            "Invalid plugin storage path."
        }
        return directory
    }

    private fun cleanupOldPackages(pluginId: String, activeHash: String) {
        pluginDirectory(pluginId).listFiles()?.forEach { child ->
            if (child.name != activeHash) safeDeletePluginTree(child.toPath())
        }
    }

    private fun safeDeletePluginTree(target: Path) {
        check(!Files.isSymbolicLink(pluginsRoot.toPath())) { "Plugin storage root must not be a symbolic link." }
        val root = pluginsRoot.toPath().toAbsolutePath().normalize()
        val normalized = target.toAbsolutePath().normalize()
        check(normalized != root && normalized.startsWith(root)) { "Refusing to delete outside plugin storage." }
        deleteTreeNoFollow(normalized)
    }

    private fun findInstalledAt(db: SQLiteDatabase, pluginId: String): Long? = db.query(
        TABLE_PLUGINS,
        arrayOf("installed_at"),
        "id = ?",
        arrayOf(pluginId),
        null,
        null,
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    private fun InstalledPlugin.toValues(manifestJson: String): ContentValues = ContentValues().apply {
        put("id", id)
        put("name", name)
        put("version", version)
        put("description", description)
        put("enabled", if (enabled) 1 else 0)
        put("source_sha256", sourceSha256)
        put("manifest_json", manifestJson)
        put("installed_at", installedAt)
        put("updated_at", updatedAt)
    }

    companion object {
        private const val TABLE_PLUGINS = "plugins"
        private const val MANIFEST_FILE = "manifest.json"
        private const val SOURCE_FILE = "source.js"
        private const val MAX_INSTALLED_PLUGINS = 32
        private const val MAX_ENABLED_PLUGIN_TOOLS = 64
        private const val MAX_ENABLED_SOURCE_BYTES = 512 * 1024
        private val PLUGIN_COLUMNS = arrayOf(
            "id",
            "name",
            "version",
            "description",
            "enabled",
            "source_sha256",
            "manifest_json",
            "installed_at",
            "updated_at",
        )
    }
}

internal object PluginPackageValidator {
    const val MAX_BUNDLE_BYTES = 384 * 1024
    const val MAX_MANIFEST_BYTES = 64 * 1024
    // Leave room for the runtime's bounded dispatch wrapper before the source
    // enters WorkerSandbox's 128 KiB total source budget.
    const val MAX_SOURCE_BYTES = 120 * 1024
    private const val MAX_SCHEMA_BYTES = 32 * 1024
    private const val MAX_SCHEMA_DEPTH = 8
    private const val MAX_TOOLS = 16
    private val ID_PATTERN = Regex("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+$")
    private val TOOL_PATTERN = Regex("^[a-z][a-z0-9_]{0,63}$")
    private val VERSION_PATTERN = Regex(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
            "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
    )
    private val HASH_PATTERN = Regex("^[0-9a-f]{64}$")
    private val SUPPORTED_SCHEMA_TYPES = setOf("object", "array", "string", "number", "integer", "boolean", "null")
    private val SCHEMA_KEYS = setOf(
        "type",
        "title",
        "description",
        "properties",
        "required",
        "items",
        "enum",
        "additionalProperties",
        "minLength",
        "maxLength",
        "minimum",
        "maximum",
        "default",
    )

    fun parseBundle(bundleJson: String): Pair<String, String> {
        require(bundleJson.toByteArray(Charsets.UTF_8).size in 2..MAX_BUNDLE_BYTES) {
            "Plugin bundle must be at most $MAX_BUNDLE_BYTES UTF-8 bytes."
        }
        val bundle = try {
            JSONObject(bundleJson)
        } catch (error: Exception) {
            throw IllegalArgumentException("Plugin bundle is not valid JSON: ${error.message}", error)
        }
        bundle.requireOnlyKeys(setOf("manifest", "source"), "bundle")
        val manifest = bundle.opt("manifest") as? JSONObject
            ?: throw IllegalArgumentException("bundle.manifest must be an object.")
        val source = bundle.opt("source") as? String
            ?: throw IllegalArgumentException("bundle.source must be a string.")
        return manifest.toString() to source
    }

    fun validate(manifestJson: String, source: String): PluginInstallPreview {
        val manifestBytes = manifestJson.toByteArray(Charsets.UTF_8)
        require(manifestBytes.size in 2..MAX_MANIFEST_BYTES) {
            "Plugin manifest must be at most $MAX_MANIFEST_BYTES UTF-8 bytes."
        }
        val sourceBytes = source.toByteArray(Charsets.UTF_8)
        require(sourceBytes.size in 1..MAX_SOURCE_BYTES) {
            "Plugin source must be at most $MAX_SOURCE_BYTES UTF-8 bytes."
        }
        require('\u0000' !in source) { "Plugin source must not contain NUL characters." }
        val computedHash = sha256(sourceBytes)
        val plugin = parseManifest(manifestJson, computedHash)
        return PluginInstallPreview(plugin = plugin, sourceSizeBytes = sourceBytes.size)
    }

    fun parseManifest(manifestJson: String, computedHash: String): InstalledPlugin {
        val root = try {
            JSONObject(manifestJson)
        } catch (error: Exception) {
            throw IllegalArgumentException("Plugin manifest is not valid JSON: ${error.message}", error)
        }
        root.requireOnlyKeys(
            setOf("manifestVersion", "id", "name", "version", "description", "sourceSha256", "tools"),
            "manifest",
        )
        require(root.requireInt("manifestVersion", "manifest") == 1) { "manifestVersion must be 1." }
        val id = requirePluginId(root.requireString("id", "manifest"))
        val name = root.requireString("name", "manifest").requireBounded("name", 1, 80)
        val version = root.requireString("version", "manifest")
        require(isValidSemVer(version)) { "Plugin version must be valid SemVer." }
        val description = if (root.has("description")) {
            root.requireString("description", "manifest").requireBounded("description", 0, 500)
        } else {
            ""
        }
        val declaredHash = if (root.has("sourceSha256")) {
            root.requireString("sourceSha256", "manifest").also { hash ->
                require(HASH_PATTERN.matches(hash)) { "sourceSha256 must be 64 lowercase hexadecimal characters." }
                require(hash == computedHash) { "sourceSha256 does not match the supplied JavaScript source." }
            }
        } else {
            computedHash
        }
        require(HASH_PATTERN.matches(computedHash)) { "Computed plugin source hash is invalid." }
        val toolsJson = root.requireArray("tools", "manifest")
        require(toolsJson.length() in 1..MAX_TOOLS) { "A plugin must declare 1 to $MAX_TOOLS tools." }
        val seenNames = mutableSetOf<String>()
        val tools = buildList {
            repeat(toolsJson.length()) { index ->
                val path = "tools[$index]"
                val tool = toolsJson.opt(index) as? JSONObject
                    ?: throw IllegalArgumentException("$path must be an object.")
                tool.requireOnlyKeys(setOf("name", "description", "risk", "inputSchema"), path)
                val toolName = tool.requireString("name", path)
                require(TOOL_PATTERN.matches(toolName)) {
                    "$path.name must use lowercase letters, digits, and underscores."
                }
                require(seenNames.add(toolName)) { "Duplicate plugin tool name: $toolName" }
                val toolDescription = tool.requireString("description", path)
                    .requireBounded("$path.description", 1, 500)
                val risk = PluginToolRisk.fromValue(tool.requireString("risk", path))
                require(risk == PluginToolRisk.READ) {
                    "$path.risk must be read. MVP plugins are pure computation and cannot request write, network, or remote access."
                }
                val schema = tool.opt("inputSchema") as? JSONObject
                    ?: throw IllegalArgumentException("$path.inputSchema must be an object.")
                require(schema.toString().toByteArray(Charsets.UTF_8).size <= MAX_SCHEMA_BYTES) {
                    "$path.inputSchema is too large."
                }
                validateSchema(schema, "$path.inputSchema", 0, rootObject = true)
                add(
                    PluginToolManifest(
                        name = toolName,
                        description = toolDescription,
                        inputSchemaJson = schema.toString(),
                        risk = risk,
                    ),
                )
            }
        }
        return InstalledPlugin(
            id = id,
            name = name,
            version = version,
            description = description,
            sourceSha256 = declaredHash,
            tools = tools,
            enabled = false,
            installedAt = 0L,
            updatedAt = 0L,
        )
    }

    fun parseStoredTools(manifestJson: String): List<PluginToolManifest> {
        val root = JSONObject(manifestJson)
        val hash = root.requireString("sourceSha256", "stored manifest")
        return parseManifest(manifestJson, hash).tools
    }

    fun canonicalManifest(plugin: InstalledPlugin): String = JSONObject()
        .put("manifestVersion", 1)
        .put("id", plugin.id)
        .put("name", plugin.name)
        .put("version", plugin.version)
        .put("description", plugin.description)
        .put("sourceSha256", plugin.sourceSha256)
        .put(
            "tools",
            JSONArray().apply {
                plugin.tools.forEach { tool ->
                    put(
                        JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("risk", tool.risk.value)
                            .put("inputSchema", JSONObject(tool.inputSchemaJson)),
                    )
                }
            },
        )
        .toString()

    fun requirePluginId(value: String): String {
        require(value.length in 3..80 && ID_PATTERN.matches(value)) {
            "Plugin id must be a lowercase dotted identifier, for example com.example.echo."
        }
        return value
    }

    private fun isValidSemVer(value: String): Boolean {
        if (!VERSION_PATTERN.matches(value)) return false
        val prerelease = value.substringBefore('+').substringAfter('-', "")
        return prerelease.isEmpty() || prerelease.split('.').none { part ->
            part.length > 1 && part.all(Char::isDigit) && part.startsWith('0')
        }
    }

    private fun validateSchema(schema: JSONObject, path: String, depth: Int, rootObject: Boolean) {
        require(depth <= MAX_SCHEMA_DEPTH) { "$path exceeds the maximum schema depth." }
        schema.requireOnlyKeys(SCHEMA_KEYS, path)
        val type = schema.requireString("type", path)
        require(type in SUPPORTED_SCHEMA_TYPES) { "$path.type is not supported: $type" }
        if (rootObject) require(type == "object") { "$path.type must be object." }

        schema.optStringValue("title")?.requireBounded("$path.title", 0, 200)
        schema.optStringValue("description")?.requireBounded("$path.description", 0, 500)
        schema.optIntValue("minLength")?.also { require(it >= 0) { "$path.minLength must be non-negative." } }
        schema.optIntValue("maxLength")?.also { require(it >= 0) { "$path.maxLength must be non-negative." } }
        val minimum = schema.optNumberValue("minimum")
        val maximum = schema.optNumberValue("maximum")
        if (minimum != null && maximum != null) require(minimum <= maximum) { "$path minimum exceeds maximum." }
        val minLength = schema.optIntValue("minLength")
        val maxLength = schema.optIntValue("maxLength")
        if (minLength != null && maxLength != null) {
            require(minLength <= maxLength) { "$path minLength exceeds maxLength." }
        }
        if (type == "string") {
            minLength?.also { require(it <= 1_048_576) { "$path.minLength is too large." } }
            maxLength?.also { require(it <= 1_048_576) { "$path.maxLength is too large." } }
        } else {
            require(!schema.has("minLength") && !schema.has("maxLength")) {
                "$path uses string-only schema keywords."
            }
        }
        if (type != "number" && type != "integer") {
            require(!schema.has("minimum") && !schema.has("maximum")) {
                "$path uses numeric-only schema keywords."
            }
        }
        (schema.opt("enum") as? JSONArray)?.also { values ->
            require(values.length() in 1..100) { "$path.enum must contain 1 to 100 values." }
            repeat(values.length()) { index -> requireJsonValue(values.opt(index), "$path.enum[$index]", depth + 1) }
        }
        if (schema.has("enum") && schema.opt("enum") !is JSONArray) {
            throw IllegalArgumentException("$path.enum must be an array.")
        }

        if (type == "object") {
            require(schema.opt("additionalProperties") == false) {
                "$path must set additionalProperties to false."
            }
            val properties = when {
                !schema.has("properties") -> JSONObject()
                else -> schema.opt("properties") as? JSONObject
                    ?: throw IllegalArgumentException("$path.properties must be an object.")
            }
            require(properties.length() <= 64) { "$path.properties contains too many fields." }
            val propertyNames = properties.keys().asSequence().toList()
            propertyNames.forEach { propertyName ->
                require(
                    propertyName.length in 1..80 &&
                        propertyName.none(Char::isUnsafePluginDisplayCharacter),
                ) { "$path has an invalid property name." }
                val child = properties.opt(propertyName) as? JSONObject
                    ?: throw IllegalArgumentException("$path.properties.$propertyName must be an object.")
                validateSchema(child, "$path.properties.$propertyName", depth + 1, rootObject = false)
            }
            if (schema.has("required")) {
                val requiredArray = schema.opt("required") as? JSONArray
                    ?: throw IllegalArgumentException("$path.required must be an array.")
                val seen = mutableSetOf<String>()
                repeat(requiredArray.length()) { index ->
                    val field = requiredArray.opt(index) as? String
                        ?: throw IllegalArgumentException("$path.required[$index] must be a string.")
                    require(field in propertyNames) { "$path.required references unknown property: $field" }
                    require(seen.add(field)) { "$path.required contains a duplicate: $field" }
                }
            }
        } else {
            require(!schema.has("properties") && !schema.has("required") && !schema.has("additionalProperties")) {
                "$path uses object-only schema keywords."
            }
        }

        if (type == "array") {
            val items = schema.opt("items") as? JSONObject
                ?: throw IllegalArgumentException("$path.items must be an object.")
            validateSchema(items, "$path.items", depth + 1, rootObject = false)
        } else {
            require(!schema.has("items")) { "$path.items is only valid for arrays." }
        }
        if (schema.has("default")) requireJsonValue(schema.opt("default"), "$path.default", depth + 1)
    }

    private fun requireJsonValue(value: Any?, path: String, depth: Int) {
        require(depth <= MAX_SCHEMA_DEPTH + 2) { "$path is nested too deeply." }
        when (value) {
            null, JSONObject.NULL, is String, is Boolean -> Unit
            is Number -> require(value.toDouble().isFinite()) { "$path must be finite." }
            is JSONArray -> repeat(value.length()) { index -> requireJsonValue(value.opt(index), "$path[$index]", depth + 1) }
            is JSONObject -> value.keys().forEach { key -> requireJsonValue(value.opt(key), "$path.$key", depth + 1) }
            else -> throw IllegalArgumentException("$path is not a JSON value.")
        }
    }

    private fun JSONObject.requireOnlyKeys(allowed: Set<String>, path: String) {
        val unknown = keys().asSequence().firstOrNull { it !in allowed }
        require(unknown == null) { "$path contains unsupported field: $unknown" }
    }

    private fun JSONObject.requireString(key: String, path: String): String =
        (opt(key) as? String) ?: throw IllegalArgumentException("$path.$key must be a string.")

    private fun JSONObject.requireInt(key: String, path: String): Int {
        val number = opt(key) as? Number
            ?: throw IllegalArgumentException("$path.$key must be an integer.")
        val value = number.toDouble()
        require(value.isFinite() && value % 1.0 == 0.0 && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
            "$path.$key must be an integer."
        }
        return value.toInt()
    }

    private fun JSONObject.requireArray(key: String, path: String): JSONArray =
        opt(key) as? JSONArray ?: throw IllegalArgumentException("$path.$key must be an array.")

    private fun JSONObject.optStringValue(key: String): String? {
        if (!has(key)) return null
        return when (val value = opt(key)) {
            JSONObject.NULL -> throw IllegalArgumentException("$key must be a string.")
            is String -> value
            else -> throw IllegalArgumentException("$key must be a string.")
        }
    }

    private fun JSONObject.optIntValue(key: String): Int? {
        if (!has(key)) return null
        return when (val value = opt(key)) {
            JSONObject.NULL -> throw IllegalArgumentException("$key must be an integer.")
            is Int -> value
            is Long -> value.toInt().takeIf { it.toLong() == value }
            else -> throw IllegalArgumentException("$key must be an integer.")
        }
    }

    private fun JSONObject.optNumberValue(key: String): Double? {
        if (!has(key)) return null
        return when (val value = opt(key)) {
            JSONObject.NULL -> throw IllegalArgumentException("$key must be a number.")
            is Number -> value.toDouble().also { require(it.isFinite()) { "$key must be finite." } }
            else -> throw IllegalArgumentException("$key must be a number.")
        }
    }

    private fun String.requireBounded(label: String, minimum: Int, maximum: Int): String {
        require(this == trim()) { "$label must not have surrounding whitespace." }
        require(length in minimum..maximum) { "$label must contain $minimum to $maximum characters." }
        require(none(Char::isUnsafePluginDisplayCharacter)) {
            "$label contains control or bidirectional formatting characters."
        }
        return this
    }
}

private fun Char.isUnsafePluginDisplayCharacter(): Boolean =
    isISOControl() ||
        code == 0x061c ||
        code == 0x200e ||
        code == 0x200f ||
        code in 0x202a..0x202e ||
        code in 0x2066..0x2069

private fun durableWrite(file: File, bytes: ByteArray) {
    FileOutputStream(file).use { output ->
        output.write(bytes)
        output.fd.sync()
    }
}

private fun moveAtomically(from: File, to: File) {
    Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
}

private fun boundedRead(file: File, maxBytes: Int): ByteArray {
    require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        "Plugin file is missing or is a symbolic link."
    }
    return java.nio.channels.FileChannel.open(
        file.toPath(),
        StandardOpenOption.READ,
        LinkOption.NOFOLLOW_LINKS,
    ).use { channel ->
        val size = channel.size()
        require(size in 0..maxBytes.toLong()) { "Plugin file exceeds its size limit." }
        val buffer = ByteBuffer.allocate(size.toInt())
        while (buffer.hasRemaining()) {
            check(channel.read(buffer) >= 0) { "Plugin file ended before its declared size." }
        }
        buffer.array()
    }
}

/** Deletes the lexical tree without following symbolic links. */
private fun deleteTreeNoFollow(target: Path) {
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(
        target,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(directory)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun pluginPackageIdentity(plugin: InstalledPlugin): String = sha256(
    buildString {
        append(plugin.sourceSha256)
        append('\n')
        append(PluginPackageValidator.canonicalManifest(plugin))
    }.toByteArray(Charsets.UTF_8),
)
