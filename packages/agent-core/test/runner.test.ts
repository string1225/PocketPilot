import { describe, expect, it, vi } from "vitest";

import { ToolRegistry, toolFailure, toolSuccess } from "@pocketpilot/tool-runtime";
import { ScriptedProvider } from "@pocketpilot/providers";

import { DefaultAgentRunner, type AgentProvider } from "../src/index.js";

const input = (signal?: AbortSignal) => ({
  runId: "run-1",
  projectId: "project-1",
  task: "Do the work",
  ...(signal === undefined ? {} : { signal })
});

const echoTool = {
  name: "test.echo",
  description: "Echo",
  inputSchema: { type: "object" } as const,
  risk: "read" as const,
  execute: async (value: unknown) => toolSuccess(value)
};

describe("DefaultAgentRunner", () => {
  it("runs provider -> tool -> provider -> final as an ordered multi-step task", async () => {
    const provider = new ScriptedProvider([
      {
        content: "Calling a tool",
        toolCalls: [{ id: "call-1", name: "test.echo", arguments: { answer: 42 } }]
      },
      (request) => {
        expect(request.messages[request.messages.length - 1]).toMatchObject({
          role: "tool",
          toolCallId: "call-1",
          result: { success: true, data: { answer: 42 } }
        });
        return { content: "Done" };
      }
    ]);
    const runner = new DefaultAgentRunner({
      provider,
      tools: new ToolRegistry().register(echoTool),
      now: () => 100
    });

    const result = await runner.run(input());

    expect(result).toMatchObject({ status: "completed", output: "Done", steps: 2 });
    expect(result.events.map(({ type }) => type)).toEqual([
      "run.started",
      "assistant.message",
      "tool.started",
      "tool.finished",
      "assistant.message",
      "run.completed"
    ]);
    expect(result.events.map(({ sequence }) => sequence)).toEqual([1, 2, 3, 4, 5, 6]);
  });

  it("feeds ordinary tool failure back to the provider instead of crashing", async () => {
    const provider = new ScriptedProvider([
      { toolCalls: [{ id: "bad", name: "test.fail", arguments: {} }] },
      (request) => {
        expect(request.messages[request.messages.length - 1]).toMatchObject({
          role: "tool",
          result: { success: false, error: { code: "EXPECTED" } }
        });
        return { content: "Recovered" };
      }
    ]);
    const registry = new ToolRegistry().register({
      ...echoTool,
      name: "test.fail",
      execute: async () => toolFailure("EXPECTED", "expected failure")
    });
    const result = await new DefaultAgentRunner({ provider, tools: registry }).run(input());
    expect(result).toMatchObject({ status: "completed", output: "Recovered" });
  });

  it("returns a structured provider failure", async () => {
    const provider: AgentProvider = {
      name: "broken",
      complete: async () => {
        throw new Error("offline");
      }
    };
    const result = await new DefaultAgentRunner({
      provider,
      tools: new ToolRegistry()
    }).run(input());
    expect(result).toMatchObject({
      status: "failed",
      error: { code: "PROVIDER_FAILED", message: "broken: offline", retryable: true }
    });
  });

  it("stops on explicit permission refusal", async () => {
    const provider = new ScriptedProvider([
      { toolCalls: [{ id: "call-1", name: "test.echo", arguments: {} }] }
    ]);
    const registry = new ToolRegistry({
      permissionPolicy: { evaluate: () => ({ outcome: "deny", reason: "User refused" }) }
    }).register(echoTool);
    const result = await new DefaultAgentRunner({ provider, tools: registry }).run(input());
    expect(result).toMatchObject({
      status: "failed",
      error: { code: "PERMISSION_DENIED", message: "User refused" }
    });
  });

  it("pauses when approval is required", async () => {
    const provider = new ScriptedProvider([
      { toolCalls: [{ id: "call-1", name: "test.remote", arguments: {} }] }
    ]);
    const registry = new ToolRegistry().register({
      ...echoTool,
      name: "test.remote",
      risk: "remote"
    });
    const result = await new DefaultAgentRunner({ provider, tools: registry }).run(input());
    expect(result).toMatchObject({
      status: "waiting_for_approval",
      pendingCall: { id: "call-1" },
      error: { code: "APPROVAL_REQUIRED" }
    });
  });

  it("propagates cancellation through AbortSignal", async () => {
    const controller = new AbortController();
    const started = vi.fn();
    const provider: AgentProvider = {
      name: "slow",
      complete: (request) =>
        new Promise((_resolve, reject) => {
          started();
          request.signal.addEventListener(
            "abort",
            () => reject(new DOMException("aborted", "AbortError")),
            { once: true },
          );
        })
    };
    const run = new DefaultAgentRunner({ provider, tools: new ToolRegistry() }).run(
      input(controller.signal),
    );
    expect(started).toHaveBeenCalledOnce();
    controller.abort();
    await expect(run).resolves.toMatchObject({ status: "cancelled", steps: 1 });
  });

  it("does not construct AbortController when the caller supplies a signal", async () => {
    const suppliedSignal = new AbortController().signal;
    vi.stubGlobal("AbortController", undefined);
    try {
      const result = await new DefaultAgentRunner({
        provider: new ScriptedProvider([{ content: "Done" }]),
        tools: new ToolRegistry()
      }).run(input(suppliedSignal));

      expect(result).toMatchObject({ status: "completed", output: "Done" });
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("fails deterministically at the maximum step count", async () => {
    const provider: AgentProvider = {
      name: "loop",
      complete: async (request) => ({
        toolCalls: [
          {
            id: `call-${request.step}`,
            name: "test.echo",
            arguments: request.step
          }
        ]
      })
    };
    const result = await new DefaultAgentRunner({
      provider,
      tools: new ToolRegistry().register(echoTool),
      maxSteps: 2
    }).run(input());
    expect(result).toMatchObject({
      status: "failed",
      steps: 2,
      error: { code: "MAX_STEPS_EXCEEDED" }
    });
  });

  it("returns unknown tool results to the model", async () => {
    const provider = new ScriptedProvider([
      { toolCalls: [{ id: "missing", name: "missing.tool", arguments: null }] },
      (request) => {
        expect(request.messages[request.messages.length - 1]).toMatchObject({
          role: "tool",
          result: { success: false, error: { code: "UNKNOWN_TOOL" } }
        });
        return { content: "Handled" };
      }
    ]);
    const result = await new DefaultAgentRunner({
      provider,
      tools: new ToolRegistry()
    }).run(input());
    expect(result).toMatchObject({ status: "completed", output: "Handled" });
  });

  it("rejects a tool call id repeated in a later provider step", async () => {
    const provider = new ScriptedProvider([
      { toolCalls: [{ id: "same", name: "test.echo", arguments: 1 }] },
      { toolCalls: [{ id: "same", name: "test.echo", arguments: 2 }] }
    ]);
    const result = await new DefaultAgentRunner({
      provider,
      tools: new ToolRegistry().register(echoTool)
    }).run(input());
    expect(result).toMatchObject({
      status: "failed",
      error: { code: "DUPLICATE_TOOL_CALL_ID" }
    });
  });
});
