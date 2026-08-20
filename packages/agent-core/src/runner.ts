import {
  type ToolError,
  type ToolRegistry,
  type ToolResult
} from "@pocketpilot/tool-runtime";

import type {
  AgentEvent,
  AgentMessage,
  AgentProvider,
  AgentRunInput,
  AgentRunResult,
  AgentRunner,
  ProviderTokenUsage,
  ProviderToolCall
} from "./types.js";

export interface DefaultAgentRunnerOptions {
  readonly provider: AgentProvider;
  readonly tools: ToolRegistry;
  readonly systemPrompt?: string;
  readonly now?: () => number;
  readonly onEvent?: (event: AgentEvent) => void;
}

type AgentEventInput<TEvent = AgentEvent> = TEvent extends AgentEvent
  ? Omit<TEvent, "sequence" | "at" | "runId" | "projectId">
  : never;

const isAbortError = (error: unknown): boolean =>
  error instanceof DOMException
    ? error.name === "AbortError"
    : typeof error === "object" &&
      error !== null &&
      "name" in error &&
      error.name === "AbortError";

const errorMessage = (error: unknown): string =>
  error instanceof Error ? error.message : String(error);

const addUsage = (
  aggregate: ProviderTokenUsage | undefined,
  next: ProviderTokenUsage | undefined,
): ProviderTokenUsage | undefined => {
  if (next === undefined) return aggregate;
  const sum = (left: number | undefined, right: number | undefined): number | undefined =>
    left === undefined && right === undefined ? undefined : (left ?? 0) + (right ?? 0);
  const inputTokens = sum(aggregate?.inputTokens, next.inputTokens);
  const outputTokens = sum(aggregate?.outputTokens, next.outputTokens);
  const totalTokens = sum(aggregate?.totalTokens, next.totalTokens);
  return {
    ...(inputTokens === undefined ? {} : { inputTokens }),
    ...(outputTokens === undefined ? {} : { outputTokens }),
    ...(totalTokens === undefined ? {} : { totalTokens })
  };
};

const toToolContent = (result: ToolResult<unknown>): string => {
  try {
    return JSON.stringify(result);
  } catch {
    return result.success
      ? "Tool succeeded with a non-serializable result."
      : `${result.error.code}: ${result.error.message}`;
  }
};

export class DefaultAgentRunner implements AgentRunner {
  readonly #provider: AgentProvider;
  readonly #tools: ToolRegistry;
  readonly #systemPrompt: string | undefined;
  readonly #now: () => number;
  readonly #onEvent: ((event: AgentEvent) => void) | undefined;

  public constructor(options: DefaultAgentRunnerOptions) {
    this.#provider = options.provider;
    this.#tools = options.tools;
    this.#systemPrompt = options.systemPrompt;
    this.#now = options.now ?? Date.now;
    this.#onEvent = options.onEvent;
  }

