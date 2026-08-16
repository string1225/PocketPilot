import { afterAll, describe, expect, it, vi } from "vitest";
// Node types are intentionally not a production dependency of the browser runtime.
// @ts-expect-error The test runner provides this built-in module at runtime.
import { Worker as NodeWorker } from "node:worker_threads";

import type { JsonValue, ToolContext } from "@pocketpilot/tool-runtime";

import {
  AndroidAgentRuntime,
  SANDBOX_LIMITS,
  WorkerSandbox,
  createScriptTools,
  type SandboxWorker,
  type SandboxWorkerFactory
} from "../src/index.js";

interface NodeWorkerInstance {
  on(event: "message", listener: (message: unknown) => void): void;
  on(event: "error", listener: (error: Error) => void): void;
  on(event: "messageerror", listener: () => void): void;
  postMessage(message: string): void;
  terminate(): Promise<number>;
}

class NodeSandboxWorker implements SandboxWorker {
  public onmessage: Worker["onmessage"] = null;
  public onerror: Worker["onerror"] = null;
  public onmessageerror: Worker["onmessageerror"] = null;
  readonly #worker: NodeWorkerInstance;

  public constructor(source: string) {
    const bootstrap = [
      'const { parentPort } = require("node:worker_threads");',
      "globalThis.self = globalThis;",
      "for (const name of ['fetch', 'postMessage', 'Worker', 'BroadcastChannel', 'indexedDB']) delete globalThis[name];",
      "const capabilityPrototype = Object.create(Object.getPrototypeOf(globalThis));",
      "Object.defineProperties(capabilityPrototype, {",
      "  fetch: { value: () => Promise.resolve('escaped'), writable: true, configurable: true },",
      "  postMessage: { value: (value) => parentPort.postMessage(value), writable: true, configurable: true },",
      "  Worker: { value: function EscapedWorker() {}, writable: true, configurable: true },",
      "  BroadcastChannel: { value: function EscapedBroadcastChannel() {}, writable: true, configurable: true },",
      "  indexedDB: { get: () => ({ open: () => 'escaped' }), configurable: true }",
      "});",
      "Object.setPrototypeOf(globalThis, capabilityPrototype);",
      "globalThis.addEventListener = (type, listener) => {",
      '  if (type === "message") parentPort.on("message", (data) => listener({ data }));',
      "};",
      `new Function(${JSON.stringify(source)})();`
    ].join("\n");
    this.#worker = new NodeWorker(bootstrap, { eval: true }) as unknown as NodeWorkerInstance;
    this.#worker.on("message", (data) => {
      this.onmessage?.call(this as unknown as Worker, { data } as MessageEvent);
    });
    this.#worker.on("error", (error) => {
      this.onerror?.call(this as unknown as Worker, {
        message: error.message,
        preventDefault: () => undefined
      } as ErrorEvent);
    });
    this.#worker.on("messageerror", () => {
      this.onmessageerror?.call(this as unknown as Worker, { data: undefined } as MessageEvent);
    });
  }

  public postMessage(message: string): void {
    this.#worker.postMessage(message);
  }

  public terminate(): void {
    void this.#worker.terminate();
  }
}

const workerFactory: SandboxWorkerFactory = (source) => new NodeSandboxWorker(source);
const sandboxes: WorkerSandbox[] = [];

const createSandbox = (): WorkerSandbox => {
  const sandbox = new WorkerSandbox({ workerFactory });
  sandboxes.push(sandbox);
  return sandbox;
};

const context = (signal = new AbortController().signal): ToolContext => ({
  runId: "run-1",
  projectId: "project-1",
  callId: "call-1",
  signal
});

afterAll(() => {
  for (const sandbox of sandboxes) sandbox.close();
});

