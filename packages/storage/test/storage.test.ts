import { describe, expect, it } from "vitest";

import {
  InMemoryProjectRepository,
  SQLITE_SCHEMA_STATEMENTS,
  SQLITE_SCHEMA_VERSION
} from "../src/index.js";

describe("storage contracts", () => {
  it("defines every MVP SQLite table and keeps secrets out of credentials", () => {
    expect(SQLITE_SCHEMA_VERSION).toBe(1);
    const schema = SQLITE_SCHEMA_STATEMENTS.join("\n").toLowerCase();
    for (const table of [
      "projects",
      "agent_runs",
      "agent_events",
      "checkpoints",
      "checkpoint_files",
      "git_config",
      "ssh_servers",
      "credentials",
      "conversations",
      "tasks"
    ]) {
      expect(schema).toContain(`table if not exists ${table}`);
    }
    const credentialStatement = SQLITE_SCHEMA_STATEMENTS.find((statement) =>
      statement.includes("credentials"),
    );
    expect(credentialStatement).toBeDefined();
    expect(credentialStatement).not.toMatch(/api_key|password|private_key|secret|token/iu);
  });

  it("stores defensive project copies in deterministic order", async () => {
    const repository = new InMemoryProjectRepository();
    await repository.put({
      id: "later",
      name: "Later",
      type: "local",
      workspacePath: "later",
      createdAt: 2,
      updatedAt: 2
    });
    await repository.put({
      id: "first",
      name: "First",
      type: "git",
      workspacePath: "first",
      createdAt: 1,
      updatedAt: 1
    });
    await expect(repository.list()).resolves.toMatchObject([
      { id: "first" },
      { id: "later" }
    ]);
    const copy = await repository.get("first");
    expect(copy).not.toBeUndefined();
    await expect(repository.delete("first")).resolves.toBe(true);
    await expect(repository.get("first")).resolves.toBeUndefined();
  });
});
