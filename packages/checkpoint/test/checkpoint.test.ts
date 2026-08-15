import { describe, expect, it } from "vitest";

import { ToolRegistry } from "@pocketpilot/tool-runtime";
import { InMemoryWorkspace, createWorkspaceTools } from "@pocketpilot/workspace";

import {
  CheckpointManager,
  InMemoryCheckpointStore
} from "../src/index.js";

describe("CheckpointManager", () => {
  it("captures full snapshots, derives diff, and restores added/deleted/modified files", async () => {
    const ids = ["cp-1", "cp-2", "cp-restore"];
    let now = 100;
    const workspace = new InMemoryWorkspace({
      files: [
        { path: "a.txt", content: "A1" },
        { path: "b.txt", content: "B1" }
      ]
    });
    const manager = new CheckpointManager({
      workspace,
      store: new InMemoryCheckpointStore(),
      createId: () => ids.shift() ?? "unexpected",
      now: () => ++now
    });

    const first = await manager.capture({
      projectId: "project-1",
      source: "import",
      description: "Initial"
    });
    await workspace.write("a.txt", "A2");
    await workspace.delete("b.txt");
    await workspace.create("c.txt", "C1");
    const second = await manager.capture({
      projectId: "project-1",
      source: "agent",
      description: "Agent changes"
    });

    await expect(manager.diff(second.id)).resolves.toEqual({
      fromId: first.id,
      toId: second.id,
      changes: [
        { kind: "modified", path: "a.txt", before: "A1", after: "A2" },
        { kind: "deleted", path: "b.txt", before: "B1" },
        { kind: "added", path: "c.txt", after: "C1" }
      ],
      summary: { added: 1, deleted: 1, modified: 1 }
    });

    const restored = await manager.restore(first.id);
    await expect(workspace.snapshot()).resolves.toEqual([
      { path: "a.txt", content: "A1" },
      { path: "b.txt", content: "B1" }
    ]);
    expect(restored).toMatchObject({
      id: "cp-restore",
      parentId: second.id,
      source: "user",
      description: `Restored checkpoint ${first.id}`
    });
    await expect(manager.list("project-1")).resolves.toHaveLength(3);
  });

  it("does not allow a cross-project parent or diff", async () => {
    const workspace = new InMemoryWorkspace();
    const manager = new CheckpointManager({
      workspace,
      store: new InMemoryCheckpointStore(),
      createId: (() => {
        let value = 0;
        return () => `cp-${++value}`;
      })()
    });
    const first = await manager.capture({
      projectId: "one",
      source: "user",
      description: "one"
    });
    const second = await manager.capture({
      projectId: "two",
      source: "user",
      description: "two"
    });
    await expect(manager.diff(second.id, first.id)).rejects.toMatchObject({
      code: "PROJECT_MISMATCH"
    });
  });

  it("provides a mutation hook that checkpoints every successful write tool", async () => {
    const workspace = new InMemoryWorkspace();
    const manager = new CheckpointManager({
      workspace,
      store: new InMemoryCheckpointStore(),
      createId: (() => {
        let value = 0;
        return () => `cp-${++value}`;
      })(),
      now: () => 100
    });
    await manager.capture({
      projectId: "project",
      source: "user",
      description: "Initial"
    });
    const tools = new ToolRegistry().registerAll(
      createWorkspaceTools(workspace, {
        onDidMutate: manager.mutationHandler({ projectId: "project" })
      }),
    );
    await tools.execute(
      "workspace.create",
      { path: "new.txt", content: "new" },
      {
        runId: "run",
        projectId: "project",
        callId: "call",
        signal: new AbortController().signal
      },
    );
    const checkpoints = await manager.list("project");
    expect(checkpoints).toHaveLength(2);
    expect(checkpoints[1]).toMatchObject({
      parentId: "cp-1",
      source: "agent",
      description: "create new.txt",
      createdAt: 101,
      files: [{ path: "new.txt", content: "new" }]
    });
  });
});
