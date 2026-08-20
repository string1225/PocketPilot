import { describe, expect, it } from "vitest";

import type { ProviderRequest } from "@pocketpilot/agent-core";
import {
  DEFAULT_CHAT_COMPLETIONS_BASE_URL,
  DEFAULT_LLM_MODEL
} from "@pocketpilot/providers";
import { toolSuccess } from "@pocketpilot/tool-runtime";

import {
  AndroidAgentRuntime,
  NativeLlmTransportError,
  NativeRpcClient,
  NativeRpcLlmTransport,
  parseRuntimeStartRequest,
  type ToolRequestEnvelope
} from "../src/index.js";

const providerRequest = (signal = new AbortController().signal): ProviderRequest => ({
  runId: "run-llm",
  projectId: "project-1",
  step: 2,
  signal,
  messages: [
    { role: "system", content: "Be precise." },
    { role: "user", content: "Inspect the project." }
  ],
  tools: [
    {
      name: "workspace.read",
      description: "Read a file.",
      risk: "read",
      inputSchema: {
        type: "object",
        properties: { path: { type: "string" } },
        required: ["path"],
        additionalProperties: false
      }
    }
  ]
});

describe("native LLM transport", () => {
  it("serializes only credential references and maps native tool calls", async () => {
    let requestEnvelope: ToolRequestEnvelope | undefined;
    let rpc: NativeRpcClient;
    rpc = new NativeRpcClient({
      postMessage: (json) => {
        requestEnvelope = JSON.parse(json) as ToolRequestEnvelope;
        queueMicrotask(() => {
          rpc.receive(JSON.stringify({
            version: 1,
            id: requestEnvelope?.id,
            type: "tool.result",
            runId: requestEnvelope?.runId,
            projectId: requestEnvelope?.projectId,
            payload: toolSuccess({
              content: "I will read it.",
              toolCalls: [{
                id: "call-1",
                name: "workspace.read",
                arguments: { path: "README.md" }
              }]
            })
          }));
        });
      }
    });

    const result = await new NativeRpcLlmTransport(rpc).complete(
      {
        protocol: "chat_completions",
        baseUrl: DEFAULT_CHAT_COMPLETIONS_BASE_URL,
        model: DEFAULT_LLM_MODEL,
        credentialId: "credential-1"
      },
      providerRequest(),
    );

    expect(result).toEqual({
      content: "I will read it.",
      toolCalls: [{
        id: "call-1",
        name: "workspace.read",
        arguments: { path: "README.md" }
      }]
    });
    expect(requestEnvelope).toMatchObject({
      type: "tool.request",
      runId: "run-llm",
      projectId: "project-1",
      payload: {
        name: "llm.complete",
        arguments: {
          protocol: "chat_completions",
          baseUrl: DEFAULT_CHAT_COMPLETIONS_BASE_URL,
          model: DEFAULT_LLM_MODEL,
          credentialId: "credential-1"
        }
      }
    });
    expect(JSON.stringify(requestEnvelope)).not.toMatch(/api[_-]?key|authorization/iu);
    expect(JSON.stringify(requestEnvelope)).not.toContain("signal");
  });

  it("preserves structured native failures", async () => {
    let rpc: NativeRpcClient;
    rpc = new NativeRpcClient({
      postMessage: (json) => {
        const envelope = JSON.parse(json) as ToolRequestEnvelope;
        queueMicrotask(() => rpc.receive(JSON.stringify({
          version: 1,
          id: envelope.id,
          type: "tool.result",
          runId: envelope.runId,
          projectId: envelope.projectId,
          payload: {
            success: false,
            error: {
              code: "LLM_RATE_LIMITED",
              message: "The model endpoint is rate limited.",
              retryable: true
            }
          }
        })));
      }
    });

    const error = await new NativeRpcLlmTransport(rpc)
      .complete(
        {
          protocol: "responses",
          baseUrl: "https://open.bigmodel.cn/api/v1",
          model: DEFAULT_LLM_MODEL,
          credentialId: "credential-1"
        },
        providerRequest(),
      )
      .catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(NativeLlmTransportError);
    expect(error).toMatchObject({ code: "LLM_RATE_LIMITED", retryable: true });
  });

  it("forwards bounded native progress and final token usage", async () => {
    const streamEvents: unknown[] = [];
    let rpc: NativeRpcClient;
    rpc = new NativeRpcClient({
      postMessage: (json) => {
        const envelope = JSON.parse(json) as ToolRequestEnvelope;
        queueMicrotask(() => {
          rpc.receive(JSON.stringify({
            version: 1,
            id: envelope.id,
            type: "tool.progress",
            runId: envelope.runId,
            projectId: envelope.projectId,
            payload: { contentDelta: "Pocket" }
          }));
          rpc.receive(JSON.stringify({
            version: 1,
            id: envelope.id,
            type: "tool.progress",
            runId: envelope.runId,
            projectId: envelope.projectId,
            payload: {
              contentDelta: "Pilot",
              usage: { inputTokens: 8, outputTokens: 2, totalTokens: 10 }
            }
          }));
          rpc.receive(JSON.stringify({
            version: 1,
            id: envelope.id,
            type: "tool.result",
            runId: envelope.runId,
            projectId: envelope.projectId,
            payload: toolSuccess({
              content: "PocketPilot",
              usage: { inputTokens: 8, outputTokens: 2, totalTokens: 10 }
            })
          }));
        });
      }
    });

    const result = await new NativeRpcLlmTransport(rpc).complete(
      {
        protocol: "chat_completions",
        baseUrl: DEFAULT_CHAT_COMPLETIONS_BASE_URL,
        model: DEFAULT_LLM_MODEL,
        credentialId: "credential-1"
      },
      {
        ...providerRequest(),
        onStreamEvent: (event) => streamEvents.push(event)
      },
    );

    expect(streamEvents).toEqual([
      { contentDelta: "Pocket" },
      {
        contentDelta: "Pilot",
        usage: { inputTokens: 8, outputTokens: 2, totalTokens: 10 }
      }
    ]);
    expect(result).toEqual({
      content: "PocketPilot",
      usage: { inputTokens: 8, outputTokens: 2, totalTokens: 10 }
    });
  });

  it("ignores progress after cancellation removes the pending RPC", async () => {
    const controller = new AbortController();
    const progress: unknown[] = [];
    let envelope: ToolRequestEnvelope | undefined;
    const rpc = new NativeRpcClient({
      postMessage: (json) => {
        envelope = JSON.parse(json) as ToolRequestEnvelope;
      }
    });
    const completion = new NativeRpcLlmTransport(rpc)
      .complete(
        {
          protocol: "chat_completions",
          baseUrl: DEFAULT_CHAT_COMPLETIONS_BASE_URL,
          model: DEFAULT_LLM_MODEL,
          credentialId: "credential-1"
        },
        {
          ...providerRequest(controller.signal),
          onStreamEvent: (event) => progress.push(event)
        },
      )
      .catch((error: unknown) => error);

    controller.abort();
    rpc.receive(JSON.stringify({
      version: 1,
      id: envelope?.id,
      type: "tool.progress",
      runId: envelope?.runId,
      projectId: envelope?.projectId,
      payload: { contentDelta: "late" }
    }));

    await expect(completion).resolves.toMatchObject({ code: "CANCELLED" });
    expect(progress).toEqual([]);
  });
});

