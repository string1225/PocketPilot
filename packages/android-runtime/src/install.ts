import { AndroidAgentRuntime } from "./runtime.js";
import {
  installAbortControllerFallback,
  resolveRuntimeGlobal
} from "./abort-controller-fallback.js";
import type {
  PocketPilotGlobalScope,
  PocketPilotRuntimeGlobal
} from "./protocol.js";
import { ANDROID_BRIDGE_VERSION } from "./protocol.js";

interface StartFailureContext {
  readonly runId: string;
  readonly projectId: string;
  readonly task: string;
}

const errorMessage = (error: unknown): string =>
  error instanceof Error ? error.message : String(error);

const parseStartFailureContext = (requestJson: string): StartFailureContext | undefined => {
  try {
    const value = JSON.parse(requestJson) as unknown;
    if (typeof value !== "object" || value === null || Array.isArray(value)) return undefined;
    const record = value as Record<string, unknown>;
    if (
      typeof record.runId !== "string" ||
      record.runId.trim().length === 0 ||
      typeof record.projectId !== "string" ||
      record.projectId.trim().length === 0
    ) {
      return undefined;
    }
    return {
      runId: record.runId,
      projectId: record.projectId,
      task: typeof record.task === "string" ? record.task : ""
    };
  } catch {
    return undefined;
  }
};

export const installPocketPilotRuntime = (
  target: PocketPilotGlobalScope = resolveRuntimeGlobal() as PocketPilotGlobalScope,
): PocketPilotRuntimeGlobal => {
  installAbortControllerFallback();
  const runtime = new AndroidAgentRuntime({
    postMessage: (envelopeJson) => {
      const bridge = target.PocketPilotNativeBridge;
      if (bridge === undefined || typeof bridge.postMessage !== "function") {
        throw new Error("PocketPilotNativeBridge.postMessage is unavailable.");
      }
      bridge.postMessage(envelopeJson);
    }
  });
  const api: PocketPilotRuntimeGlobal = {
    start: (requestJson) => runtime.start(requestJson).catch((error: unknown) => {
      const context = parseStartFailureContext(requestJson);
      const failure = {
        runId: context?.runId ?? "",
        projectId: context?.projectId ?? "",
        status: "failed" as const,
        steps: 0,
        messages: context?.task === undefined || context.task.length === 0
          ? []
          : [{ role: "user" as const, content: context.task }],
        events: [],
        error: {
          code: "RUNTIME_START_FAILED",
          message: errorMessage(error)
        }
      };
      if (context !== undefined) {
        try {
          target.PocketPilotNativeBridge?.postMessage(
            JSON.stringify({
              version: ANDROID_BRIDGE_VERSION,
              id: `event:${context.runId}:start-failed`,
              type: "event",
              runId: context.runId,
              projectId: context.projectId,
              payload: {
                type: "run.failed",
                runId: context.runId,
                projectId: context.projectId,
                sequence: 1,
                at: Date.now(),
                steps: 0,
                error: failure.error
              }
            }),
          );
        } catch {
          // A failed diagnostic delivery must not recreate an unhandled start
          // rejection in the fire-and-forget WebView API.
        }
      }
      return JSON.stringify(failure);
    }),
    receive: (envelopeJson) => {
      runtime.receive(envelopeJson);
    },
    cancel: (runId) => runtime.cancel(runId)
  };
  target.PocketPilotRuntime = api;
  try {
    target.PocketPilotNativeBridge?.postMessage(
      JSON.stringify({
        version: ANDROID_BRIDGE_VERSION,
        id: "runtime.ready",
        type: "event",
        runId: "",
        projectId: "",
        payload: {
          type: "runtime.ready",
          runtimeVersion: "0.1.0",
          protocolVersion: ANDROID_BRIDGE_VERSION
        }
      }),
    );
  } catch {
    // Android registers the bridge before loading the bundle. A missing bridge is
    // still surfaced by the first tool call without making the bundle unusable.
  }
  return api;
};
