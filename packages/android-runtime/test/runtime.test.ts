import { describe, expect, it, vi } from "vitest";

import { toolSuccess } from "@agentdock/tool-runtime";

import {
  ANDROID_BRIDGE_VERSION,
  AndroidAgentRuntime,
  NativeRpcClient,
  installAbortControllerFallback,
  installAgentDockRuntime,
  resolveRuntimeGlobal,
  type AgentDockGlobalScope,
  type ToolRequestEnvelope
} from "../src/index.js";

const parse = (json: string): Record<string, unknown> =>
  JSON.parse(json) as Record<string, unknown>;

describe("Android runtime bridge", () => {
  it("installs the required global API and immediately announces runtime.ready", () => {
    const postMessage = vi.fn();
    const target: AgentDockGlobalScope = {
      AgentDockNativeBridge: { postMessage }
    };

    const api = installAgentDockRuntime(target);

    expect(target.AgentDockRuntime).toBe(api);
    expect(api).toEqual({
      start: expect.any(Function),
      receive: expect.any(Function),
      cancel: expect.any(Function)
    });
    expect(parse(postMessage.mock.calls[0]?.[0] as string)).toEqual({
      version: 1,
      id: "runtime.ready",
      type: "event",
      runId: "",
      projectId: "",
      payload: {
        type: "runtime.ready",
        runtimeVersion: "0.1.0",
        protocolVersion: 1
      }
    });
  });

  it("turns a rejected fire-and-forget start into a run.failed event", async () => {
    const postMessage = vi.fn();
    const api = installAgentDockRuntime({
      AgentDockNativeBridge: { postMessage }
    });
    postMessage.mockClear();

    const result = JSON.parse(
      await api.start(
        JSON.stringify({
          runId: "run-invalid",
          projectId: "project-1",
          task: ""
        }),
      ),
    ) as Record<string, unknown>;

    expect(result).toMatchObject({
      runId: "run-invalid",
      projectId: "project-1",
      status: "failed",
      error: { code: "RUNTIME_START_FAILED" }
    });
    expect(parse(postMessage.mock.calls[0]?.[0] as string)).toMatchObject({
      version: 1,
      type: "event",
      runId: "run-invalid",
      projectId: "project-1",
      payload: {
        type: "run.failed",
        runId: "run-invalid",
        projectId: "project-1",
        error: { code: "RUNTIME_START_FAILED" }
      }
    });
  });

  it("provides the abort behavior used by the runtime on older WebViews", () => {
    const host: { AbortController?: typeof AbortController } = {};

    expect(installAbortControllerFallback(host)).toBe(true);
    expect(installAbortControllerFallback(host)).toBe(false);

    const controller = new (host.AbortController as typeof AbortController)();
    const once = vi.fn();
    const removed = vi.fn();
    controller.signal.addEventListener("abort", once, { once: true });
    controller.signal.addEventListener("abort", removed);
    controller.signal.removeEventListener("abort", removed);

    controller.abort();
    controller.abort();

    expect(controller.signal.aborted).toBe(true);
    expect(once).toHaveBeenCalledOnce();
    expect(removed).not.toHaveBeenCalled();
  });

  it("uses window as the runtime global for pre-globalThis WebViews", () => {
    const webViewWindow = {};
    vi.stubGlobal("window", webViewWindow);
    try {
      expect(resolveRuntimeGlobal()).toBe(webViewWindow);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("runs offline Agent -> Promise tool RPC -> final and emits required events", async () => {
    const envelopes: Record<string, unknown>[] = [];
    let runtime: AndroidAgentRuntime;
    runtime = new AndroidAgentRuntime({
      postMessage: (json) => {
        const envelope = parse(json);
        envelopes.push(envelope);
        if (envelope.type === "tool.request") {
          queueMicrotask(() => {
            runtime.receive(
              JSON.stringify({
                version: 1,
                id: envelope.id,
                type: "tool.result",
                runId: envelope.runId,
                projectId: envelope.projectId,
                payload: toolSuccess({ path: "notes/hello.md", bytes: 5 })
              }),
            );
          });
        }
      }
    });

    const result = JSON.parse(
      await runtime.start(
        JSON.stringify({
          runId: "run-1",
          projectId: "project-1",
          task: "/create notes/hello.md | Hello"
        }),
      ),
    ) as Record<string, unknown>;

    expect(result).toMatchObject({ status: "completed", steps: 2 });
    const request = envelopes.find((envelope) => envelope.type === "tool.request");
    expect(request).toEqual({
      version: ANDROID_BRIDGE_VERSION,
      id: expect.any(String),
      type: "tool.request",
      runId: "run-1",
      projectId: "project-1",
      payload: {
        name: "workspace.create",
        arguments: { path: "notes/hello.md", content: "Hello" }
      }
    });
    const eventTypes = envelopes
      .filter((envelope) => envelope.type === "event")
      .map((envelope) => (envelope.payload as { readonly type: string }).type);
    expect(eventTypes).toEqual([
      "run.started",
      "assistant.message",
      "tool.started",
      "tool.finished",
      "assistant.message",
      "run.completed"
    ]);
    for (const envelope of envelopes.filter((item) => item.type === "event")) {
      expect(envelope).toMatchObject({ runId: "run-1", projectId: "project-1" });
    }
    expect(runtime.pendingToolCount).toBe(0);
    expect(runtime.activeRunCount).toBe(0);
  });

  it("cancels a pending native tool and emits run.cancelled", async () => {
    let toolSeenResolve: (() => void) | undefined;
    const toolSeen = new Promise<void>((resolve) => {
      toolSeenResolve = resolve;
    });
    const envelopes: Record<string, unknown>[] = [];
    const runtime = new AndroidAgentRuntime({
      postMessage: (json) => {
        const envelope = parse(json);
        envelopes.push(envelope);
        if (envelope.type === "tool.request") {
          toolSeenResolve?.();
        }
      }
    });
    const running = runtime.start(
      JSON.stringify({
        runId: "run-cancel",
        projectId: "project-1",
        task: "/read slow.txt"
      }),
    );
    await toolSeen;
    expect(runtime.cancel("run-cancel")).toBe(true);
    const result = JSON.parse(await running) as Record<string, unknown>;
    expect(result).toMatchObject({ status: "cancelled" });
    expect(runtime.pendingToolCount).toBe(0);
    expect(
      envelopes.some(
        (envelope) =>
          envelope.type === "event" &&
          (envelope.payload as { readonly type?: string }).type === "run.cancelled",
      ),
    ).toBe(true);
    expect(runtime.cancel("run-cancel")).toBe(false);
  });

  it("pairs out-of-order native results by envelope id", async () => {
    const requests: ToolRequestEnvelope[] = [];
    const rpc = new NativeRpcClient({
      createId: (context) => context.callId,
      postMessage: (json) => requests.push(JSON.parse(json) as ToolRequestEnvelope)
    });
    const signal = new AbortController().signal;
    const first = rpc.execute("workspace.read", { path: "a" }, {
      runId: "run",
      projectId: "project",
      callId: "one",
      signal
    });
    const second = rpc.execute("workspace.read", { path: "b" }, {
      runId: "run",
      projectId: "project",
      callId: "two",
      signal
    });
    expect(requests.map(({ id }) => id)).toEqual(["one", "two"]);

    rpc.receive(
      JSON.stringify({
        version: 1,
        id: "two",
        type: "tool.result",
        runId: "run",
        projectId: "project",
        payload: toolSuccess("second")
      }),
    );
    rpc.receive(
      JSON.stringify({
        version: 1,
        id: "one",
        type: "tool.result",
        runId: "run",
        projectId: "project",
        payload: toolSuccess("first")
      }),
    );

    await expect(first).resolves.toEqual(toolSuccess("first"));
    await expect(second).resolves.toEqual(toolSuccess("second"));
    expect(rpc.pendingCount).toBe(0);
  });
});
