import {
  DefaultAgentRunner,
  type AgentEvent,
  type AgentProvider,
  type AgentRunResult
} from "@pocketpilot/agent-core";
import {
  OfflineCommandProvider,
  OpenAICompatibleProvider
} from "@pocketpilot/providers";
import { AllowAllPermissionPolicy, ToolRegistry } from "@pocketpilot/tool-runtime";

import { NativeRpcLlmTransport } from "./llm-transport.js";
import { createNativeWorkspaceTools, NativeRpcClient } from "./native-rpc.js";
import {
  ANDROID_BRIDGE_VERSION,
  parseRuntimeStartRequest,
  type JavaScriptToNativeEnvelope,
  type RuntimeStartRequest
} from "./protocol.js";

export interface AndroidAgentRuntimeOptions {
  readonly postMessage: (envelopeJson: string) => void;
  readonly providerFactory?: (request: RuntimeStartRequest) => AgentProvider;
}

interface ActiveRun {
  readonly controller: AbortController;
  readonly promise: Promise<AgentRunResult>;
}

const fallbackFailure = (
  request: RuntimeStartRequest,
  error: unknown,
): AgentRunResult => ({
  runId: request.runId,
  projectId: request.projectId,
  status: "failed",
  steps: 0,
  messages: [{ role: "user", content: request.task }],
  events: [],
  error: {
    code: "RUNTIME_FAILED",
    message: error instanceof Error ? error.message : String(error)
  }
});

export class AndroidAgentRuntime {
  readonly #postMessage: (envelopeJson: string) => void;
  readonly #providerFactory: (request: RuntimeStartRequest) => AgentProvider;
  readonly #rpc: NativeRpcClient;
  readonly #runs = new Map<string, ActiveRun>();

  public constructor(options: AndroidAgentRuntimeOptions) {
    this.#postMessage = options.postMessage;
    this.#rpc = new NativeRpcClient({ postMessage: options.postMessage });
    this.#providerFactory = options.providerFactory ?? ((request) => {
      const provider = request.provider;
      if (provider === undefined || provider.type === "offline") {
        return new OfflineCommandProvider();
      }
      return new OpenAICompatibleProvider(
        {
          credentialId: provider.credentialId,
          ...(provider.protocol === undefined ? {} : { protocol: provider.protocol }),
          ...(provider.baseUrl === undefined ? {} : { baseUrl: provider.baseUrl }),
          ...(provider.model === undefined ? {} : { model: provider.model })
        },
        new NativeRpcLlmTransport(this.#rpc),
      );
    });
  }

  public async start(requestJson: string): Promise<string> {
    const request = parseRuntimeStartRequest(requestJson);
    if (this.#runs.has(request.runId)) {
      throw new Error(`Run is already active: ${request.runId}`);
    }

    const controller = new AbortController();
    // Android Native owns the user-visible, per-call approval UI. Allow the
    // bridge request through here so network/remote calls can reach that gate.
    const registry = new ToolRegistry({
      permissionPolicy: new AllowAllPermissionPolicy()
    }).registerAll(
      request.toolsEnabled === false ? [] : createNativeWorkspaceTools(this.#rpc),
    );
    const runner = new DefaultAgentRunner({
      provider: this.#providerFactory(request),
      tools: registry,
      maxSteps: request.maxSteps ?? 8,
      ...(request.systemPrompt === undefined
        ? {}
        : { systemPrompt: request.systemPrompt }),
      onEvent: (event) => {
        this.#postEvent(event);
      }
    });

    const promise = runner
      .run({
        runId: request.runId,
        projectId: request.projectId,
        task: request.task,
        ...(request.messages === undefined ? {} : { messages: request.messages }),
        signal: controller.signal
      })
      .catch((error: unknown) => {
        const result = fallbackFailure(request, error);
        const failureEvent: AgentEvent = {
          type: "run.failed",
          runId: request.runId,
          projectId: request.projectId,
          sequence: 1,
          at: Date.now(),
          steps: 0,
          error: result.status === "failed" ? result.error : {
            code: "RUNTIME_FAILED",
            message: "Runtime failed."
          }
        };
        this.#postEvent(failureEvent);
        return result;
      });
    this.#runs.set(request.runId, { controller, promise });
    try {
      const result = await promise;
      return JSON.stringify(result);
    } finally {
      this.#runs.delete(request.runId);
    }
  }

  public receive(envelopeJson: string): void {
    this.#rpc.receive(envelopeJson);
  }

  public cancel(runId: string): boolean {
    const active = this.#runs.get(runId);
    if (active === undefined) {
      return false;
    }
    active.controller.abort();
    return true;
  }

  public get activeRunCount(): number {
    return this.#runs.size;
  }

  public get pendingToolCount(): number {
    return this.#rpc.pendingCount;
  }

  #postEvent(event: AgentEvent): void {
    const envelope: JavaScriptToNativeEnvelope = {
      version: ANDROID_BRIDGE_VERSION,
      id: `event:${event.runId}:${event.sequence}`,
      type: "event",
      runId: event.runId,
      projectId: event.projectId,
      payload: event
    };
    try {
      this.#postMessage(JSON.stringify(envelope));
    } catch {
      // Native event delivery is observational; tool RPC still reports bridge failures.
    }
  }
}
