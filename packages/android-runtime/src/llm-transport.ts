import type {
  AgentMessage,
  ProviderRequest,
  ProviderResponse,
  ProviderStreamEvent,
  ProviderTokenUsage,
  ProviderToolCall
} from "@pocketpilot/agent-core";
import type {
  NativeLlmTransport,
  ResolvedOpenAICompatibleConfig
} from "@pocketpilot/providers";

import type { NativeRpcClient } from "./native-rpc.js";

interface NativeLlmMessage {
  readonly role: AgentMessage["role"];
  readonly content: string;
  readonly toolCalls?: readonly ProviderToolCall[];
  readonly toolCallId?: string;
  readonly name?: string;
}

export interface NativeLlmCompleteRequest {
  readonly protocol: ResolvedOpenAICompatibleConfig["protocol"];
  readonly baseUrl: string;
  readonly model: string;
  readonly credentialId: string;
  readonly stream: true;
  readonly messages: readonly NativeLlmMessage[];
  readonly tools: readonly {
    readonly name: string;
    readonly description: string;
    readonly inputSchema: unknown;
  }[];
}

export class NativeLlmTransportError extends Error {
  public readonly code: string;
  public readonly retryable: boolean;

  public constructor(code: string, message: string, retryable = false) {
    super(message);
    this.name = "NativeLlmTransportError";
    this.code = code;
    this.retryable = retryable;
  }
}

const asRecord = (value: unknown): Record<string, unknown> | undefined =>
  typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined;

const serializeMessage = (message: AgentMessage): NativeLlmMessage => {
  switch (message.role) {
    case "assistant":
      return {
        role: message.role,
        content: message.content,
        ...(message.toolCalls === undefined
          ? {}
          : { toolCalls: message.toolCalls.map((call) => ({ ...call })) })
      };
    case "tool":
      return {
        role: message.role,
        content: message.content,
        toolCallId: message.toolCallId,
        name: message.name
      };
    case "system":
    case "user":
      return { role: message.role, content: message.content };
  }
};

const parseToolCall = (value: unknown): ProviderToolCall => {
  const record = asRecord(value);
  if (
    record === undefined ||
    typeof record.id !== "string" ||
    record.id.length === 0 ||
    typeof record.name !== "string" ||
    record.name.length === 0 ||
    !("arguments" in record)
  ) {
    throw new NativeLlmTransportError(
      "LLM_INVALID_RESPONSE",
      "Native LLM response contains an invalid tool call.",
    );
  }
  return { id: record.id, name: record.name, arguments: record.arguments };
};

const parseNonNegativeInteger = (
  value: unknown,
  field: string,
): number | undefined => {
  if (value === undefined) return undefined;
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    throw new NativeLlmTransportError(
      "LLM_INVALID_RESPONSE",
      `Native LLM ${field} must be a non-negative safe integer.`,
    );
  }
  return value;
};

const parseUsage = (value: unknown): ProviderTokenUsage | undefined => {
  if (value === undefined) return undefined;
  const record = asRecord(value);
  if (record === undefined) {
    throw new NativeLlmTransportError(
      "LLM_INVALID_RESPONSE",
      "Native LLM token usage must be an object.",
    );
  }
  const inputTokens = parseNonNegativeInteger(record.inputTokens, "inputTokens");
  const outputTokens = parseNonNegativeInteger(record.outputTokens, "outputTokens");
  const totalTokens = parseNonNegativeInteger(record.totalTokens, "totalTokens");
  if (inputTokens === undefined && outputTokens === undefined && totalTokens === undefined) {
    return undefined;
  }
  return {
    ...(inputTokens === undefined ? {} : { inputTokens }),
    ...(outputTokens === undefined ? {} : { outputTokens }),
    ...(totalTokens === undefined ? {} : { totalTokens })
  };
};

