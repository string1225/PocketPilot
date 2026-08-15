import type { AgentEvent } from "@pocketpilot/agent-core";
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
}

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
  const { runId, projectId, task, maxSteps } = record;
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
  return {
    runId,
    projectId,
    task,
    ...(maxSteps === undefined ? {} : { maxSteps })
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
