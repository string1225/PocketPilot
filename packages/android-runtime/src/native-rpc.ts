import {
  toolFailure,
  type AgentTool,
  type JsonSchema,
  type ToolContext,
  type ToolResult,
  type ToolRisk
} from "@pocketpilot/tool-runtime";

import {
  ANDROID_BRIDGE_VERSION,
  parseNativeEnvelope,
  type JavaScriptToNativeEnvelope,
  type NativeToJavaScriptEnvelope
} from "./protocol.js";

export interface NativeRpcClientOptions {
  readonly postMessage: (envelopeJson: string) => void;
  readonly createId?: (context: ToolContext) => string;
}

interface PendingCall {
  readonly runId: string;
  readonly projectId: string;
  readonly signal: AbortSignal;
  readonly onAbort: () => void;
  readonly resolve: (result: ToolResult<unknown>) => void;
}

let nextEnvelopeId = 0;

const defaultCreateId = (context: ToolContext): string => {
  nextEnvelopeId += 1;
  return `${context.callId}:${nextEnvelopeId}`;
};

const isToolResult = (value: unknown): value is ToolResult<unknown> => {
  if (typeof value !== "object" || value === null || !("success" in value)) {
    return false;
  }
  const success = (value as { readonly success?: unknown }).success;
  if (success === true) {
    return "data" in value;
  }
  if (success !== false || !("error" in value)) {
    return false;
  }
  const error = (value as { readonly error?: unknown }).error;
  return (
    typeof error === "object" &&
    error !== null &&
    "code" in error &&
    typeof error.code === "string" &&
    "message" in error &&
    typeof error.message === "string"
  );
};

const nativeErrorResult = (payload: unknown): ToolResult<never> => {
  if (isToolResult(payload) && !payload.success) {
    return payload;
  }
  if (typeof payload === "object" && payload !== null) {
    const record = payload as Record<string, unknown>;
    if (typeof record.code === "string" && typeof record.message === "string") {
      return toolFailure(record.code, record.message, {
        ...(typeof record.retryable === "boolean"
          ? { retryable: record.retryable }
          : {})
      });
    }
  }
  return toolFailure("NATIVE_TOOL_FAILED", "Native tool execution failed.");
};

export class NativeRpcClient {
  readonly #postMessage: (envelopeJson: string) => void;
  readonly #createId: (context: ToolContext) => string;
  readonly #pending = new Map<string, PendingCall>();

  public constructor(options: NativeRpcClientOptions) {
    this.#postMessage = options.postMessage;
    this.#createId = options.createId ?? defaultCreateId;
  }

  public execute(
    name: string,
    input: unknown,
    context: ToolContext,
  ): Promise<ToolResult<unknown>> {
    if (context.signal.aborted) {
      return Promise.resolve(toolFailure("CANCELLED", "Tool execution was cancelled."));
    }
    const id = this.#createId(context);
    if (this.#pending.has(id)) {
      return Promise.resolve(
        toolFailure("BRIDGE_DUPLICATE_ID", `Bridge envelope id already exists: ${id}`),
      );
    }

    const envelope: JavaScriptToNativeEnvelope = {
      version: ANDROID_BRIDGE_VERSION,
      id,
      type: "tool.request",
      runId: context.runId,
      projectId: context.projectId,
      payload: { name, arguments: input === undefined ? null : input }
    };

    return new Promise((resolve) => {
      const finish = (result: ToolResult<unknown>): void => {
        const pending = this.#pending.get(id);
        if (pending === undefined) {
          return;
        }
        this.#pending.delete(id);
        pending.signal.removeEventListener("abort", pending.onAbort);
        resolve(result);
      };
      const onAbort = (): void => {
        finish(toolFailure("CANCELLED", "Tool execution was cancelled."));
      };
      this.#pending.set(id, {
        runId: context.runId,
        projectId: context.projectId,
        signal: context.signal,
        onAbort,
        resolve
      });
      context.signal.addEventListener("abort", onAbort, { once: true });

      let serialized: string;
      try {
        serialized = JSON.stringify(envelope);
      } catch {
        finish(
          toolFailure("BRIDGE_SERIALIZATION_FAILED", "Tool arguments are not serializable."),
        );
        return;
      }
      try {
        this.#postMessage(serialized);
      } catch (error) {
        finish(
          toolFailure(
            "BRIDGE_UNAVAILABLE",
            error instanceof Error ? error.message : String(error),
            { retryable: true },
          ),
        );
      }
    });
  }

  public receive(envelopeJson: string): void {
    let envelope: NativeToJavaScriptEnvelope;
    try {
      envelope = parseNativeEnvelope(envelopeJson);
    } catch {
      return;
    }
    const pending = this.#pending.get(envelope.id);
    if (pending === undefined) {
      return;
    }
    if (
      pending.runId !== envelope.runId ||
      pending.projectId !== envelope.projectId
    ) {
      this.#settle(
        envelope.id,
        toolFailure(
          "BRIDGE_CONTEXT_MISMATCH",
          "Native response does not match the pending run and project.",
        ),
      );
      return;
    }
    if (envelope.type === "tool.error") {
      this.#settle(envelope.id, nativeErrorResult(envelope.payload));
      return;
    }
    this.#settle(
      envelope.id,
      isToolResult(envelope.payload)
        ? envelope.payload
        : toolFailure(
            "BRIDGE_INVALID_RESULT",
            "Native tool result has an invalid shape.",
          ),
    );
  }

  public get pendingCount(): number {
    return this.#pending.size;
  }

  #settle(id: string, result: ToolResult<unknown>): void {
    const pending = this.#pending.get(id);
    if (pending === undefined) {
      return;
    }
    this.#pending.delete(id);
    pending.signal.removeEventListener("abort", pending.onAbort);
    pending.resolve(result);
  }
}

const inputObjectSchema: JsonSchema = {
  type: "object",
  additionalProperties: true
};

const nativeTool = (
  rpc: NativeRpcClient,
  name: string,
  description: string,
  risk: ToolRisk,
): AgentTool<unknown, unknown> => ({
  name,
  description,
  risk,
  inputSchema: inputObjectSchema,
  execute: (input, context) => rpc.execute(name, input, context)
});

export const createNativeWorkspaceTools = (
  rpc: NativeRpcClient,
): readonly AgentTool<unknown, unknown>[] => [
  nativeTool(rpc, "workspace.list", "List a workspace directory.", "read"),
  nativeTool(rpc, "workspace.read", "Read a workspace text file.", "read"),
  nativeTool(rpc, "workspace.write", "Write an existing workspace text file.", "write"),
  nativeTool(rpc, "workspace.create", "Create a workspace text file.", "write"),
  nativeTool(rpc, "workspace.delete", "Delete a workspace path.", "write"),
  nativeTool(rpc, "workspace.move", "Move a workspace path.", "write"),
  nativeTool(rpc, "workspace.search", "Search workspace text files.", "read"),
  nativeTool(rpc, "workspace.patch", "Apply an exact-text workspace patch.", "write")
];
