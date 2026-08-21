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
  readonly cachedInputTokens?: number;
}

export interface ProviderStreamEvent {
  readonly contentDelta?: string;
  readonly usage?: ProviderTokenUsage;
}

export type ProviderFinishReason =
  | "stop"
  | "tool_calls"
  | "length"
  | "content_filter"
  | "unknown";

export interface ProviderResponse {
  readonly content?: string;
  readonly toolCalls?: readonly ProviderToolCall[];
  readonly usage?: ProviderTokenUsage;
  readonly finishReason?: ProviderFinishReason;
}

export interface AgentProvider {
  readonly name: string;
  readonly contextWindowTokens?: number;
  complete(request: ProviderRequest): Promise<ProviderResponse>;
}

export interface AgentRunControl {
  /** Messages that should redirect the current turn before its next model call. */
  drainSteering(): readonly AgentMessage[];
  /**
   * Waits briefly for queued follow-ups after a nominal final response. The
   * implementation must resolve undefined once the run can safely finish.
   */
  waitForFollowUp(signal: AbortSignal): Promise<readonly AgentMessage[] | undefined>;
}

export type AgentBoundaryPhase = "provider_ready" | "tool_in_flight";

export interface AgentTurnHookContext {
  readonly runId: string;
  readonly projectId: string;
  readonly step: number;
  readonly signal: AbortSignal;
}

export interface AgentTurnHooks {
  beforeProvider?(
    context: AgentTurnHookContext & { readonly messages: readonly AgentMessage[] },
  ): void | Promise<void>;
  afterProvider?(
    context: AgentTurnHookContext & { readonly response: ProviderResponse },
  ): void | Promise<void>;
  beforeTool?(
    context: AgentTurnHookContext & { readonly call: ProviderToolCall },
  ): void | Promise<void>;
  afterTool?(
    context: AgentTurnHookContext & {
      readonly call: ProviderToolCall;
      readonly result: ToolResult<unknown>;
    },
  ): void | Promise<void>;
  onBoundary?(
    context: AgentTurnHookContext & {
      readonly phase: AgentBoundaryPhase;
      readonly messages: readonly AgentMessage[];
    },
  ): void | Promise<void>;
}

interface AgentEventBase {
  readonly sequence: number;
  readonly at: number;
  readonly runId: string;
  readonly projectId: string;
}

export type AgentEvent =
  | (AgentEventBase & {
      readonly type: "context.compacted";
      readonly beforeTokens: number;
      readonly afterTokens: number;
      readonly omittedMessages: number;
    })
  | (AgentEventBase & {
      readonly type: "run.boundary";
      readonly phase: AgentBoundaryPhase;
      readonly nextStep: number;
      readonly messages?: readonly AgentMessage[];
    })
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
  readonly resume?: {
    readonly messages: readonly AgentMessage[];
    readonly nextStep: number;
  };
  readonly control?: AgentRunControl;
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
