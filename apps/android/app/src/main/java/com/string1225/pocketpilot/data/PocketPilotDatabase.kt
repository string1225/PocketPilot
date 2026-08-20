package com.string1225.pocketpilot.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class PocketPilotDatabase(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE projects (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE checkpoints (
                id TEXT PRIMARY KEY NOT NULL,
                project_id TEXT NOT NULL,
                parent_id TEXT,
                source TEXT NOT NULL,
                description TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                added_files INTEGER NOT NULL DEFAULT 0,
                modified_files INTEGER NOT NULL DEFAULT 0,
                deleted_files INTEGER NOT NULL DEFAULT 0,
                total_files INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE,
                FOREIGN KEY(parent_id) REFERENCES checkpoints(id) ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE checkpoint_files (
                checkpoint_id TEXT NOT NULL,
                path TEXT NOT NULL,
                content TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                size INTEGER NOT NULL,
                PRIMARY KEY(checkpoint_id, path),
                FOREIGN KEY(checkpoint_id) REFERENCES checkpoints(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE agent_runs (
                id TEXT PRIMARY KEY NOT NULL,
                project_id TEXT NOT NULL,
                conversation_id TEXT,
                task TEXT NOT NULL,
                status TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                completed_at INTEGER,
                error TEXT,
                recovery_phase TEXT,
                resume_payload TEXT,
                FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE agent_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                type TEXT NOT NULL,
                payload TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE(run_id, sequence),
                FOREIGN KEY(run_id) REFERENCES agent_runs(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE git_config (
                project_id TEXT PRIMARY KEY NOT NULL,
                remote_name TEXT NOT NULL DEFAULT 'origin',
                remote_url TEXT,
                branch TEXT,
                username TEXT NOT NULL DEFAULT 'git',
                credential_id TEXT,
                FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE ssh_servers (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                username TEXT NOT NULL,
                credential_id TEXT NOT NULL,
                auth_type TEXT NOT NULL DEFAULT 'password',
                host_key_fingerprint TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE credentials (
                id TEXT PRIMARY KEY NOT NULL,
                kind TEXT NOT NULL,
                label TEXT NOT NULL,
                keystore_alias TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        createConversationAndSettingsTables(db)
        createPluginTables(db)

        db.execSQL("CREATE INDEX idx_checkpoints_project_time ON checkpoints(project_id, created_at DESC)")
        db.execSQL("CREATE INDEX idx_agent_runs_project_time ON agent_runs(project_id, started_at DESC)")
        db.execSQL("CREATE INDEX idx_agent_events_run_sequence ON agent_events(run_id, sequence)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createConversationAndSettingsTables(db)
        }
        if (oldVersion < 3) {
            db.execSQL(
                "ALTER TABLE ssh_servers ADD COLUMN auth_type TEXT NOT NULL DEFAULT 'password'",
            )
            db.execSQL(
                "ALTER TABLE ssh_servers ADD COLUMN host_key_fingerprint TEXT NOT NULL DEFAULT ''",
            )
            db.execSQL(
                "UPDATE ssh_servers SET credential_id = 'ssh.' || id " +
                    "WHERE credential_id IS NULL OR credential_id = ''",
            )
        }
        if (oldVersion < 4) {
            createPluginTables(db)
        }
        // Version 1 did not have a messages table; the <2 branch above creates
        // it with the latest shape, so only pre-existing v2-v4 tables need ALTERs.
        if (oldVersion in 2..4) {
            db.execSQL("ALTER TABLE messages ADD COLUMN status TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN prompt_tokens INTEGER")
            db.execSQL("ALTER TABLE messages ADD COLUMN completion_tokens INTEGER")
            db.execSQL("ALTER TABLE messages ADD COLUMN total_tokens INTEGER")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachments_json TEXT NOT NULL DEFAULT '[]'")
        }
        if (oldVersion < 6) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS git_config (
                    project_id TEXT PRIMARY KEY NOT NULL,
                    remote_url TEXT,
                    branch TEXT,
                    credential_id TEXT
                )
                """.trimIndent(),
            )
            if (!db.hasColumn("git_config", "remote_name")) {
                db.execSQL("ALTER TABLE git_config ADD COLUMN remote_name TEXT NOT NULL DEFAULT 'origin'")
            }
            if (!db.hasColumn("git_config", "username")) {
                db.execSQL("ALTER TABLE git_config ADD COLUMN username TEXT NOT NULL DEFAULT 'git'")
            }
        }
        if (oldVersion < 7 && db.hasTable("agent_runs")) {
            db.execSQL("ALTER TABLE agent_runs ADD COLUMN conversation_id TEXT")
            db.execSQL("ALTER TABLE agent_runs ADD COLUMN recovery_phase TEXT")
            db.execSQL("ALTER TABLE agent_runs ADD COLUMN resume_payload TEXT")
        }
        check(newVersion <= DATABASE_VERSION) {
            "Database version $newVersion is newer than supported version $DATABASE_VERSION"
        }
    }

    private fun createConversationAndSettingsTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY NOT NULL,
                project_id TEXT NOT NULL,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY NOT NULL,
                conversation_id TEXT NOT NULL,
                role TEXT NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                run_id TEXT,
                is_error INTEGER NOT NULL DEFAULT 0,
                status TEXT,
                prompt_tokens INTEGER,
                completion_tokens INTEGER,
                total_tokens INTEGER,
                attachments_json TEXT NOT NULL DEFAULT '[]',
                FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY NOT NULL,
                value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_conversations_project_time " +
                "ON conversations(project_id, updated_at DESC)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_messages_conversation_time " +
                "ON messages(conversation_id, created_at ASC)",
        )
    }

    private fun createPluginTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS plugins (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                version TEXT NOT NULL,
                description TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 0 CHECK(enabled IN (0, 1)),
                source_sha256 TEXT NOT NULL,
                manifest_json TEXT NOT NULL,
                installed_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_plugins_enabled_name " +
                "ON plugins(enabled, name COLLATE NOCASE)",
        )
    }

    private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean =
        rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }
                .any { it == column }
        }

    private fun SQLiteDatabase.hasTable(table: String): Boolean = rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
        arrayOf(table),
    ).use { it.moveToFirst() }

    companion object {
        private const val DATABASE_NAME = "pocketpilot.db"
        private const val DATABASE_VERSION = 7
    }
}