const parseStreamEvent = (value: unknown): ProviderStreamEvent => {
  const record = asRecord(value);
  if (record === undefined) {
    throw new NativeLlmTransportError(
      "LLM_INVALID_RESPONSE",
      "Native LLM stream event must be an object.",
    );
  }
  const contentDelta = record.contentDelta;
  if (contentDelta !== undefined && typeof contentDelta !== "string") {
    throw new NativeLlmTransportError(
      "LLM_INVALID_RESPONSE",
      "Native LLM contentDelta must be a string.",
    );
  }
  const usage = parseUsage(record.usage);
  if (contentDelta === undefined && usage === undefined) {
    throw new NativeLlmTransportError(
      "LLM_INVALID_RESPONSE",
      "Native LLM stream event contains neither text nor usage.",
    );
  }
  return {
    ...(contentDelta === undefined ? {} : { contentDelta }),
    ...(usage === undefined ? {} : { usage })
  };
};

const parseProviderResponse = (value: unknown): ProviderResponse => {
  const record = asRecord(value);
  if (record === undefined) {
    throw new NativeLlmTransportError(
      "LLM_INVALID_RESPONSE",
      "Native LLM response must be an object.",
    );
  }
  const content = record.content;
  if (content !== undefined && typeof content !== "string") {
    throw new NativeLlmTransportError(
      "LLM_INVALID_RESPONSE",
      "Native LLM response content must be a string.",
    );
  }
  const rawToolCalls = record.toolCalls;
  if (rawToolCalls !== undefined && !Array.isArray(rawToolCalls)) {
    throw new NativeLlmTransportError(
      "LLM_INVALID_RESPONSE",
      "Native LLM response toolCalls must be an array.",
    );
  }
  const toolCalls = rawToolCalls?.map(parseToolCall);
  const usage = parseUsage(record.usage);
  if (content === undefined && toolCalls === undefined) {
    throw new NativeLlmTransportError(
      "LLM_INVALID_RESPONSE",
      "Native LLM response contains neither content nor tool calls.",
    );
  }
  return {
    ...(content === undefined ? {} : { content }),
    ...(toolCalls === undefined ? {} : { toolCalls }),
    ...(usage === undefined ? {} : { usage })
  };
};

export class NativeRpcLlmTransport implements NativeLlmTransport {
  readonly #rpc: NativeRpcClient;

  public constructor(rpc: NativeRpcClient) {
    this.#rpc = rpc;
  }

  public async complete(
    config: ResolvedOpenAICompatibleConfig,
    request: ProviderRequest,
  ): Promise<ProviderResponse> {
    const payload: NativeLlmCompleteRequest = {
      protocol: config.protocol,
      baseUrl: config.baseUrl,
      model: config.model,
      credentialId: config.credentialId,
      stream: true,
      messages: request.messages.map(serializeMessage),
      tools: request.tools.map((tool) => ({
        name: tool.name,
        description: tool.description,
        inputSchema: tool.inputSchema
      }))
    };
    let progressError: NativeLlmTransportError | undefined;
    const result = await this.#rpc.execute(
      "llm.complete",
      payload,
      {
        runId: request.runId,
        projectId: request.projectId,
        callId: `llm:${request.runId}:${request.step}`,
        signal: request.signal
      },
      {
        onProgress: (value) => {
          if (progressError !== undefined || request.signal.aborted) return;
          try {
            request.onStreamEvent?.(parseStreamEvent(value));
          } catch (error) {
            progressError = error instanceof NativeLlmTransportError
              ? error
              : new NativeLlmTransportError(
                  "LLM_STREAM_CONSUMER_FAILED",
                  "LLM stream event could not be processed.",
                );
          }
        }
      },
    );
    if (progressError !== undefined) throw progressError;
    if (!result.success) {
      throw new NativeLlmTransportError(
        result.error.code,
        result.error.message,
        result.error.retryable ?? false,
      );
    }
    return parseProviderResponse(result.data);
  }
}
