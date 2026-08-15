package com.string1225.pocketpilot.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class PocketPilotDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
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
                task TEXT NOT NULL,
                status TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                completed_at INTEGER,
                error TEXT,
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
                remote_url TEXT,
                branch TEXT,
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
                credential_id TEXT,
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

        db.execSQL("CREATE INDEX idx_checkpoints_project_time ON checkpoints(project_id, created_at DESC)")
        db.execSQL("CREATE INDEX idx_agent_runs_project_time ON agent_runs(project_id, started_at DESC)")
        db.execSQL("CREATE INDEX idx_agent_events_run_sequence ON agent_events(run_id, sequence)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No database migration from $oldVersion to $newVersion has been defined")
    }

    companion object {
        private const val DATABASE_NAME = "pocketpilot.db"
        private const val DATABASE_VERSION = 1
    }
}