describe("WorkerSandbox", () => {
  it("runs JavaScript in a fresh worker with JSON input and bounded console output", async () => {
    const sandbox = createSandbox();

    const result = await sandbox.execute({
      language: "javascript",
      source: [
        'console.log("received", input.count);',
        "await Promise.resolve();",
        "return { doubled: input.count * 2 };"
      ].join("\n"),
      input: { count: 21 }
    });

    expect(result).toMatchObject({
      success: true,
      data: {
        language: "javascript",
        value: { doubled: 42 },
        console: [{ level: "log", arguments: ["received", 21] }],
        consoleTruncated: false,
        durationMillis: expect.any(Number)
      }
    });
    expect(sandbox.activeExecutionCount).toBe(0);
  });

  it("transpiles TypeScript on the host and supports top-level await in the body", async () => {
    const sandbox = createSandbox();

    const result = await sandbox.execute({
      language: "typescript",
      source: [
        "const values: number[] = (input as { values: number[] }).values;",
        "const total: number = await Promise.resolve(values.reduce((sum, value) => sum + value, 0));",
        'console.info({ total });',
        "return { total } satisfies { total: number };"
      ].join("\n"),
      input: { values: [2, 3, 5] }
    });

    expect(result).toMatchObject({
      success: true,
      data: {
        language: "typescript",
        value: { total: 10 },
        console: [{ level: "info", arguments: [{ total: 10 }] }]
      }
    });
  });

  it("has no DOM, native bridge, network, imports, persistence, or child workers", async () => {
    const sandbox = createSandbox();

    const result = await sandbox.execute({
      language: "javascript",
      source: [
        "return {",
        "  document: typeof globalThis.document,",
        "  bridge: typeof globalThis.PocketPilotNativeBridge,",
        "  runtime: typeof globalThis.PocketPilotRuntime,",
        "  fetch: typeof globalThis.fetch,",
        "  xhr: typeof globalThis.XMLHttpRequest,",
        "  socket: typeof globalThis.WebSocket,",
        "  events: typeof globalThis.EventSource,",
        "  imports: typeof globalThis.importScripts,",
        "  worker: typeof globalThis.Worker,",
        "  database: typeof globalThis.indexedDB,",
        "  cache: typeof globalThis.caches,",
        "  navigator: typeof globalThis.navigator,",
        "  rtc: typeof globalThis.RTCPeerConnection,",
        "  notification: typeof globalThis.Notification,",
        "  eval: typeof globalThis.eval,",
        "  fn: typeof globalThis.Function,",
        "  recoveredConstructor: typeof (() => {}).constructor,",
        "  constructorConstructor: typeof ({}).constructor.constructor,",
        "  privilegedScope: typeof scope,",
        "  privilegedPost: typeof safePostMessage,",
        "  privilegedFunction: typeof NativeFunction,",
        "  globalEntry: typeof globalThis.__pocketpilot_user_entry,",
        "  nodeProcess: typeof globalThis.process",
        "};"
      ].join("\n")
    });

    expect(result).toMatchObject({
      success: true,
      data: {
        value: {
          document: "undefined",
          bridge: "undefined",
          runtime: "undefined",
          fetch: "undefined",
          xhr: "undefined",
          socket: "undefined",
          events: "undefined",
          imports: "undefined",
          worker: "undefined",
          database: "undefined",
          cache: "undefined",
          navigator: "undefined",
          rtc: "undefined",
          notification: "undefined",
          eval: "undefined",
          fn: "undefined",
          recoveredConstructor: "undefined",
          constructorConstructor: "undefined",
          privilegedScope: "undefined",
          privilegedPost: "undefined",
          privilegedFunction: "undefined",
          globalEntry: "undefined",
          nodeProcess: "undefined"
        }
      }
    });
  });

  it("cannot invoke privileged wrapper locals or reconstruct blocked capabilities", async () => {
    const sandbox = createSandbox();
    const result = await sandbox.execute({
      language: "javascript",
      source: [
        "const attempt = (callback) => { try { callback(); return 'escaped'; } catch { return 'blocked'; } };",
        "return {",
        "  scope: attempt(() => scope.postMessage('escape')),",
        "  safePostMessage: attempt(() => safePostMessage('escape')),",
        "  nativeFunction: attempt(() => NativeFunction('return self')()),",
        "  constructor: attempt(() => ({}).constructor.constructor('return self')()),",
        "  fetch: attempt(() => fetch('https://example.com/')),",
        "  imports: attempt(() => importScripts('https://example.com/escape.js')),",
        "  bridge: attempt(() => PocketPilotNativeBridge.postMessage('escape'))",
        "};"
      ].join("\n")
    });

    expect(result).toMatchObject({
      success: true,
      data: {
        value: {
          scope: "blocked",
          safePostMessage: "blocked",
          nativeFunction: "blocked",
          constructor: "blocked",
          fetch: "blocked",
          imports: "blocked",
          bridge: "blocked"
        }
      }
    });
  });

  it("cannot recover capabilities from WorkerGlobalScope prototype descriptors", async () => {
    const sandbox = createSandbox();
    const result = await sandbox.execute({
      language: "javascript",
      source: [
        "const recover = (name) => {",
        "  let target = globalThis;",
        "  while (target !== null) {",
        "    const descriptor = Reflect.getOwnPropertyDescriptor(target, name);",
        "    if (descriptor) {",
        "      if (typeof descriptor.value === 'function') return descriptor.value;",
        "      if (typeof descriptor.get === 'function') {",
        "        const value = descriptor.get.call(globalThis);",
        "        if (value !== undefined) return value;",
        "      }",
        "    }",
        "    target = Reflect.getPrototypeOf(target);",
        "  }",
        "};",
        "const attempt = (callback) => { try { callback(); return 'escaped'; } catch { return 'blocked'; } };",
        "return {",
        "  fetch: attempt(() => recover('fetch').call(globalThis, 'https://example.com/')),",
        "  postMessage: attempt(() => recover('postMessage').call(globalThis, 'escape')),",
        "  worker: attempt(() => Reflect.construct(recover('Worker'), ['https://example.com/worker.js'])),",
        "  broadcast: attempt(() => Reflect.construct(recover('BroadcastChannel'), ['escape'])),",
        "  indexedDB: attempt(() => recover('indexedDB').open('escape'))",
        "};"
      ].join("\n")
    });

    expect(result).toMatchObject({
      success: true,
      data: {
        value: {
          fetch: "blocked",
          postMessage: "blocked",
          worker: "blocked",
          broadcast: "blocked",
          indexedDB: "blocked"
        }
      }
    });
  });

  it("rejects dynamic imports before creating a worker", async () => {
    let workerCreated = false;
    const sandbox = new WorkerSandbox({
      workerFactory: () => {
        workerCreated = true;
        throw new Error("must not run");
      }
    });
    sandboxes.push(sandbox);

    await expect(
      sandbox.execute({
        language: "javascript",
        source: 'return import("https://example.com/module.js");'
      }),
    ).resolves.toMatchObject({
      success: false,
      error: { code: "UNSUPPORTED_SYNTAX" }
    });
    expect(workerCreated).toBe(false);
  });

  it("rejects async generators that are newer than the raw Chrome 61 worker target", async () => {
    let workerCreated = false;
    const sandbox = new WorkerSandbox({
      workerFactory: () => {
        workerCreated = true;
        throw new Error("must not run");
      }
    });
    sandboxes.push(sandbox);

    await expect(
      sandbox.execute({
        language: "javascript",
        source: "async function* values() { yield 1; } return null;"
      }),
    ).resolves.toMatchObject({
      success: false,
      error: { code: "UNSUPPORTED_SYNTAX" }
    });
    expect(workerCreated).toBe(false);
  });

  it("rejects source that attempts to escape its generated function body", async () => {
    let workerCreated = false;
    const sandbox = new WorkerSandbox({
      workerFactory: () => {
        workerCreated = true;
        throw new Error("must not run");
      }
    });
    sandboxes.push(sandbox);

    await expect(
      sandbox.execute({
        language: "javascript",
        source: "}\nglobalThis.fetch('https://example.com/');\nif (false) {"
      }),
    ).resolves.toMatchObject({
      success: false,
      error: { code: "UNSUPPORTED_SYNTAX" }
    });
    expect(workerCreated).toBe(false);
  });

  it("terminates CPU-bound code at the deadline", async () => {
    const sandbox = createSandbox();
    const startedAt = Date.now();

    const result = await sandbox.execute({
      language: "javascript",
      source: "while (true) {}",
      timeoutMillis: SANDBOX_LIMITS.minTimeoutMillis
    });

    expect(result).toMatchObject({ success: false, error: { code: "TIMEOUT" } });
    expect(Date.now() - startedAt).toBeLessThan(2_000);
    expect(sandbox.activeExecutionCount).toBe(0);
  });

  it("terminates a running worker when the Agent run is aborted", async () => {
    const sandbox = createSandbox();
    const controller = new AbortController();
    const pending = sandbox.execute(
      {
        language: "javascript",
        source: "while (true) {}",
        timeoutMillis: 5_000
      },
      controller.signal,
    );
    setTimeout(() => controller.abort(), 30);

    await expect(pending).resolves.toMatchObject({
      success: false,
      error: { code: "CANCELLED" }
    });
    expect(sandbox.activeExecutionCount).toBe(0);
  });

  it("bounds concurrent workers and rejects excess executions before creating one", async () => {
    let workersCreated = 0;
    const sandbox = new WorkerSandbox({
      workerFactory: () => {
        workersCreated += 1;
        return {
          onmessage: null,
          onerror: null,
          onmessageerror: null,
          postMessage: () => undefined,
          terminate: () => undefined
        };
      }
    });
    sandboxes.push(sandbox);
    const pending = Array.from(
      { length: SANDBOX_LIMITS.maxConcurrentExecutions },
      () => sandbox.execute({
        language: "javascript",
        source: "return null;",
        timeoutMillis: SANDBOX_LIMITS.maxTimeoutMillis
      }),
    );

    expect(sandbox.activeExecutionCount).toBe(SANDBOX_LIMITS.maxConcurrentExecutions);
    await expect(
      sandbox.execute({ language: "javascript", source: "return null;" }),
    ).resolves.toMatchObject({ success: false, error: { code: "SANDBOX_BUSY" } });
    expect(workersCreated).toBe(SANDBOX_LIMITS.maxConcurrentExecutions);

    sandbox.close();
    await expect(Promise.all(pending)).resolves.toHaveLength(SANDBOX_LIMITS.maxConcurrentExecutions);
  });

  it("enforces source, input, output, console, and JSON-result limits", async () => {
    const sandbox = createSandbox();

    await expect(
      sandbox.execute({
        language: "javascript",
        source: `/*${"界".repeat(SANDBOX_LIMITS.maxSourceBytes)}*/ return null;`
      }),
    ).resolves.toMatchObject({
      success: false,
      error: { code: "SOURCE_LIMIT_EXCEEDED" }
    });

    await expect(
      sandbox.execute({
        language: "javascript",
        source: "return null;",
        input: { value: "x".repeat(SANDBOX_LIMITS.maxInputBytes) }
      }),
    ).resolves.toMatchObject({
      success: false,
      error: { code: "INPUT_LIMIT_EXCEEDED" }
    });

    await expect(
      sandbox.execute({
        language: "javascript",
        source: `return "x".repeat(${SANDBOX_LIMITS.maxOutputBytes + 1});`
      }),
    ).resolves.toMatchObject({
      success: false,
      error: { code: "OUTPUT_LIMIT_EXCEEDED" }
    });

    await expect(
      sandbox.execute({ language: "javascript", source: "return undefined;" }),
    ).resolves.toMatchObject({
      success: false,
      error: { code: "SCRIPT_RESULT_INVALID" }
    });

    const consoleResult = await sandbox.execute({
      language: "javascript",
      source: `for (let index = 0; index < ${SANDBOX_LIMITS.maxConsoleEntries + 10}; index += 1) console.log(index); return true;`
    });
    expect(consoleResult).toMatchObject({
      success: true,
      data: {
        consoleTruncated: true,
        value: true
      }
    });
    if (consoleResult.success) {
      expect(consoleResult.data.console).toHaveLength(SANDBOX_LIMITS.maxConsoleEntries);
    }

    await expect(
      sandbox.execute({
        language: "javascript",
        source: "return input;",
        input: Number.POSITIVE_INFINITY as unknown as JsonValue
      }),
    ).resolves.toMatchObject({ success: false, error: { code: "INVALID_INPUT" } });
  });

  it("closes active workers and remains closed", async () => {
    const sandbox = createSandbox();
    const pending = sandbox.execute({
      language: "javascript",
      source: "while (true) {}",
      timeoutMillis: 5_000
    });
    sandbox.close();

    await expect(pending).resolves.toMatchObject({
      success: false,
      error: { code: "SANDBOX_CLOSED" }
    });
    await expect(
      sandbox.execute({ language: "javascript", source: "return 1;" }),
    ).resolves.toMatchObject({
      success: false,
      error: { code: "SANDBOX_CLOSED" }
    });
  });

  it("keeps the Blob URL alive until the default worker is terminated", async () => {
    class BrowserWorkerStub {
      public onmessage: Worker["onmessage"] = null;
      public onerror: Worker["onerror"] = null;
      public onmessageerror: Worker["onmessageerror"] = null;
      public postMessage(): void {}
      public terminate(): void {}
    }
    const createObjectUrl = vi
      .spyOn(URL, "createObjectURL")
      .mockReturnValue("blob:pocketpilot-sandbox");
    const revokeObjectUrl = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    vi.stubGlobal("Worker", BrowserWorkerStub);
    try {
      const sandbox = new WorkerSandbox();
      sandboxes.push(sandbox);
      const pending = sandbox.execute({
        language: "javascript",
        source: "return true;",
        timeoutMillis: 5_000
      });

      expect(createObjectUrl).toHaveBeenCalledOnce();
      expect(revokeObjectUrl).not.toHaveBeenCalled();
      sandbox.close();
      await expect(pending).resolves.toMatchObject({
        success: false,
        error: { code: "SANDBOX_CLOSED" }
      });
      expect(revokeObjectUrl).toHaveBeenCalledOnce();
    } finally {
      createObjectUrl.mockRestore();
      revokeObjectUrl.mockRestore();
      vi.unstubAllGlobals();
    }
  });
});

