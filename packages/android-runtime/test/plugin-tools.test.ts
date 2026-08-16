import { afterAll, describe, expect, it } from "vitest";
// Node types are intentionally not a production dependency of the browser runtime.
// @ts-expect-error The test runner provides this built-in module at runtime.
import { Worker as NodeWorker } from "node:worker_threads";
// @ts-expect-error The test runner provides this built-in module at runtime.
import { readFileSync } from "node:fs";
// @ts-expect-error The test runner provides this built-in module at runtime.
import { createHash } from "node:crypto";

import type { ToolContext } from "@pocketpilot/tool-runtime";

import {
  WorkerSandbox,
  createPluginTools,
  parseRuntimeStartRequest,
  type RuntimePluginPackage,
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
      "globalThis.postMessage = (value) => parentPort.postMessage(value);",
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
const sandbox = (): WorkerSandbox => {
  const value = new WorkerSandbox({ workerFactory });
  sandboxes.push(value);
  return value;
};

const context = (): ToolContext => ({
  runId: "run-plugin",
  projectId: "project-plugin",
  callId: "call-plugin",
  signal: new AbortController().signal
});

const schema = (
  properties: Record<string, unknown>,
  required: string[] = [],
): Record<string, unknown> => ({
  type: "object",
  properties,
  required,
  additionalProperties: false
});

const packageFrom = (overrides: Record<string, unknown> = {}): RuntimePluginPackage => {
  const request = parseRuntimeStartRequest(JSON.stringify({
    runId: "run-plugin",
    projectId: "project-plugin",
    task: "Use the plugin",
    plugins: [{
      id: "com.example.safe",
      name: "Safe plugin",
      version: "1.2.3",
      description: "Pure computation test plugin.",
      sourceSha256: "a".repeat(64),
      source: "async (toolName, input) => ({ toolName, input, bridge: typeof globalThis.PocketPilotNativeBridge })",
      tools: [
        {
          name: "echo",
          description: "Echo input.",
          risk: "read",
          inputSchema: schema({ text: { type: "string", maxLength: 100 } }, ["text"])
        },
        {
          name: "empty",
          description: "Accept no fields.",
          risk: "read",
          inputSchema: schema({})
        },
        {
          name: "own_to_string",
          description: "Require an own toString field.",
          risk: "read",
          inputSchema: schema({ toString: { type: "string" } }, ["toString"])
        },
        {
          name: "dunder",
          description: "Accept an explicitly declared __proto__ field.",
          risk: "read",
          inputSchema: schema(JSON.parse('{"__proto__":{"type":"string"}}') as Record<string, unknown>, ["__proto__"])
        }
      ],
      ...overrides
    }]
  }));
  const plugin = request.plugins?.[0];
  if (plugin === undefined) throw new Error("Test plugin was not parsed.");
  return plugin;
};

afterAll(() => sandboxes.forEach((value) => value.close()));

describe("plugin runtime tools", () => {
  it("loads the documented import bundle with a matching source hash", async () => {
    const bundle = JSON.parse(
      readFileSync(new URL("../../../docs/PLUGIN_EXAMPLE.json", import.meta.url), "utf8"),
    ) as {
      manifest: Record<string, unknown>;
      source: string;
    };
    const { manifestVersion: _manifestVersion, ...runtimeManifest } = bundle.manifest;
    expect(createHash("sha256").update(bundle.source, "utf8").digest("hex"))
      .toBe(runtimeManifest.sourceSha256);
    const request = parseRuntimeStartRequest(JSON.stringify({
      runId: "sample-run",
      projectId: "sample-project",
      task: "Count text",
      plugins: [{ ...runtimeManifest, source: bundle.source }]
    }));
    const tools = createPluginTools(request.plugins ?? [], sandbox());

    await expect(tools[0]?.execute({ text: "one two\n三" }, context())).resolves.toMatchObject({
      success: true,
      data: { codePoints: 9, lines: 2, words: 3 }
    });
  });

  it("qualifies names and executes the plugin in the isolated Worker", async () => {
    const tools = createPluginTools([packageFrom()], sandbox());
    expect(tools.map(({ name, risk }) => [name, risk])).toEqual([
      ["plugin.com.example.safe.echo", "read"],
      ["plugin.com.example.safe.empty", "read"],
      ["plugin.com.example.safe.own_to_string", "read"],
      ["plugin.com.example.safe.dunder", "read"]
    ]);

    const echo = tools[0];
    await expect(echo?.execute({ text: "hello" }, context())).resolves.toMatchObject({
      success: true,
      data: {
        toolName: "echo",
        input: { text: "hello" },
        bridge: "undefined"
      },
      metadata: {
        pluginId: "com.example.safe",
        pluginVersion: "1.2.3",
        durationMillis: expect.any(Number)
      }
    });
  });

  it("uses own-property semantics for strict and required fields", async () => {
    const tools = createPluginTools([packageFrom()], sandbox());
    const empty = tools.find(({ name }) => name.endsWith(".empty"));
    const ownToString = tools.find(({ name }) => name.endsWith(".own_to_string"));
    const dunder = tools.find(({ name }) => name.endsWith(".dunder"));

    await expect(
      empty?.execute(JSON.parse('{"toString":"attack"}'), context()),
    ).resolves.toMatchObject({ success: false, error: { code: "PLUGIN_INVALID_ARGUMENTS" } });
    await expect(ownToString?.execute({}, context())).resolves.toMatchObject({
      success: false,
      error: { code: "PLUGIN_INVALID_ARGUMENTS" }
    });
    await expect(
      dunder?.execute(JSON.parse('{"__proto__":"owned value"}'), context()),
    ).resolves.toMatchObject({
      success: true,
      data: { input: JSON.parse('{"__proto__":"owned value"}') }
    });
  });

  it("leaves enough sandbox wrapper budget at the 120 KiB installation boundary", async () => {
    const entry = "async () => ({ boundary: true })";
    const source = entry + " ".repeat(120 * 1_024 - entry.length);
    const plugin = packageFrom({
      source,
      tools: [{
        name: "boundary",
        description: "Exercise the maximum accepted source size.",
        risk: "read",
        inputSchema: schema({})
      }]
    });
    const tool = createPluginTools([plugin], sandbox())[0];

    await expect(tool?.execute({}, context())).resolves.toMatchObject({
      success: true,
      data: { boundary: true }
    });
  });

  it("compares object enum values independent of JSON property order", async () => {
    const plugin = packageFrom({
      source: "async (_toolName, input) => input.value",
      tools: [{
        name: "enum_object",
        description: "Accept a structurally equal object enum value.",
        risk: "read",
        inputSchema: schema({
          value: {
            type: "object",
            properties: {
              first: { type: "integer" },
              second: { type: "integer" }
            },
            required: ["first", "second"],
            additionalProperties: false,
            enum: [{ first: 1, second: 2 }]
          }
        }, ["value"])
      }]
    });
    const tool = createPluginTools([plugin], sandbox())[0];

    await expect(
      tool?.execute(JSON.parse('{"value":{"second":2,"first":1}}'), context()),
    ).resolves.toMatchObject({ success: true, data: { first: 1, second: 2 } });
  });

  it("rejects non-pure risk, loose schemas, unsupported schema keywords, and oversized source", () => {
    expect(() => packageFrom({
      tools: [{
        name: "network",
        description: "Must not install.",
        risk: "network",
        inputSchema: schema({})
      }]
    })).toThrow(/risk must be read/u);

    expect(() => packageFrom({
      tools: [{
        name: "loose",
        description: "Must be strict.",
        risk: "read",
        inputSchema: { type: "object", properties: {}, additionalProperties: true }
      }]
    })).toThrow(/additionalProperties/u);

    expect(() => packageFrom({
      tools: [{
        name: "regex",
        description: "Patterns are not in the safe subset.",
        risk: "read",
        inputSchema: schema({ value: { type: "string", pattern: "(a+)+$" } })
      }]
    })).toThrow(/unsupported field: pattern/u);

    expect(() => packageFrom({ source: "x".repeat(120 * 1_024 + 1) })).toThrow(/122880/u);
  });
});
