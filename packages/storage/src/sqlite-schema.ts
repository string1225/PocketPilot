export const SQLITE_SCHEMA_VERSION = 1;

export const SQLITE_SCHEMA_STATEMENTS: readonly string[] = [
  "PRAGMA foreign_keys = ON",
  `CREATE TABLE IF NOT EXISTS projects (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    type TEXT NOT NULL CHECK(type IN ('local', 'git')),
    workspace_path TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS agent_runs (
    id TEXT PRIMARY KEY NOT NULL,
    project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    task TEXT NOT NULL,
    status TEXT NOT NULL,
    started_at INTEGER NOT NULL,
    finished_at INTEGER
  )`,
  `CREATE TABLE IF NOT EXISTS agent_events (
    run_id TEXT NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    sequence INTEGER NOT NULL,
    type TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (run_id, sequence)
  )`,
  `CREATE TABLE IF NOT EXISTS checkpoints (
    id TEXT PRIMARY KEY NOT NULL,
    project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    parent_id TEXT REFERENCES checkpoints(id),
    source TEXT NOT NULL,
    description TEXT NOT NULL,
    created_at INTEGER NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS checkpoint_files (
    checkpoint_id TEXT NOT NULL REFERENCES checkpoints(id) ON DELETE CASCADE,
    path TEXT NOT NULL,
    content TEXT NOT NULL,
    PRIMARY KEY (checkpoint_id, path)
  )`,
  `CREATE TABLE IF NOT EXISTS credentials (
    id TEXT PRIMARY KEY NOT NULL,
    kind TEXT NOT NULL,
    label TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS git_config (
    project_id TEXT PRIMARY KEY NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    remote_url TEXT,
    branch TEXT,
    credential_id TEXT REFERENCES credentials(id)
  )`,
  `CREATE TABLE IF NOT EXISTS ssh_servers (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    host TEXT NOT NULL,
    port INTEGER NOT NULL CHECK(port BETWEEN 1 AND 65535),
    username TEXT NOT NULL,
    credential_id TEXT NOT NULL REFERENCES credentials(id),
    description TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS conversations (
    id TEXT PRIMARY KEY NOT NULL,
    project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS tasks (
    id TEXT PRIMARY KEY NOT NULL,
    project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    run_id TEXT REFERENCES agent_runs(id) ON DELETE SET NULL,
    description TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
  )`,
  "CREATE INDEX IF NOT EXISTS idx_agent_runs_project ON agent_runs(project_id, started_at)",
  "CREATE INDEX IF NOT EXISTS idx_checkpoints_project ON checkpoints(project_id, created_at)",
  "CREATE INDEX IF NOT EXISTS idx_conversations_project ON conversations(project_id, updated_at)",
  "CREATE INDEX IF NOT EXISTS idx_tasks_project ON tasks(project_id, updated_at)"
];