describe("script tools", () => {
  it("publishes strict schemas and executes only validated arguments", async () => {
    const sandbox = createSandbox();
    const tools = createScriptTools(sandbox);

    expect(tools.map(({ name, risk }) => [name, risk])).toEqual([
      ["execute_js", "read"],
      ["execute_ts", "read"]
    ]);
    for (const tool of tools) {
      expect(tool.inputSchema).toMatchObject({
        type: "object",
        required: ["source"],
        additionalProperties: false,
        properties: {
          source: { type: "string", minLength: 1, maxLength: SANDBOX_LIMITS.maxSourceBytes },
          timeoutMillis: {
            type: "integer",
            minimum: SANDBOX_LIMITS.minTimeoutMillis,
            maximum: SANDBOX_LIMITS.maxTimeoutMillis,
            default: SANDBOX_LIMITS.defaultTimeoutMillis
          }
        }
      });
    }

    const javascript = tools.find(({ name }) => name === "execute_js");
    expect(javascript).toBeDefined();
    await expect(
      javascript?.execute({ source: "return input.ok;", input: { ok: true } }, context()),
    ).resolves.toMatchObject({ success: true, data: { value: true } });
    await expect(
      javascript?.execute({ source: "return true;", unexpected: true }, context()),
    ).resolves.toMatchObject({
      success: false,
      error: { code: "INVALID_INPUT", message: "Unsupported input field: unexpected" }
    });
  });

  it("runs an Agent tool call through execute_ts and returns the Worker result", async () => {
    const sandbox = createSandbox();
    let providerStep = 0;
    const runtime = new AndroidAgentRuntime({
      postMessage: () => undefined,
      sandbox,
      providerFactory: () => ({
        name: "sandbox-integration",
        complete: async (request) => {
          providerStep += 1;
          expect(request.tools.map(({ name }) => name)).toEqual(
            expect.arrayContaining(["execute_js", "execute_ts"]),
          );
          if (providerStep === 1) {
            return {
              toolCalls: [{
                id: "script-1",
                name: "execute_ts",
                arguments: {
                  source: "const value: number = input.value; return { answer: value * 2 };",
                  input: { value: 21 }
                }
              }]
            };
          }
          const toolMessage = [...request.messages]
            .reverse()
            .find(({ role }) => role === "tool");
          expect(toolMessage).toMatchObject({
            role: "tool",
            name: "execute_ts",
            result: {
              success: true,
              data: { language: "typescript", value: { answer: 42 } }
            }
          });
          return { content: "Sandbox returned 42." };
        }
      })
    });

    const result = JSON.parse(
      await runtime.start(
        JSON.stringify({
          runId: "sandbox-agent-run",
          projectId: "project-1",
          task: "Calculate twice 21 in TypeScript."
        }),
      ),
    ) as Record<string, unknown>;

    expect(result).toMatchObject({
      status: "completed",
      output: "Sandbox returned 42.",
      steps: 2
    });
    expect(providerStep).toBe(2);
  });
});
