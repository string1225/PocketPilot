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
  readonly onProgress: ((payload: unknown) => void) | undefined;
  readonly resolve: (result: ToolResult<unknown>) => void;
}

export interface NativeRpcExecutionOptions {
  readonly onProgress?: (payload: unknown) => void;
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
    options: NativeRpcExecutionOptions = {},
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
        onProgress: options.onProgress,
        resolve
      });
      context.signal.addEventListener("abort", onAbort, { once: true });
      // Abort may race the initial check (for example while an id is being
      // allocated). AbortSignal does not replay an event to a late listener.
      if (context.signal.aborted) {
        onAbort();
        return;
      }

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
    if (envelope.type === "tool.progress") {
      try {
        pending.onProgress?.(envelope.payload);
      } catch {
        // Progress is observational and cannot fail or settle the native call.
      }
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

const objectSchema = (
  properties: Readonly<Record<string, JsonSchema>>,
  required: readonly string[] = [],
): JsonSchema => ({
  type: "object",
  properties,
  required,
  additionalProperties: false
});

const workspacePathSchema: JsonSchema = {
  type: "string",
  minLength: 1,
  maxLength: 4_096,
  description: "A project-relative workspace path. Absolute paths and traversal are forbidden."
};

const optionalWorkspacePathSchema: JsonSchema = {
  type: "string",
  maxLength: 4_096,
  default: "",
  description: "An optional project-relative directory; use an empty string for the workspace root."
};

const workspaceContentSchema: JsonSchema = {
  type: "string",
  maxLength: 1_048_576,
  description: "UTF-8 text content, limited to 1 MiB by the native workspace."
};

const gitRemoteSchema: JsonSchema = {
  type: "string",
  minLength: 1,
  maxLength: 4_096,
  description: "A credential-free HTTPS Git remote URL."
};

const gitBranchSchema: JsonSchema = {
  type: "string",
  minLength: 1,
  maxLength: 256
};

const gitRemoteNameSchema: JsonSchema = {
  type: "string",
  minLength: 1,
  maxLength: 128,
  pattern: "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"
};

const gitCredentialProperties: Readonly<Record<string, JsonSchema>> = {
  useCredential: {
    type: "boolean",
    default: false,
    description: "Use the app's dedicated Git token credential."
  },
  username: {
    type: "string",
    minLength: 1,
    maxLength: 256,
    default: "git",
    description: "HTTPS Git username; used only when useCredential is true."
  }
};

const gitTimeoutMillisSchema: JsonSchema = {
  type: "integer",
  minimum: 1_000,
  maximum: 3_600_000,
  multipleOf: 1_000,
  default: 60_000,
  description: "Git network timeout in milliseconds, in whole-second increments."
};

const sshTimeoutMillisSchema: JsonSchema = {
  type: "integer",
  minimum: 1,
  maximum: 3_600_000,
  default: 60_000,
  description: "Operation timeout in milliseconds (1 to 3600000)."
};

const workspaceSchemas = {
  list: objectSchema({ path: optionalWorkspacePathSchema }),
  read: objectSchema({ path: workspacePathSchema }, ["path"]),
  write: objectSchema(
    { path: workspacePathSchema, content: workspaceContentSchema },
    ["path", "content"],
  ),
  create: objectSchema(
    { path: workspacePathSchema, content: workspaceContentSchema },
    ["path", "content"],
  ),
  delete: objectSchema({ path: workspacePathSchema }, ["path"]),
  move: objectSchema(
    { from: workspacePathSchema, to: workspacePathSchema },
    ["from", "to"],
  ),
  search: objectSchema(
    {
      query: { type: "string", minLength: 1, maxLength: 65_536 },
      limit: { type: "integer", minimum: 1, maximum: 500, default: 100 }
    },
    ["query"],
  ),
  patch: objectSchema(
    {
      path: workspacePathSchema,
      oldText: { ...workspaceContentSchema, minLength: 1 },
      newText: workspaceContentSchema,
      expectedOccurrences: {
        type: "integer",
        enum: [1],
        default: 1,
        description: "The native MVP requires exactly one occurrence."
      }
    },
    ["path", "oldText", "newText"],
  )
} as const;

const gitSchemas = {
  init: objectSchema({ initialBranch: { ...gitBranchSchema, default: "main" } }),
  clone: objectSchema(
    {
      remoteUrl: gitRemoteSchema,
      branch: gitBranchSchema,
      ...gitCredentialProperties,
      timeoutMillis: gitTimeoutMillisSchema
    },
    ["remoteUrl"],
  ),
  status: objectSchema({}),
  diff: objectSchema({
    maxBytes: {
      type: "integer",
      minimum: 1,
      maximum: 524_288,
      default: 524_288,
      description: "Maximum UTF-8 bytes returned for each of the staged and unstaged patches."
    }
  }),
  commit: objectSchema(
    {
      message: { type: "string", minLength: 1, maxLength: 65_536 },
      authorName: { type: "string", minLength: 1, maxLength: 256 },
      authorEmail: { type: "string", minLength: 3, maxLength: 320 }
    },
    ["message", "authorName", "authorEmail"],
  ),
  pull: objectSchema({
    remote: { ...gitRemoteNameSchema, default: "origin" },
    branch: gitBranchSchema,
    ...gitCredentialProperties,
    timeoutMillis: gitTimeoutMillisSchema
  }),
  push: objectSchema({
    remote: { ...gitRemoteNameSchema, default: "origin" },
    ...gitCredentialProperties,
    timeoutMillis: gitTimeoutMillisSchema
  })
} as const;

const sshExecuteSchema = objectSchema(
  {
    server: {
      type: "string",
      minLength: 1,
      maxLength: 512,
      description: "The id or name of a user-configured SSH server."
    },
    command: {
      type: "string",
      minLength: 1,
      maxLength: 4_096,
      description: "The command text shown in native approval before execution."
    },
    timeoutMillis: sshTimeoutMillisSchema,
    maxOutputBytes: {
      type: "integer",
      minimum: 1,
      maximum: 524_288,
      default: 524_288
    }
  },
  ["server", "command"],
);

const httpHeaderValueSchema: JsonSchema = {
  type: "string",
  minLength: 1,
  maxLength: 512,
  pattern: "^[^\\r\\n\\u0000]+$",
  description: "A bounded HTTP header value without control or Unicode formatting characters."
};

const httpRequestSchema = objectSchema(
  {
    method: {
      type: "string",
      enum: ["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"],
      default: "GET"
    },
    url: {
      type: "string",
      minLength: 1,
      maxLength: 4_096,
      description: "An absolute public HTTP(S) URL. HTTPS is required unless allowInsecureHttp is explicitly true; credentials, fragments, IP literals, and private-network targets are forbidden."
    },
    headers: objectSchema({
      Accept: httpHeaderValueSchema,
      "Content-Type": httpHeaderValueSchema,
      "If-Match": httpHeaderValueSchema,
      "If-None-Match": httpHeaderValueSchema
    }),
    body: {
      type: "string",
      maxLength: 262_144,
      description: "Optional UTF-8 request body. GET and HEAD requests cannot include a body."
    },
    timeoutMillis: {
      type: "integer",
      minimum: 1_000,
      maximum: 120_000,
      default: 30_000
    },
    maxResponseBytes: {
      type: "integer",
      minimum: 1,
      maximum: 524_288,
      default: 262_144
    },
    allowInsecureHttp: {
      type: "boolean",
      default: false,
      description: "Explicitly opt in to cleartext HTTP. Every cleartext request still requires native user approval."
    }
  },
  ["url"],
);

const imageAnalyzeSchema = objectSchema(
  {
    attachmentId: {
      type: "string",
      minLength: 36,
      maxLength: 64,
      description: "An app-owned image attachment id from the current project. Never pass a URI, path, URL, or base64 image."
    },
    prompt: {
      type: "string",
      minLength: 1,
      maxLength: 8_192,
      description: "What to identify, read, or explain in the attached image."
    }
  },
  ["attachmentId", "prompt"],
);

const nativeTool = (
  rpc: NativeRpcClient,
  name: string,
  description: string,
  risk: ToolRisk,
  inputSchema: JsonSchema,
): AgentTool<unknown, unknown> => ({
  name,
  description,
  risk,
  inputSchema,
  execute: (input, context) => rpc.execute(name, input, context)
});

export const createNativeWorkspaceTools = (
  rpc: NativeRpcClient,
): readonly AgentTool<unknown, unknown>[] => [
  nativeTool(rpc, "workspace.list", "List a workspace directory with bounded entries and truncation metadata.", "read", workspaceSchemas.list),
  nativeTool(rpc, "workspace.read", "Read a workspace text file.", "read", workspaceSchemas.read),
  nativeTool(rpc, "workspace.write", "Write an existing workspace text file.", "write", workspaceSchemas.write),
  nativeTool(rpc, "workspace.create", "Create a workspace text file.", "write", workspaceSchemas.create),
  nativeTool(rpc, "workspace.delete", "Delete one workspace file after native approval.", "write", workspaceSchemas.delete),
  nativeTool(rpc, "workspace.move", "Move a workspace path.", "write", workspaceSchemas.move),
  nativeTool(rpc, "workspace.search", "Search workspace text files.", "read", workspaceSchemas.search),
  nativeTool(rpc, "workspace.patch", "Apply one exact-text workspace replacement.", "write", workspaceSchemas.patch),
  nativeTool(rpc, "git.init", "Initialize Git after native approval.", "write", gitSchemas.init),
  nativeTool(rpc, "git.clone", "Clone an HTTPS Git repository after native approval; timeoutMillis is in milliseconds.", "network", gitSchemas.clone),
  nativeTool(rpc, "git.status", "Read bounded Git status entries and truncation metadata for the current project workspace.", "read", gitSchemas.status),
  nativeTool(rpc, "git.diff", "Read separately bounded staged and unstaged Git patches.", "read", gitSchemas.diff),
  nativeTool(rpc, "git.commit", "Stage changes and create a Git commit after native approval.", "write", gitSchemas.commit),
  nativeTool(rpc, "git.pull", "Pull from an HTTPS Git remote after native approval; timeoutMillis is in milliseconds.", "network", gitSchemas.pull),
  nativeTool(rpc, "git.push", "Push to an HTTPS Git remote after native approval and return bounded update metadata; timeoutMillis is in milliseconds.", "network", gitSchemas.push),
  nativeTool(rpc, "http.request", "Send one bounded request to a public HTTP(S) endpoint and return status, safe response headers, body, bodyEncoding (utf8 or base64), and truncated. GET/HEAD over HTTPS run automatically; mutating methods and every cleartext request require native approval. Authentication headers and private-network targets are forbidden.", "network", httpRequestSchema),
  nativeTool(rpc, "image.analyze", "Analyze one app-owned image attached to this project. The native layer selects the configured vision model and never exposes image bytes or credentials to JavaScript.", "network", imageAnalyzeSchema),
  nativeTool(rpc, "ssh.execute", "Execute one command after native approval; timeoutMillis is in milliseconds.", "remote", sshExecuteSchema)
];
