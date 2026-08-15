import type { AgentEvent, AgentMessage } from "@pocketpilot/agent-core";
import type {
  OpenAICompatibleConfig,
  OpenAICompatibleProtocol
} from "@pocketpilot/providers";
import type { ToolResult } from "@pocketpilot/tool-runtime";

export const ANDROID_BRIDGE_VERSION = 1 as const;

interface EnvelopeBase {
  readonly version: typeof ANDROID_BRIDGE_VERSION;
  readonly id: string;
  readonly runId: string;
  readonly projectId: string;
}

export interface ToolRequestEnvelope extends EnvelopeBase {
  readonly type: "tool.request";
  readonly payload: {
    readonly name: string;
    readonly arguments: unknown;
  };
}

export interface RuntimeEventEnvelope extends EnvelopeBase {
  readonly type: "event";
  readonly payload: AgentEvent | RuntimeReadyEvent;
}

export interface RuntimeReadyEvent {
  readonly type: "runtime.ready";
  readonly runtimeVersion: "0.1.0";
  readonly protocolVersion: typeof ANDROID_BRIDGE_VERSION;
}

export interface ToolResultEnvelope extends EnvelopeBase {
  readonly type: "tool.result";
  readonly payload: ToolResult<unknown>;
}

export interface ToolErrorEnvelope extends EnvelopeBase {
  readonly type: "tool.error";
  readonly payload: unknown;
}

export type JavaScriptToNativeEnvelope = ToolRequestEnvelope | RuntimeEventEnvelope;
export type NativeToJavaScriptEnvelope = ToolResultEnvelope | ToolErrorEnvelope;

export interface RuntimeStartRequest {
  readonly runId: string;
  readonly projectId: string;
  readonly task: string;
  readonly maxSteps?: number;
  readonly provider?: RuntimeProviderConfig;
  readonly systemPrompt?: string;
  readonly messages?: readonly AgentMessage[];
  readonly toolsEnabled?: boolean;
}

export type RuntimeProviderConfig =
  | { readonly type: "offline" }
  | ({ readonly type: "openai_compatible" } & OpenAICompatibleConfig);

export interface PocketPilotNativeBridge {
  postMessage(envelopeJson: string): void;
}

export interface PocketPilotRuntimeGlobal {
  start(requestJson: string): Promise<string>;
  receive(envelopeJson: string): void;
  cancel(runId: string): boolean;
}

export interface PocketPilotGlobalScope {
  PocketPilotNativeBridge?: PocketPilotNativeBridge;
  PocketPilotRuntime?: PocketPilotRuntimeGlobal;
}

const asRecord = (value: unknown): Record<string, unknown> | undefined =>
  typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;

const parseProviderConfig = (value: unknown): RuntimeProviderConfig | undefined => {
  if (value === undefined) return undefined;
  const record = asRecord(value);
  if (record === undefined || typeof record.type !== "string") {
    throw new Error("provider must be an object with a supported type.");
  }
  const forbiddenSecretField = Object.keys(record).find((key) => {
    const normalized = key.replace(/[_ -]/gu, "").toLowerCase();
    return normalized === "apikey" ||
      normalized === "authorization" ||
      normalized === "bearertoken";
  });
  if (forbiddenSecretField !== undefined) {
    throw new Error("API key material must not be sent to the TypeScript runtime.");
  }
  if (record.type === "offline") return { type: "offline" };
  if (record.type !== "openai_compatible") {
    throw new Error(`Unsupported provider type: ${record.type}`);
  }
  const allowedKeys = new Set(["type", "protocol", "baseUrl", "model", "credentialId"]);
  if (Object.keys(record).some((key) => !allowedKeys.has(key))) {
    throw new Error("provider contains an unsupported field.");
  }
  const protocol = record.protocol;
  if (
    protocol !== undefined &&
    protocol !== "chat_completions" &&
    protocol !== "responses"
  ) {
    throw new Error("provider.protocol must be chat_completions or responses.");
  }
  const credentialId = record.credentialId;
  if (typeof credentialId !== "string" || credentialId.trim().length === 0) {
    throw new Error("provider.credentialId must be a non-empty string.");
  }
  const baseUrl = record.baseUrl;
  const model = record.model;
  if (baseUrl !== undefined && typeof baseUrl !== "string") {
    throw new Error("provider.baseUrl must be a string.");
  }
  if (model !== undefined && typeof model !== "string") {
    throw new Error("provider.model must be a string.");
  }
  return {
    type: "openai_compatible",
    credentialId,
    ...(protocol === undefined
      ? {}
      : { protocol: protocol as OpenAICompatibleProtocol }),
    ...(baseUrl === undefined ? {} : { baseUrl }),
    ...(model === undefined ? {} : { model })
  };
};

