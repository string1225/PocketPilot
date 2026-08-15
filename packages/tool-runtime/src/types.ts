export type JsonPrimitive = string | number | boolean | null;

export type JsonValue =
  | JsonPrimitive
  | { readonly [key: string]: JsonValue }
  | readonly JsonValue[];

export interface JsonSchema {
  readonly type?: string | readonly string[];
  readonly title?: string;
  readonly description?: string;
  readonly properties?: Readonly<Record<string, JsonSchema>>;
  readonly required?: readonly string[];
  readonly items?: JsonSchema;
  readonly enum?: readonly JsonValue[];
  readonly additionalProperties?: boolean | JsonSchema;
  readonly [keyword: string]: JsonValue | JsonSchema | Readonly<Record<string, JsonSchema>> | undefined;
}

export type ToolRisk = "read" | "write" | "network" | "remote";

export interface ToolError {
  readonly code: string;
  readonly message: string;
  readonly retryable?: boolean;
  readonly details?: JsonValue;
}

export type ToolResult<T = JsonValue> =
  | {
      readonly success: true;
      readonly data: T;
      readonly metadata?: Readonly<Record<string, JsonValue>>;
    }
  | {
      readonly success: false;
      readonly error: ToolError;
    };

export interface ToolContext {
  readonly runId: string;
  readonly projectId: string;
  readonly callId: string;
  readonly signal: AbortSignal;
  readonly metadata?: Readonly<Record<string, JsonValue>>;
}

export interface AgentTool<TInput = unknown, TOutput = unknown> {
  readonly name: string;
  readonly description: string;
  readonly inputSchema: JsonSchema;
  readonly risk: ToolRisk;
  execute(input: TInput, context: ToolContext): Promise<ToolResult<TOutput>>;
}

export interface ToolDefinition {
  readonly name: string;
  readonly description: string;
  readonly inputSchema: JsonSchema;
  readonly risk: ToolRisk;
}

export const toolSuccess = <T>(
  data: T,
  metadata?: Readonly<Record<string, JsonValue>>,
): ToolResult<T> =>
  metadata === undefined
    ? { success: true, data }
    : { success: true, data, metadata };

export type ToolFailure = Extract<ToolResult<never>, { readonly success: false }>;

export const toolFailure = (
  code: string,
  message: string,
  options: { readonly retryable?: boolean; readonly details?: JsonValue } = {},
): ToolFailure => ({
  success: false,
  error: {
    code,
    message,
    ...(options.retryable === undefined ? {} : { retryable: options.retryable }),
    ...(options.details === undefined ? {} : { details: options.details })
  }
});
