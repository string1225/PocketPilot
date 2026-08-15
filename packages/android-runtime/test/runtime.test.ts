import { describe, expect, it, vi } from "vitest";

import { toolSuccess } from "@pocketpilot/tool-runtime";

import {
  ANDROID_BRIDGE_VERSION,
  AndroidAgentRuntime,
  NativeRpcClient,
  createNativeWorkspaceTools,
  installAbortControllerFallback,
  installPocketPilotRuntime,
  resolveRuntimeGlobal,
  type PocketPilotGlobalScope,
  type ToolRequestEnvelope
} from "../src/index.js";

const parse = (json: string): Record<string, unknown> =>
  JSON.parse(json) as Record<string, unknown>;

describe("Android runtime bridge", () => {
  it("installs the required global API and immediately announces runtime.ready", () => {
    const postMessage = vi.fn();
    const target: PocketPilotGlobalScope = {
      PocketPilotNativeBridge: { postMessage }
    };

    const api = installPocketPilotRuntime(target);

    expect(target.PocketPilotRuntime).toBe(api);
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
    const api = installPocketPilotRuntime({
      PocketPilotNativeBridge: { postMessage }
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

  it("settles cancellation that races native RPC registration without posting", async () => {
    const controller = new AbortController();
    const postMessage = vi.fn();
    const rpc = new NativeRpcClient({
      createId: () => {
        controller.abort();
        return "cancelled-before-registration";
      },
      postMessage
    });

    await expect(
      rpc.execute("workspace.read", { path: "a.txt" }, {
        runId: "run",
        projectId: "project",
        callId: "call",
        signal: controller.signal
      }),
    ).resolves.toMatchObject({
      success: false,
      error: { code: "CANCELLED" }
    });
    expect(postMessage).not.toHaveBeenCalled();
    expect(rpc.pendingCount).toBe(0);
  });

  it("publishes strict per-tool schemas with millisecond network timeouts", () => {
    const rpc = new NativeRpcClient({ postMessage: vi.fn() });
    const tools = createNativeWorkspaceTools(rpc);
    const schemas = new Map(tools.map((tool) => [tool.name, tool.inputSchema]));

    expect(tools.map(({ name, risk }) => [name, risk])).toEqual([
      ["workspace.list", "read"],
      ["workspace.read", "read"],
      ["workspace.write", "write"],
      ["workspace.create", "write"],
      ["workspace.delete", "write"],
      ["workspace.move", "write"],
      ["workspace.search", "read"],
      ["workspace.patch", "write"],
      ["git.init", "write"],
      ["git.clone", "network"],
      ["git.status", "read"],
      ["git.diff", "read"],
      ["git.commit", "write"],
      ["git.pull", "network"],
      ["git.push", "network"],
      ["ssh.execute", "remote"]
    ]);
    for (const schema of schemas.values()) {
      expect(schema.type).toBe("object");
      expect(schema.additionalProperties).toBe(false);
      expect(schema.required).toBeInstanceOf(Array);
      expect(schema.properties).toBeDefined();
    }

    expect(schemas.get("workspace.read")).toMatchObject({
      required: ["path"],
      properties: { path: { type: "string", minLength: 1 } }
    });
    expect(schemas.get("workspace.search")).toMatchObject({
      required: ["query"],
      properties: {
        query: { type: "string", minLength: 1 },
        limit: { type: "integer", minimum: 1, maximum: 500 }
      }
    });
    expect(Object.keys(schemas.get("workspace.delete")?.properties ?? {})).toEqual(["path"]);
    expect(Object.keys(schemas.get("workspace.search")?.properties ?? {})).toEqual([
      "query",
      "limit"
    ]);
    expect(schemas.get("workspace.patch")).toMatchObject({
      properties: {
        expectedOccurrences: { type: "integer", enum: [1], default: 1 }
      }
    });
    expect(schemas.get("git.commit")).toMatchObject({
      required: ["message", "authorName", "authorEmail"]
    });
    expect(schemas.get("git.diff")).toMatchObject({
      properties: {
        maxBytes: { type: "integer", minimum: 1, maximum: 524_288, default: 524_288 }
      }
    });
    expect(schemas.get("ssh.execute")).toMatchObject({
      required: ["server", "command"],
      properties: {
        command: { type: "string", minLength: 1, maxLength: 4_096 },
        timeoutMillis: { type: "integer", minimum: 1, maximum: 3_600_000 },
        maxOutputBytes: { type: "integer", minimum: 1, maximum: 524_288, default: 524_288 }
      }
    });
    expect(schemas.get("git.clone")).toMatchObject({
      properties: {
        timeoutMillis: {
          type: "integer",
          minimum: 1_000,
          maximum: 3_600_000,
          multipleOf: 1_000,
          default: 60_000
        }
      }
    });

    for (const name of ["git.clone", "git.pull", "git.push", "ssh.execute"]) {
      const properties = schemas.get(name)?.properties;
      expect(properties).toHaveProperty("timeoutMillis");
      expect(properties).not.toHaveProperty("timeout");
      expect(properties).not.toHaveProperty("timeoutSeconds");
    }
  });

  it("forwards timeoutMillis unchanged in native tool envelopes", async () => {
    const postMessage = vi.fn();
    const controller = new AbortController();
    const rpc = new NativeRpcClient({ postMessage });
    const ssh = createNativeWorkspaceTools(rpc).find((tool) => tool.name === "ssh.execute");
    expect(ssh).toBeDefined();

    const pending = ssh?.execute(
      { server: "dev", command: "pwd", timeoutMillis: 12_345 },
      {
        runId: "run",
        projectId: "project",
        callId: "ssh-call",
        signal: controller.signal
      },
    );
    expect(JSON.parse(postMessage.mock.calls[0]?.[0] as string)).toMatchObject({
      payload: {
        name: "ssh.execute",
        arguments: { server: "dev", command: "pwd", timeoutMillis: 12_345 }
      }
    });

    controller.abort();
    await expect(pending).resolves.toMatchObject({
      success: false,
      error: { code: "CANCELLED" }
    });
  });
});
