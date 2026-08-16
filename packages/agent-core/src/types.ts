import type {
  ToolDefinition,
  ToolError,
  ToolResult
} from "@pocketpilot/tool-runtime";

export type AgentRunStatus =
  | "idle"
  | "running"
  | "waiting_for_approval"
  | "completed"
  | "failed"
  | "cancelled";

export interface ProviderToolCall {
  readonly id: string;
  readonly name: string;
  readonly arguments: unknown;
}

export type AgentMessage =
  | { readonly role: "system"; readonly content: string }
  | { readonly role: "user"; readonly content: string }
  | {
      readonly role: "assistant";
      readonly content: string;
      readonly toolCalls?: readonly ProviderToolCall[];
    }
  | {
      readonly role: "tool";
      readonly content: string;
      readonly toolCallId: string;
      readonly name: string;
      readonly result: ToolResult<unknown>;
    };

export interface ProviderRequest {
  readonly runId: string;
  readonly projectId: string;
  readonly step: number;
  readonly messages: readonly AgentMessage[];
  readonly tools: readonly ToolDefinition[];
  readonly signal: AbortSignal;
  /**
   * Optional, in-process observer for text generated before the provider call
   * completes. It is never serialized to a native or network boundary.
   */
  readonly onStreamEvent?: (event: ProviderStreamEvent) => void;
}

export interface ProviderTokenUsage {
  readonly inputTokens?: number;
  readonly outputTokens?: number;
  readonly totalTokens?: number;
}

export interface ProviderStreamEvent {
  readonly contentDelta?: string;
  readonly usage?: ProviderTokenUsage;
}

export interface ProviderResponse {
  readonly content?: string;
  readonly toolCalls?: readonly ProviderToolCall[];
  readonly usage?: ProviderTokenUsage;
}

export interface AgentProvider {
  readonly name: string;
  complete(request: ProviderRequest): Promise<ProviderResponse>;
}

interface AgentEventBase {
  readonly sequence: number;
  readonly at: number;
  readonly runId: string;
  readonly projectId: string;
}

export type AgentEvent =
  | (AgentEventBase & {
      readonly type: "run.started";
      readonly task: string;
    })
  | (AgentEventBase & {
      readonly type: "assistant.delta";
      readonly messageId: string;
      readonly delta: string;
      /** Complete assistant text accumulated for this provider step. */
      readonly content: string;
    })
  | (AgentEventBase & {
      readonly type: "assistant.message";
      readonly messageId: string;
      readonly content: string;
      readonly status?: "completed" | "failed" | "cancelled";
      readonly usage?: ProviderTokenUsage;
    })
  | (AgentEventBase & {
      readonly type: "tool.started";
      readonly call: ProviderToolCall;
    })
  | (AgentEventBase & {
      readonly type: "tool.finished";
      readonly call: ProviderToolCall;
      readonly result: ToolResult<unknown>;
    })
  | (AgentEventBase & {
      readonly type: "run.waiting_for_approval";
      readonly call: ProviderToolCall;
      readonly reason: string;
    })
  | (AgentEventBase & {
      readonly type: "run.completed";
      readonly output: string;
      readonly steps: number;
      readonly usage?: ProviderTokenUsage;
    })
  | (AgentEventBase & {
      readonly type: "run.failed";
      readonly error: ToolError;
      readonly steps: number;
    })
  | (AgentEventBase & {
      readonly type: "run.cancelled";
      readonly steps: number;
    });

export interface AgentRunInput {
  readonly runId: string;
  readonly projectId: string;
  readonly task: string;
  readonly signal?: AbortSignal;
  readonly messages?: readonly AgentMessage[];
}

interface AgentRunResultBase {
  readonly runId: string;
  readonly projectId: string;
  readonly messages: readonly AgentMessage[];
  readonly events: readonly AgentEvent[];
  readonly steps: number;
}

export type AgentRunResult =
  | (AgentRunResultBase & {
      readonly status: "completed";
      readonly output: string;
    })
  | (AgentRunResultBase & {
      readonly status: "waiting_for_approval";
      readonly pendingCall: ProviderToolCall;
      readonly error: ToolError;
    })
  | (AgentRunResultBase & {
      readonly status: "failed";
      readonly error: ToolError;
    })
  | (AgentRunResultBase & {
      readonly status: "cancelled";
    });

export interface AgentRunner {
  run(input: AgentRunInput): Promise<AgentRunResult>;
}
