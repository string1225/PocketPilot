import {
  type ToolError,
  type ToolRegistry,
  type ToolResult
} from "@pocketpilot/tool-runtime";

import { compactContext } from "./context.js";
import type {
  AgentBoundaryPhase,
  AgentEvent,
  AgentMessage,
  AgentProvider,
  AgentRunInput,
  AgentRunResult,
  AgentRunner,
  AgentTurnHookContext,
  AgentTurnHooks,
  ProviderResponse,
  ProviderTokenUsage,
  ProviderToolCall
} from "./types.js";

export interface DefaultAgentRunnerOptions {
  readonly provider: AgentProvider;
  readonly tools: ToolRegistry;
  readonly systemPrompt?: string;
  readonly now?: () => number;
  readonly onEvent?: (event: AgentEvent) => void;
  readonly hooks?: readonly AgentTurnHooks[];
  readonly reserveOutputTokens?: number;
  readonly summaryTokens?: number;
  /** Maximum simultaneously executing parallel-mode tools for this Run. */
  readonly maxConcurrentTools?: number;
  /** Zero or undefined means unlimited provider turns. */
  readonly maxTurns?: number;
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
  const cachedInputTokens = sum(aggregate?.cachedInputTokens, next.cachedInputTokens);
  return {
    ...(inputTokens === undefined ? {} : { inputTokens }),
    ...(outputTokens === undefined ? {} : { outputTokens }),
    ...(totalTokens === undefined ? {} : { totalTokens }),
    ...(cachedInputTokens === undefined ? {} : { cachedInputTokens })
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
  readonly #hooks: readonly AgentTurnHooks[];
  readonly #reserveOutputTokens: number;
  readonly #summaryTokens: number;
  readonly #maxConcurrentTools: number;
  readonly #maxTurns: number;

  public constructor(options: DefaultAgentRunnerOptions) {
    this.#provider = options.provider;
    this.#tools = options.tools;
    this.#systemPrompt = options.systemPrompt;
    this.#now = options.now ?? Date.now;
    this.#onEvent = options.onEvent;
    this.#hooks = options.hooks ?? [];
    this.#reserveOutputTokens = options.reserveOutputTokens ?? 16_384;
    this.#summaryTokens = options.summaryTokens ?? 4_096;
    this.#maxConcurrentTools = options.maxConcurrentTools ?? 4;
    this.#maxTurns = options.maxTurns ?? 0;
    if (!Number.isSafeInteger(this.#maxConcurrentTools) || this.#maxConcurrentTools < 1 || this.#maxConcurrentTools > 32) {
      throw new Error("maxConcurrentTools must be an integer between 1 and 32.");
    }
    if (!Number.isSafeInteger(this.#maxTurns) || this.#maxTurns < 0 || this.#maxTurns > 100_000) {
      throw new Error("maxTurns must be zero (unlimited) or a positive integer no greater than 100000.");
    }
  }

  public async run(input: AgentRunInput): Promise<AgentRunResult> {
    if (input.runId.trim().length === 0 || input.projectId.trim().length === 0) {
      throw new Error("runId and projectId must not be empty.");
    }
    if (input.task.trim().length === 0) throw new Error("task must not be empty.");
    if (input.resume !== undefined && input.resume.nextStep < 1) {
      throw new Error("resume.nextStep must be positive.");
    }

    const signal = input.signal ?? new AbortController().signal;
    const messages: AgentMessage[] = input.resume === undefined
      ? [
          ...(this.#systemPrompt === undefined
            ? []
            : [{ role: "system" as const, content: this.#systemPrompt }]),
          ...(input.messages ?? []),
          { role: "user", content: input.task }
        ]
      : [...input.resume.messages];
    const events: AgentEvent[] = [];
    let sequence = 0;

    const emit = (event: AgentEventInput): void => {
      const completeEvent = {
        ...event,
        sequence: ++sequence,
        at: this.#now(),
        runId: input.runId,
        projectId: input.projectId
      } as AgentEvent;
      events.push(completeEvent);
      try { this.#onEvent?.(completeEvent); } catch { /* observer only */ }
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
    const failed = (steps: number, error: ToolError): AgentRunResult => {
      emit({ type: "run.failed", error, steps });
      return { ...base(steps), status: "failed", error };
    };
    const hookContext = (step: number): AgentTurnHookContext => ({
      runId: input.runId,
      projectId: input.projectId,
      step,
      signal
    });
    const runBoundary = async (
      step: number,
      phase: AgentBoundaryPhase,
      includeMessages: boolean,
    ): Promise<void> => {
      const snapshot = [...messages];
      for (const hook of this.#hooks) {
        await hook.onBoundary?.({ ...hookContext(step), phase, messages: snapshot });
      }
      emit({
        type: "run.boundary",
        phase,
        nextStep: step,
        ...(includeMessages ? { messages: snapshot } : {})
      });
    };
    const hookFailed = (step: number, error: unknown): AgentRunResult => failed(step, {
      code: "AGENT_HOOK_FAILED",
      message: errorMessage(error),
      retryable: false
    });

    emit({ type: "run.started", task: input.task });
    if (signal.aborted) return cancelled(0);

    const seenCallIds = new Set<string>();
    let runUsage: ProviderTokenUsage | undefined;
    let step = input.resume?.nextStep ?? 1;
    for (;; step += 1) {
      if (this.#maxTurns > 0 && step > this.#maxTurns) {
        return failed(step - 1, {
          code: "MAX_TURNS_REACHED",
          message: `Agent reached the configured maximum of ${this.#maxTurns} turns.`,
          retryable: false
        });
      }
      if (signal.aborted) return cancelled(step - 1);
      const steering = input.control?.drainSteering() ?? [];
      if (steering.length > 0) messages.push(...steering);

      const compacted = compactContext(messages, {
        contextWindowTokens: this.#provider.contextWindowTokens ?? 128_000,
        reserveOutputTokens: this.#reserveOutputTokens,
        summaryTokens: this.#summaryTokens
      });
      if (compacted.compacted) {
        messages.splice(0, messages.length, ...compacted.messages);
        emit({
          type: "context.compacted",
          beforeTokens: compacted.beforeTokens,
          afterTokens: compacted.afterTokens,
          omittedMessages: compacted.omittedMessages
        });
      }

      try {
        await runBoundary(step, "provider_ready", true);
        for (const hook of this.#hooks) {
          await hook.beforeProvider?.({ ...hookContext(step), messages: [...messages] });
        }
      } catch (error) {
        return hookFailed(step, error);
      }

      let response: ProviderResponse;
      const messageId = `${input.runId}:assistant:${step}`;
      let streamedContent = "";
      const finalizePartialStream = (status: "failed" | "cancelled"): void => {
        if (streamedContent.length > 0) {
          emit({ type: "assistant.message", messageId, content: streamedContent, status });
        }
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
            emit({ type: "assistant.delta", messageId, delta, content: streamedContent });
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
      try {
        for (const hook of this.#hooks) {
          await hook.afterProvider?.({ ...hookContext(step), response });
        }
      } catch (error) {
        finalizePartialStream("failed");
        return hookFailed(step, error);
      }

      if (signal.aborted) {
        finalizePartialStream("cancelled");
        return cancelled(step);
      }
      if (response.finishReason === "length") {
        finalizePartialStream("failed");
        return failed(step, {
          code: "MODEL_OUTPUT_TRUNCATED",
          message: "The model output reached its token limit; incomplete tool calls were not executed.",
          retryable: true
        });
      }
      if (response.finishReason === "content_filter") {
        finalizePartialStream("failed");
        return failed(step, {
          code: "MODEL_OUTPUT_FILTERED",
          message: "The model provider filtered this output; tool calls were not executed."
        });
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
        const followUp = await input.control?.waitForFollowUp(signal);
        if (followUp !== undefined && followUp.length > 0) {
          messages.push(...followUp);
          continue;
        }
        emit({
          type: "run.completed",
          output: content,
          steps: step,
          ...(runUsage === undefined ? {} : { usage: runUsage })
        });
        return { ...base(step), status: "completed", output: content };
      }
      if (response.finishReason !== undefined && response.finishReason !== "tool_calls" && response.finishReason !== "unknown") {
        return failed(step, {
          code: "INVALID_PROVIDER_FINISH_REASON",
          message: `Provider returned tool calls with finish reason ${response.finishReason}.`
        });
      }

      for (const call of toolCalls) {
        if (seenCallIds.has(call.id)) {
          return failed(step, {
            code: "DUPLICATE_TOOL_CALL_ID",
            message: `Provider repeated tool call id: ${call.id}`
          });
        }
        seenCallIds.add(call.id);
      }
      messages.push({ role: "assistant", content, toolCalls: [...toolCalls] });
      try { await runBoundary(step, "tool_in_flight", false); } catch (error) {
        return hookFailed(step, error);
      }

      for (let offset = 0; offset < toolCalls.length;) {
        if (signal.aborted) return cancelled(step);
        const first = toolCalls[offset]!;
        const parallel = this.#tools.definition(first.name)?.executionMode === "parallel";
        let end = offset + 1;
        if (parallel) {
          while (
            end < toolCalls.length &&
            this.#tools.definition(toolCalls[end]!.name)?.executionMode === "parallel"
          ) end += 1;
        }
        const group = toolCalls.slice(offset, end);
        try {
          for (const call of group) {
            for (const hook of this.#hooks) {
              await hook.beforeTool?.({ ...hookContext(step), call });
            }
            emit({ type: "tool.started", call });
          }
        } catch (error) {
          return hookFailed(step, error);
        }
        const results: ToolResult<unknown>[] = [];
        for (let batchOffset = 0; batchOffset < group.length; batchOffset += this.#maxConcurrentTools) {
          if (signal.aborted) return cancelled(step);
          const batch = group.slice(batchOffset, batchOffset + this.#maxConcurrentTools);
          results.push(...await Promise.all(batch.map((call) => this.#tools.execute(
            call.name,
            call.arguments,
            { runId: input.runId, projectId: input.projectId, callId: call.id, signal },
          ))));
        }
        for (let index = 0; index < group.length; index += 1) {
          const call = group[index]!;
          const result = results[index]!;
          try {
            for (const hook of this.#hooks) {
              await hook.afterTool?.({ ...hookContext(step), call, result });
            }
          } catch (error) {
            return hookFailed(step, error);
          }
          emit({ type: "tool.finished", call, result });
          messages.push({
            role: "tool",
            content: toToolContent(result),
            toolCallId: call.id,
            name: call.name,
            result
          });
          if (!result.success && result.error.code === "CANCELLED") return cancelled(step);
          if (!result.success && result.error.code === "APPROVAL_REQUIRED") {
            emit({ type: "run.waiting_for_approval", call, reason: result.error.message });
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
        const betweenTools = input.control?.drainSteering() ?? [];
        if (betweenTools.length > 0) messages.push(...betweenTools);
        offset = end;
      }
    }
  }
}
