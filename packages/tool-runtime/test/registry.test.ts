import { describe, expect, it, vi } from "vitest";

import {
  ToolRegistry,
  toolSuccess,
  type AgentTool,
  type PermissionDecision,
  type ToolContext
} from "../src/index.js";

const context = (signal = new AbortController().signal): ToolContext => ({
  runId: "run-1",
  projectId: "project-1",
  callId: "call-1",
  signal
});

const tool = (risk: AgentTool["risk"] = "read"): AgentTool => ({
  name: "test.echo",
  description: "Echo input",
  inputSchema: { type: "object" },
  risk,
  execute: async (input) => toolSuccess(input)
});

describe("ToolRegistry", () => {
  it("registers, lists, and executes a tool with ordered events", async () => {
    const events: string[] = [];
    const registry = new ToolRegistry({ onEvent: (event) => events.push(event.type) });
    registry.register(tool());

    expect(registry.list().map(({ name }) => name)).toEqual(["test.echo"]);
    await expect(registry.execute("test.echo", { value: 1 }, context())).resolves.toEqual({
      success: true,
      data: { value: 1 }
    });
    expect(events).toEqual(["tool.started", "tool.finished"]);
  });

  it("rejects duplicate registration and returns an unknown-tool result", async () => {
    const registry = new ToolRegistry().register(tool());
    expect(() => registry.register(tool())).toThrow("already registered");
    await expect(registry.execute("missing", {}, context())).resolves.toMatchObject({
      success: false,
      error: { code: "UNKNOWN_TOOL" }
    });
  });

  it("requires approval for remote risk by default", async () => {
    const execute = vi.fn(async () => toolSuccess(null));
    const registry = new ToolRegistry().register({ ...tool("remote"), execute });
    await expect(registry.execute("test.echo", {}, context())).resolves.toMatchObject({
      success: false,
      error: { code: "APPROVAL_REQUIRED" }
    });
    expect(execute).not.toHaveBeenCalled();
  });

  it("honors an explicit permission denial", async () => {
    const decision: PermissionDecision = { outcome: "deny", reason: "No access" };
    const registry = new ToolRegistry({
      permissionPolicy: { evaluate: () => decision }
    }).register(tool());
    await expect(registry.execute("test.echo", {}, context())).resolves.toEqual({
      success: false,
      error: { code: "PERMISSION_DENIED", message: "No access" }
    });
  });

  it("maps thrown errors and cancellation to structured failures", async () => {
    const throwing = new ToolRegistry().register({
      ...tool(),
      execute: async () => {
        throw new Error("boom");
      }
    });
    await expect(throwing.execute("test.echo", {}, context())).resolves.toMatchObject({
      success: false,
      error: { code: "TOOL_EXECUTION_FAILED", message: "boom" }
    });

    const controller = new AbortController();
    controller.abort();
    await expect(
      throwing.execute("test.echo", {}, context(controller.signal)),
    ).resolves.toMatchObject({ success: false, error: { code: "CANCELLED" } });
  });

  it("does not start a tool cancelled while permission is being evaluated", async () => {
    const controller = new AbortController();
    const execute = vi.fn(async () => toolSuccess(null));
    const registry = new ToolRegistry({
      permissionPolicy: {
        evaluate: async () => {
          await Promise.resolve();
          controller.abort();
          return { outcome: "allow" } as const;
        }
      }
    }).register({ ...tool(), execute });

    await expect(
      registry.execute("test.echo", {}, context(controller.signal)),
    ).resolves.toMatchObject({ success: false, error: { code: "CANCELLED" } });
    expect(execute).not.toHaveBeenCalled();
  });
});
