import { describe, expect, it, vi } from "vitest";

import { ToolRegistry } from "@pocketpilot/tool-runtime";

import {
  InMemoryWorkspace,
  WorkspaceError,
  createWorkspaceTools,
  normalizeWorkspacePath
} from "../src/index.js";

describe("workspace path safety", () => {
  it.each([
    "../secret",
    "safe/../secret",
    "/absolute",
    "C:/Windows/system.ini",
    "C:\\Windows\\system.ini",
    "\\\\server\\share",
    "a//b",
    "./a",
    "a/./b",
    "a\u0000b"
  ])("rejects escaping or ambiguous path %s", (path) => {
    expect(() => normalizeWorkspacePath(path)).toThrow(WorkspaceError);
  });

  it("accepts only an explicit empty root for directory operations", () => {
    expect(normalizeWorkspacePath("", { allowRoot: true })).toBe("");
    expect(() => normalizeWorkspacePath("")).toThrowError(
      expect.objectContaining({ code: "INVALID_PATH" }),
    );
  });
});

describe("InMemoryWorkspace", () => {
  it("prevents file/directory prefix conflicts", async () => {
    const workspace = new InMemoryWorkspace({ files: [{ path: "file", content: "x" }] });
    await expect(workspace.create("file/child", "x")).rejects.toMatchObject({
      code: "ALREADY_EXISTS"
    });
    expect(
      () =>
        new InMemoryWorkspace({
          files: [
            { path: "a/b", content: "child" },
            { path: "a", content: "parent" }
          ]
        }),
    ).toThrowError(expect.objectContaining({ code: "PATH_CONFLICT" }));
  });

  it("enforces UTF-8 file limits and atomically validates snapshots", async () => {
    const workspace = new InMemoryWorkspace({
      files: [{ path: "ok.txt", content: "ok" }],
      maxFileBytes: 4
    });
    await expect(workspace.create("large.txt", "你好")).rejects.toMatchObject({
      code: "FILE_TOO_LARGE"
    });
    await expect(
      workspace.replaceAll([
        { path: "next.txt", content: "yes" },
        { path: "../escape", content: "x" }
      ]),
    ).rejects.toMatchObject({ code: "PATH_ESCAPE" });
    await expect(workspace.read("ok.txt")).resolves.toBe("ok");
  });
});

describe("workspace tools", () => {
  it("covers list/read/write/create/delete/move/search/patch", async () => {
    const mutations = vi.fn();
    const workspace = new InMemoryWorkspace();
    const registry = new ToolRegistry().registerAll(
      createWorkspaceTools(workspace, { onDidMutate: mutations }),
    );
    let call = 0;
    const execute = (name: string, args: unknown) =>
      registry.execute(name, args, {
        runId: "run-1",
        projectId: "project-1",
        callId: `call-${++call}`,
        signal: new AbortController().signal
      });

    await expect(
      execute("workspace.create", {
        path: "notes/hello.md",
        content: "Hello PocketPilot\nSecond line"
      }),
    ).resolves.toMatchObject({ success: true, data: { path: "notes/hello.md" } });

    await expect(execute("workspace.list", { path: "" })).resolves.toMatchObject({
      success: true,
      data: [{ kind: "directory", name: "notes", path: "notes" }]
    });
    await expect(
      execute("workspace.read", { path: "notes/hello.md" }),
    ).resolves.toMatchObject({
      success: true,
      data: { content: "Hello PocketPilot\nSecond line" }
    });

    await expect(
      execute("workspace.write", {
        path: "notes/hello.md",
        content: "Hello world\nSecond line"
      }),
    ).resolves.toMatchObject({ success: true });
    await expect(
      execute("workspace.patch", {
        path: "notes/hello.md",
        oldText: "world",
        newText: "PocketPilot"
      }),
    ).resolves.toMatchObject({ success: true, data: { replacements: 1 } });
    await expect(
      execute("workspace.search", { query: "pocketpilot", maxResults: 5 }),
    ).resolves.toMatchObject({
      success: true,
      data: [{ path: "notes/hello.md", line: 1, column: 7 }]
    });

    await expect(
      execute("workspace.move", {
        from: "notes/hello.md",
        to: "archive/hello.md"
      }),
    ).resolves.toMatchObject({ success: true });
    await expect(workspace.read("archive/hello.md")).resolves.toBe(
      "Hello PocketPilot\nSecond line",
    );
    await expect(
      execute("workspace.delete", { path: "archive/hello.md" }),
    ).resolves.toMatchObject({ success: true, data: { deleted: true } });
    await expect(workspace.snapshot()).resolves.toEqual([]);
    expect(mutations).toHaveBeenCalledTimes(5);
  });

  it("refuses a drifting exact-text patch without changing the file", async () => {
    const workspace = new InMemoryWorkspace({
      files: [{ path: "a.txt", content: "same same" }]
    });
    const registry = new ToolRegistry().registerAll(createWorkspaceTools(workspace));
    const result = await registry.execute(
      "workspace.patch",
      { path: "a.txt", oldText: "same", newText: "new" },
      {
        runId: "run",
        projectId: "project",
        callId: "call",
        signal: new AbortController().signal
      },
    );
    expect(result).toMatchObject({
      success: false,
      error: { code: "PATCH_MISMATCH" }
    });
    await expect(workspace.read("a.txt")).resolves.toBe("same same");
  });
});