const parseHistory = (value: unknown): readonly AgentMessage[] | undefined => {
  if (value === undefined) return undefined;
  if (!Array.isArray(value) || value.length > 100) {
    throw new Error("messages must be an array with at most 100 items.");
  }
  return value.map((item) => {
    const record = asRecord(item);
    if (
      record === undefined ||
      (record.role !== "user" && record.role !== "assistant") ||
      typeof record.content !== "string" ||
      record.content.length > 4 * 1_048_576
    ) {
      throw new Error("Historical messages must contain a user/assistant role and text content.");
    }
    return { role: record.role, content: record.content };
  });
};

export const parseRuntimeStartRequest = (json: string): RuntimeStartRequest => {
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch {
    throw new Error("Runtime start request is not valid JSON.");
  }
  const record = asRecord(parsed);
  if (record === undefined) {
    throw new Error("Runtime start request must be an object.");
  }
  const {
    runId,
    projectId,
    task,
    maxSteps,
    provider,
    systemPrompt,
    messages,
    toolsEnabled
  } = record;
  if (
    typeof runId !== "string" ||
    runId.trim().length === 0 ||
    typeof projectId !== "string" ||
    projectId.trim().length === 0 ||
    typeof task !== "string" ||
    task.trim().length === 0
  ) {
    throw new Error("runId, projectId, and task must be non-empty strings.");
  }
  if (
    maxSteps !== undefined &&
    (typeof maxSteps !== "number" ||
      !Number.isSafeInteger(maxSteps) ||
      maxSteps < 1 ||
      maxSteps > 64)
  ) {
    throw new Error("maxSteps must be an integer between 1 and 64.");
  }
  const parsedProvider = parseProviderConfig(provider);
  if (
    systemPrompt !== undefined &&
    (typeof systemPrompt !== "string" || systemPrompt.length > 16_384)
  ) {
    throw new Error("systemPrompt must be a string no longer than 16384 characters.");
  }
  if (toolsEnabled !== undefined && typeof toolsEnabled !== "boolean") {
    throw new Error("toolsEnabled must be a boolean.");
  }
  const parsedMessages = parseHistory(messages);
  return {
    runId,
    projectId,
    task,
    ...(maxSteps === undefined ? {} : { maxSteps }),
    ...(parsedProvider === undefined ? {} : { provider: parsedProvider }),
    ...(systemPrompt === undefined ? {} : { systemPrompt }),
    ...(parsedMessages === undefined ? {} : { messages: parsedMessages }),
    ...(toolsEnabled === undefined ? {} : { toolsEnabled })
  };
};

export const parseNativeEnvelope = (json: string): NativeToJavaScriptEnvelope => {
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch {
    throw new Error("Native bridge envelope is not valid JSON.");
  }
  const record = asRecord(parsed);
  if (
    record === undefined ||
    record.version !== ANDROID_BRIDGE_VERSION ||
    typeof record.id !== "string" ||
    typeof record.runId !== "string" ||
    typeof record.projectId !== "string" ||
    (record.type !== "tool.result" && record.type !== "tool.error") ||
    !("payload" in record)
  ) {
    throw new Error("Native bridge envelope has an invalid shape.");
  }
  return record as unknown as NativeToJavaScriptEnvelope;
};