  public async run(input: AgentRunInput): Promise<AgentRunResult> {
    if (input.runId.trim().length === 0 || input.projectId.trim().length === 0) {
      throw new Error("runId and projectId must not be empty.");
    }
    if (input.task.trim().length === 0) {
      throw new Error("task must not be empty.");
    }

    const signal = input.signal ?? new AbortController().signal;
    const messages: AgentMessage[] = [
      ...(this.#systemPrompt === undefined
        ? []
        : [{ role: "system" as const, content: this.#systemPrompt }]),
      ...(input.messages ?? []),
      { role: "user", content: input.task }
    ];
    const events: AgentEvent[] = [];
    let sequence = 0;

    const emit = (
      event: AgentEventInput,
    ): void => {
      const completeEvent = {
        ...event,
        sequence: ++sequence,
        at: this.#now(),
        runId: input.runId,
        projectId: input.projectId
      } as AgentEvent;
      events.push(completeEvent);
      try {
        this.#onEvent?.(completeEvent);
      } catch {
        // Event consumers are observers and cannot fail an agent run.
      }
    };

    const base = (steps: number) => ({
      runId: input.runId,
      projectId: input.projectId,
      messages,
      events,
      steps
    });

    const cancelled = (steps: number): AgentRunResult => {
      emit({ type: "run.cancelled", steps });
      return { ...base(steps), status: "cancelled" };
    };

    const failed = (
      steps: number,
      error: ToolError,
    ): AgentRunResult => {
      emit({ type: "run.failed", error, steps });
      return { ...base(steps), status: "failed", error };
    };

    emit({ type: "run.started", task: input.task });
    if (signal.aborted) {
      return cancelled(0);
    }

    const seenCallIds = new Set<string>();
    let runUsage: ProviderTokenUsage | undefined;
    for (let step = 1; ; step += 1) {
      if (signal.aborted) {
        return cancelled(step - 1);
      }

      let response;
      const messageId = `${input.runId}:assistant:${step}`;
      let streamedContent = "";
      const finalizePartialStream = (status: "failed" | "cancelled"): void => {
        if (streamedContent.length === 0) return;
        emit({
          type: "assistant.message",
          messageId,
          content: streamedContent,
          status
        });
      };
      try {
        response = await this.#provider.complete({
          runId: input.runId,
          projectId: input.projectId,
          step,
          messages: [...messages],
          tools: this.#tools.list(),
          signal,
          onStreamEvent: (streamEvent) => {
            if (signal.aborted) return;
            const delta = streamEvent.contentDelta;
            if (delta === undefined || delta.length === 0) return;
            streamedContent += delta;
            emit({
              type: "assistant.delta",
              messageId,
              delta,
              content: streamedContent
            });
          }
        });
      } catch (error) {
        if (signal.aborted || isAbortError(error)) {
          finalizePartialStream("cancelled");
          return cancelled(step);
        }
        finalizePartialStream("failed");
        return failed(step, {
          code: "PROVIDER_FAILED",
          message: `${this.#provider.name}: ${errorMessage(error)}`,
          retryable: true
        });
      }

      if (signal.aborted) {
        finalizePartialStream("cancelled");
        return cancelled(step);
      }

      const toolCalls = response.toolCalls ?? [];
      const content = response.content ?? "";
      runUsage = addUsage(runUsage, response.usage);
      if (content.length > 0) {
        const messageUsage = toolCalls.length === 0 ? runUsage : response.usage;
        emit({
          type: "assistant.message",
          messageId,
          content,
          status: "completed",
          ...(messageUsage === undefined ? {} : { usage: messageUsage })
        });
      }

      if (toolCalls.length === 0) {
        if (response.content === undefined) {
          return failed(step, {
            code: "INVALID_PROVIDER_RESPONSE",
            message: "Provider returned neither content nor tool calls."
          });
        }
        messages.push({ role: "assistant", content });
        emit({
          type: "run.completed",
          output: content,
          steps: step,
          ...(runUsage === undefined ? {} : { usage: runUsage })
        });
        return { ...base(step), status: "completed", output: content };
      }

      messages.push({
        role: "assistant",
        content,
        toolCalls: [...toolCalls]
      });

      for (const call of toolCalls) {
        if (seenCallIds.has(call.id)) {
          return failed(step, {
            code: "DUPLICATE_TOOL_CALL_ID",
            message: `Provider repeated tool call id: ${call.id}`
          });
        }
        seenCallIds.add(call.id);

        if (signal.aborted) {
          return cancelled(step);
        }

        emit({ type: "tool.started", call });
        const result = await this.#tools.execute(call.name, call.arguments, {
          runId: input.runId,
          projectId: input.projectId,
          callId: call.id,
          signal
        });
        emit({ type: "tool.finished", call, result });
        messages.push({
          role: "tool",
          content: toToolContent(result),
          toolCallId: call.id,
          name: call.name,
          result
        });

        if (!result.success && result.error.code === "CANCELLED") {
          return cancelled(step);
        }
        if (!result.success && result.error.code === "APPROVAL_REQUIRED") {
          emit({
            type: "run.waiting_for_approval",
            call,
            reason: result.error.message
          });
          return {
            ...base(step),
            status: "waiting_for_approval",
            pendingCall: call,
            error: result.error
          };
        }
        if (!result.success && result.error.code === "PERMISSION_DENIED") {
          return failed(step, result.error);
        }
      }
    }
  }
}
