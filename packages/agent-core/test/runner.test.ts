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
      "run.boundary",
      "assistant.message",
      "run.boundary",
      "tool.started",
      "tool.finished",
      "run.boundary",
      "assistant.message",
      "run.completed"
    ]);
    expect(result.events.map(({ sequence }) => sequence)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9]);
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

  it("emits stable incremental assistant events and cumulative token usage", async () => {
    const provider: AgentProvider = {
      name: "streaming",
      complete: async (request) => {
        request.onStreamEvent?.({ contentDelta: "你" });
        request.onStreamEvent?.({ contentDelta: "好" });
        return {
          content: "你好",
          usage: { inputTokens: 4, outputTokens: 2, totalTokens: 6 }
        };
      }
    };
    const result = await new DefaultAgentRunner({
      provider,
      tools: new ToolRegistry(),
      now: () => 100
    }).run(input());

    expect(result.events.map(({ type }) => type)).toEqual([
      "run.started",
      "run.boundary",
      "assistant.delta",
      "assistant.delta",
      "assistant.message",
      "run.completed"
    ]);
    expect(result.events[2]).toMatchObject({
      messageId: "run-1:assistant:1",
      delta: "你",
      content: "你"
    });
    expect(result.events[3]).toMatchObject({
      messageId: "run-1:assistant:1",
      delta: "好",
      content: "你好"
    });
    expect(result.events[4]).toMatchObject({
      messageId: "run-1:assistant:1",
      content: "你好",
      usage: { inputTokens: 4, outputTokens: 2, totalTokens: 6 }
    });
    expect(result.events[5]).toMatchObject({
      usage: { inputTokens: 4, outputTokens: 2, totalTokens: 6 }
    });
  });

  it("adds provider usage across tool steps", async () => {
    const provider = new ScriptedProvider([
      {
        toolCalls: [{ id: "call-usage", name: "test.echo", arguments: {} }],
        usage: { inputTokens: 2, outputTokens: 1, totalTokens: 3 }
      },
      {
        content: "Done",
        usage: { inputTokens: 5, outputTokens: 2, totalTokens: 7 }
      }
    ]);
    const result = await new DefaultAgentRunner({
      provider,
      tools: new ToolRegistry().register(echoTool)
    }).run(input());

    expect(result.events[result.events.length - 1]).toMatchObject({
      type: "run.completed",
      usage: { inputTokens: 7, outputTokens: 3, totalTokens: 10 }
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
    await vi.waitFor(() => expect(started).toHaveBeenCalledOnce());
    controller.abort();
    await expect(run).resolves.toMatchObject({ status: "cancelled", steps: 1 });
  });

  it("finalizes already streamed text when the provider fails", async () => {
    const provider: AgentProvider = {
      name: "broken-stream",
      complete: async (request) => {
        request.onStreamEvent?.({ contentDelta: "partial" });
        throw new Error("connection dropped");
      }
    };
    const result = await new DefaultAgentRunner({
      provider,
      tools: new ToolRegistry()
    }).run(input());

    expect(result.status).toBe("failed");
    expect(result.events).toEqual(expect.arrayContaining([
      expect.objectContaining({
        type: "assistant.message",
        messageId: "run-1:assistant:1",
        content: "partial",
        status: "failed"
      })
    ]));
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

  it("continues beyond eight tool steps until the provider returns a final answer", async () => {
    const provider: AgentProvider = {
      name: "long-running-loop",
      complete: async (request) =>
        request.step <= 12
          ? {
              toolCalls: [
                {
                  id: `call-${request.step}`,
                  name: "test.echo",
                  arguments: { step: request.step }
                }
              ]
            }
          : { content: "Finished after twelve tool steps" }
    };
    const result = await new DefaultAgentRunner({
      provider,
      tools: new ToolRegistry().register(echoTool)
    }).run(input());
    expect(result).toMatchObject({
      status: "completed",
      steps: 13,
      output: "Finished after twelve tool steps"
    });
    expect(result.events.filter(({ type }) => type === "tool.finished")).toHaveLength(12);
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

  it("compacts oversized context against the provider token window", async () => {
    const provider: AgentProvider = {
      name: "small-window",
      contextWindowTokens: 2_048,
      complete: async (request) => {
        expect(request.messages.some((message) =>
          message.role === "system" && message.content.includes("compacted locally"),
        )).toBe(true);
        return { content: "Done", finishReason: "stop" };
      }
    };
    const history = Array.from({ length: 20 }, (_, index) => ({
      role: index % 2 === 0 ? "user" as const : "assistant" as const,
      content: `history-${index}-${"x".repeat(600)}`
    }));
    const result = await new DefaultAgentRunner({
      provider,
      tools: new ToolRegistry(),
      reserveOutputTokens: 512,
      summaryTokens: 256
    }).run({ ...input(), messages: history });
    expect(result.events).toEqual(expect.arrayContaining([
      expect.objectContaining({ type: "context.compacted", omittedMessages: expect.any(Number) })
    ]));
  });

  it("never executes tool calls when the provider reports truncated output", async () => {
    const execute = vi.fn(async () => toolSuccess({ ok: true }));
    const result = await new DefaultAgentRunner({
      provider: new ScriptedProvider([{
        finishReason: "length",
        toolCalls: [{ id: "partial", name: "test.echo", arguments: {} }]
      }]),
      tools: new ToolRegistry().register({ ...echoTool, execute })
    }).run(input());
    expect(result).toMatchObject({
      status: "failed",
      error: { code: "MODEL_OUTPUT_TRUNCATED" }
    });
    expect(execute).not.toHaveBeenCalled();
  });

  it("runs independent read tools concurrently while preserving result order", async () => {
    let active = 0;
    let maximumActive = 0;
    const parallelTool = {
      ...echoTool,
      executionMode: "parallel" as const,
      execute: async (value: unknown) => {
        active += 1;
        maximumActive = Math.max(maximumActive, active);
        await new Promise((resolve) => setTimeout(resolve, 10));
        active -= 1;
        return toolSuccess(value);
      }
    };
    const provider = new ScriptedProvider([
      {
        finishReason: "tool_calls",
        toolCalls: [
          { id: "read-1", name: "test.echo", arguments: 1 },
          { id: "read-2", name: "test.echo", arguments: 2 }
        ]
      },
      (request) => {
        const results = request.messages.filter((message) => message.role === "tool");
        expect(results.map((message) => message.toolCallId)).toEqual(["read-1", "read-2"]);
        return { content: "Done", finishReason: "stop" };
      }
    ]);
    const result = await new DefaultAgentRunner({
      provider,
      tools: new ToolRegistry().register(parallelTool)
    }).run(input());
    expect(result.status).toBe("completed");
    expect(maximumActive).toBe(2);
  });

  it("continues the same run for queued follow-ups and invokes composable hooks", async () => {
    const calls: string[] = [];
    let delivered = false;
    const result = await new DefaultAgentRunner({
      provider: new ScriptedProvider([
        { content: "First", finishReason: "stop" },
        (request) => {
          expect(request.messages[request.messages.length - 1]).toMatchObject({
            role: "user",
            content: "Continue"
          });
          return { content: "Second", finishReason: "stop" };
        }
      ]),
      tools: new ToolRegistry(),
      hooks: [{
        beforeProvider: ({ step }) => { calls.push(`before:${step}`); },
        afterProvider: ({ step }) => { calls.push(`after:${step}`); }
      }]
    }).run({
      ...input(),
      control: {
        drainSteering: () => [],
        waitForFollowUp: async () => {
          if (delivered) return undefined;
          delivered = true;
          return [{ role: "user", content: "Continue" }];
        }
      }
    });
    expect(result).toMatchObject({ status: "completed", output: "Second", steps: 2 });
    expect(calls).toEqual(["before:1", "after:1", "before:2", "after:2"]);
  });
});
