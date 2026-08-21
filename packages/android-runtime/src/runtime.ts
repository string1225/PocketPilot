import {
  DefaultAgentRunner,
  type AgentEvent,
  type AgentProvider,
  type AgentMessage,
  type AgentRunControl,
  type AgentRunResult
} from "@pocketpilot/agent-core";
import {
  OfflineCommandProvider,
  OpenAICompatibleProvider
} from "@pocketpilot/providers";
import { AllowAllPermissionPolicy, ToolRegistry } from "@pocketpilot/tool-runtime";

import { NativeRpcLlmTransport } from "./llm-transport.js";
import { createNativeWorkspaceTools, NativeRpcClient } from "./native-rpc.js";
import { createPluginTools } from "./plugin-tools.js";
import { createScriptTools, WorkerSandbox } from "./sandbox.js";
import {
  ANDROID_BRIDGE_VERSION,
  parseRuntimeStartRequest,
  type JavaScriptToNativeEnvelope,
  type RuntimeStartRequest
} from "./protocol.js";

export interface AndroidAgentRuntimeOptions {
  readonly postMessage: (envelopeJson: string) => void;
  readonly providerFactory?: (request: RuntimeStartRequest) => AgentProvider;
  readonly sandbox?: WorkerSandbox;
}

interface ActiveRun {
  readonly controller: AbortController;
  readonly promise: Promise<AgentRunResult>;
  readonly mailbox: RunMailbox;
}

class RunMailbox implements AgentRunControl {
  readonly #steering: AgentMessage[] = [];
  readonly #followUps: AgentMessage[] = [];
  readonly #waiters = new Set<(messages: readonly AgentMessage[] | undefined) => void>();
  #closed = false;

  public pushSteering(content: string): boolean {
    if (this.#closed || content.trim().length === 0) return false;
    this.#steering.push({ role: "user", content: content.trim() });
    return true;
  }

  public pushFollowUp(content: string): boolean {
    if (this.#closed || content.trim().length === 0) return false;
    this.#followUps.push({ role: "user", content: content.trim() });
    if (this.#waiters.size > 0) {
      const messages = this.#followUps.splice(0);
      for (const waiter of this.#waiters) waiter(messages);
      this.#waiters.clear();
    }
    return true;
  }

  public drainSteering(): readonly AgentMessage[] {
    return this.#steering.splice(0);
  }

  public waitForFollowUp(signal: AbortSignal): Promise<readonly AgentMessage[] | undefined> {
    const available = this.#followUps.splice(0);
    if (available.length > 0) return Promise.resolve(available);
    if (this.#closed || signal.aborted) return Promise.resolve(undefined);
    return new Promise((resolve) => {
      let settled = false;
      const finish = (messages: readonly AgentMessage[] | undefined) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        signal.removeEventListener("abort", aborted);
        this.#waiters.delete(finish);
        resolve(messages);
      };
      const aborted = () => finish(undefined);
      const timer = setTimeout(() => finish(undefined), 250);
      this.#waiters.add(finish);
      signal.addEventListener("abort", aborted, { once: true });
    });
  }

  public close(): void {
    this.#closed = true;
    for (const waiter of this.#waiters) waiter(undefined);
    this.#waiters.clear();
  }
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
  readonly #sandbox: WorkerSandbox;
  readonly #runs = new Map<string, ActiveRun>();

  public constructor(options: AndroidAgentRuntimeOptions) {
    this.#postMessage = options.postMessage;
    this.#rpc = new NativeRpcClient({ postMessage: options.postMessage });
    this.#sandbox = options.sandbox ?? new WorkerSandbox();
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
    const mailbox = new RunMailbox();
    // Android Native owns the user-visible, per-call approval UI. Allow the
    // bridge request through here so network/remote calls can reach that gate.
    const enabledTools = request.toolsEnabled === false
      ? []
      : [
          ...createNativeWorkspaceTools(this.#rpc),
          ...createScriptTools(this.#sandbox),
          ...createPluginTools(request.plugins ?? [], this.#sandbox)
        ];
    const registry = new ToolRegistry({
      permissionPolicy: new AllowAllPermissionPolicy()
    }).registerAll(enabledTools);
    const runner = new DefaultAgentRunner({
      provider: this.#providerFactory(request),
      tools: registry,
      ...(request.systemPrompt === undefined
        ? {}
        : { systemPrompt: request.systemPrompt }),
      onEvent: (event) => {
        this.#postEvent(event);
      },
      ...(request.runtime === undefined
        ? {}
        : {
            maxConcurrentTools: request.runtime.maxConcurrentTools,
            maxTurns: request.runtime.maxTurns
          })
    });

    const promise = runner
      .run({
        runId: request.runId,
        projectId: request.projectId,
        task: request.task,
        ...(request.messages === undefined ? {} : { messages: request.messages }),
        ...(request.resume === undefined ? {} : { resume: request.resume }),
        signal: controller.signal,
        control: mailbox
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
    this.#runs.set(request.runId, { controller, promise, mailbox });
    try {
      const result = await promise;
      return JSON.stringify(result);
    } finally {
      mailbox.close();
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

  public steer(runId: string, content: string): boolean {
    return this.#runs.get(runId)?.mailbox.pushSteering(content) ?? false;
  }

  public followUp(runId: string, content: string): boolean {
    return this.#runs.get(runId)?.mailbox.pushFollowUp(content) ?? false;
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
