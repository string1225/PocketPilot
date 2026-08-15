import type {
  AgentMessage,
  ProviderRequest,
  ProviderResponse,
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
  if (content === undefined && toolCalls === undefined) {
    throw new NativeLlmTransportError(
      "LLM_INVALID_RESPONSE",
      "Native LLM response contains neither content nor tool calls.",
    );
  }
  return {
    ...(content === undefined ? {} : { content }),
    ...(toolCalls === undefined ? {} : { toolCalls })
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
      messages: request.messages.map(serializeMessage),
      tools: request.tools.map((tool) => ({
        name: tool.name,
        description: tool.description,
        inputSchema: tool.inputSchema
      }))
    };
    const result = await this.#rpc.execute("llm.complete", payload, {
      runId: request.runId,
      projectId: request.projectId,
      callId: `llm:${request.runId}:${request.step}`,
      signal: request.signal
    });
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