describe("runtime LLM provider selection", () => {
  it("parses an OpenAI-compatible config while rejecting API key material", () => {
    expect(parseRuntimeStartRequest(JSON.stringify({
      runId: "run-1",
      projectId: "project-1",
      task: "hello",
      provider: {
        type: "openai_compatible",
        protocol: "responses",
        credentialId: "credential-1"
      }
    }))).toMatchObject({
      provider: {
        type: "openai_compatible",
        protocol: "responses",
        credentialId: "credential-1"
      }
    });

    expect(() => parseRuntimeStartRequest(JSON.stringify({
      runId: "run-1",
      projectId: "project-1",
      task: "hello",
      provider: {
        type: "openai_compatible",
        credentialId: "credential-1",
        apiKey: "must-not-cross-the-bridge"
      }
    }))).toThrow("must not be sent");

    expect(() => parseRuntimeStartRequest(JSON.stringify({
      runId: "run-1",
      projectId: "project-1",
      task: "hello",
      provider: {
        type: "openai_compatible",
        credentialId: "credential-1",
        Authorization: "Bearer must-not-cross-the-bridge"
      }
    }))).toThrow("must not be sent");

    expect(() => parseRuntimeStartRequest(JSON.stringify({
      runId: "run-1",
      projectId: "project-1",
      task: "hello",
      provider: {
        type: "openai_compatible",
        credentialId: "credential-1",
        unknown: "value"
      }
    }))).toThrow("unsupported field");
  });

  it("routes provider completion through llm.complete instead of fetch", async () => {
    const envelopes: ToolRequestEnvelope[] = [];
    let runtime: AndroidAgentRuntime;
    runtime = new AndroidAgentRuntime({
      postMessage: (json) => {
        const envelope = JSON.parse(json) as ToolRequestEnvelope;
        envelopes.push(envelope);
        if (envelope.type === "tool.request" && envelope.payload.name === "llm.complete") {
          queueMicrotask(() => runtime.receive(JSON.stringify({
            version: 1,
            id: envelope.id,
            type: "tool.result",
            runId: envelope.runId,
            projectId: envelope.projectId,
            payload: toolSuccess({ content: "Native model response" })
          })));
        }
      }
    });

    const result = JSON.parse(await runtime.start(JSON.stringify({
      runId: "run-native-llm",
      projectId: "project-1",
      task: "hello",
      provider: {
        type: "openai_compatible",
        credentialId: "credential-1"
      }
    }))) as Record<string, unknown>;

    expect(result).toMatchObject({ status: "completed", output: "Native model response" });
    const llmRequest = envelopes.find(
      (envelope) => envelope.type === "tool.request" && envelope.payload.name === "llm.complete",
    );
    expect(llmRequest?.payload.arguments).toMatchObject({
      protocol: "chat_completions",
      baseUrl: DEFAULT_CHAT_COMPLETIONS_BASE_URL,
      model: DEFAULT_LLM_MODEL,
      credentialId: "credential-1"
    });
  });

  it("orders bridge progress before the stable final assistant event", async () => {
    const runtimeEvents: Array<Record<string, unknown>> = [];
    let runtime: AndroidAgentRuntime;
    runtime = new AndroidAgentRuntime({
      postMessage: (json) => {
        const envelope = JSON.parse(json) as Record<string, unknown>;
        if (envelope.type === "event") {
          runtimeEvents.push(envelope.payload as Record<string, unknown>);
          return;
        }
        const requestEnvelope = envelope as unknown as ToolRequestEnvelope;
        if (requestEnvelope.payload.name !== "llm.complete") return;
        queueMicrotask(() => {
          for (const contentDelta of ["Pocket", "Pilot"]) {
            runtime.receive(JSON.stringify({
              version: 1,
              id: requestEnvelope.id,
              type: "tool.progress",
              runId: requestEnvelope.runId,
              projectId: requestEnvelope.projectId,
              payload: { contentDelta }
            }));
          }
          runtime.receive(JSON.stringify({
            version: 1,
            id: requestEnvelope.id,
            type: "tool.result",
            runId: requestEnvelope.runId,
            projectId: requestEnvelope.projectId,
            payload: toolSuccess({
              content: "PocketPilot",
              usage: { inputTokens: 3, outputTokens: 2, totalTokens: 5 }
            })
          }));
        });
      }
    });

    await runtime.start(JSON.stringify({
      runId: "run-stream-order",
      projectId: "project-1",
      task: "hello",
      provider: {
        type: "openai_compatible",
        credentialId: "credential-1"
      }
    }));

    expect(runtimeEvents.map((event) => event.type)).toEqual([
      "run.started",
      "run.boundary",
      "assistant.delta",
      "assistant.delta",
      "assistant.message",
      "run.completed"
    ]);
    expect(runtimeEvents.slice(2, 5).map((event) => event.messageId)).toEqual([
      "run-stream-order:assistant:1",
      "run-stream-order:assistant:1",
      "run-stream-order:assistant:1"
    ]);
    expect(runtimeEvents[2]).toMatchObject({ delta: "Pocket", content: "Pocket" });
    expect(runtimeEvents[3]).toMatchObject({ delta: "Pilot", content: "PocketPilot" });
    expect(runtimeEvents[4]).toMatchObject({
      content: "PocketPilot",
      status: "completed",
      usage: { inputTokens: 3, outputTokens: 2, totalTokens: 5 }
    });
  });
});
