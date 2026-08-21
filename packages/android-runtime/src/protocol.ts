import type { AgentEvent, AgentMessage } from "@pocketpilot/agent-core";
import type {
  OpenAICompatibleConfig,
  OpenAICompatibleProtocol
} from "@pocketpilot/providers";
import type { JsonSchema, ToolResult } from "@pocketpilot/tool-runtime";

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

export interface ToolProgressEnvelope extends EnvelopeBase {
  readonly type: "tool.progress";
  readonly payload: unknown;
}

export type JavaScriptToNativeEnvelope = ToolRequestEnvelope | RuntimeEventEnvelope;
export type NativeToJavaScriptEnvelope =
  | ToolResultEnvelope
  | ToolErrorEnvelope
  | ToolProgressEnvelope;

export interface RuntimeStartRequest {
  readonly runId: string;
  readonly projectId: string;
  readonly task: string;
  readonly provider?: RuntimeProviderConfig;
  readonly systemPrompt?: string;
  readonly messages?: readonly AgentMessage[];
  readonly toolsEnabled?: boolean;
  readonly plugins?: readonly RuntimePluginPackage[];
  readonly resume?: {
    readonly messages: readonly AgentMessage[];
    readonly nextStep: number;
  };
  readonly runtime?: RuntimeExecutionConfig;
}

export interface RuntimeExecutionConfig {
  readonly maxConcurrentTools: number;
  /** Zero means unlimited. */
  readonly maxTurns: number;
}

export interface RuntimePluginTool {
  readonly name: string;
  readonly description: string;
  readonly risk: "read";
  readonly inputSchema: JsonSchema;
}

export interface RuntimePluginPackage {
  readonly id: string;
  readonly name: string;
  readonly version: string;
  readonly description: string;
  readonly sourceSha256: string;
  readonly source: string;
  readonly tools: readonly RuntimePluginTool[];
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
  steer(runId: string, content: string): boolean;
  followUp(runId: string, content: string): boolean;
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

const parseResume = (
  value: unknown,
): RuntimeStartRequest["resume"] => {
  if (value === undefined) return undefined;
  const record = asRecord(value);
  if (
    record === undefined ||
    record.phase !== "provider_ready" ||
    typeof record.nextStep !== "number" ||
    !Number.isSafeInteger(record.nextStep) ||
    record.nextStep < 1 ||
    !Array.isArray(record.messages) ||
    record.messages.length < 1 ||
    record.messages.length > 1_024
  ) {
    throw new Error("resume must contain a provider-ready message boundary.");
  }
  const messages = record.messages.map((value, index): AgentMessage => {
    const message = asRecord(value);
    if (message === undefined || typeof message.content !== "string") {
      throw new Error(`resume.messages[${index}] is invalid.`);
    }
    if (message.role === "system" || message.role === "user") {
      return { role: message.role, content: message.content };
    }
    if (message.role === "assistant") {
      const rawCalls = message.toolCalls;
      if (rawCalls !== undefined && !Array.isArray(rawCalls)) {
        throw new Error(`resume.messages[${index}].toolCalls is invalid.`);
      }
      const toolCalls = rawCalls?.map((rawCall) => {
        const call = asRecord(rawCall);
        if (
          call === undefined ||
          typeof call.id !== "string" ||
          typeof call.name !== "string" ||
          !("arguments" in call)
        ) throw new Error(`resume.messages[${index}] has an invalid tool call.`);
        return { id: call.id, name: call.name, arguments: call.arguments };
      });
      return {
        role: "assistant",
        content: message.content,
        ...(toolCalls === undefined ? {} : { toolCalls })
      };
    }
    if (
      message.role === "tool" &&
      typeof message.toolCallId === "string" &&
      typeof message.name === "string"
    ) {
      const result = asRecord(message.result);
      if (result === undefined || typeof result.success !== "boolean") {
        throw new Error(`resume.messages[${index}].result is invalid.`);
      }
      return {
        role: "tool",
        content: message.content,
        toolCallId: message.toolCallId,
        name: message.name,
        result: message.result as ToolResult<unknown>
      };
    }
    throw new Error(`resume.messages[${index}] has an unsupported role.`);
  });
  return { messages, nextStep: record.nextStep };
};

const utf8Bytes = (value: string): number => new TextEncoder().encode(value).byteLength;
const pluginIdPattern = /^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+$/u;
const pluginToolPattern = /^[a-z][a-z0-9_]{0,63}$/u;
const pluginVersionPattern = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/u;
const pluginHashPattern = /^[0-9a-f]{64}$/u;
const pluginSchemaKeys = new Set([
  "type", "title", "description", "properties", "required", "items", "enum",
  "additionalProperties", "minLength", "maxLength", "minimum", "maximum", "default"
]);
const pluginSchemaTypes = new Set([
  "object", "array", "string", "number", "integer", "boolean", "null"
]);
const owns = (value: object, key: PropertyKey): boolean =>
  Object.prototype.hasOwnProperty.call(value, key);
const isValidPluginVersion = (value: string): boolean => {
  if (!pluginVersionPattern.test(value)) return false;
  const withoutBuild = value.split("+", 1)[0] ?? value;
  const separator = withoutBuild.indexOf("-");
  if (separator < 0) return true;
  return withoutBuild.slice(separator + 1).split(".").every((part) =>
    !(/^\d+$/u.test(part) && part.length > 1 && part.startsWith("0"))
  );
};

const optionalBoundedString = (
  value: unknown,
  path: string,
  maximum: number,
): void => {
  if (value !== undefined && (typeof value !== "string" || value.length > maximum)) {
    throw new Error(`${path} must be a string with at most ${maximum} characters.`);
  }
};

const optionalNonNegativeInteger = (value: unknown, path: string): number | undefined => {
  if (value === undefined) return undefined;
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    throw new Error(`${path} must be a non-negative integer.`);
  }
  return value;
};

const optionalFiniteNumber = (value: unknown, path: string): number | undefined => {
  if (value === undefined) return undefined;
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw new Error(`${path} must be a finite number.`);
  }
  return value;
};

const validateRuntimePluginSchema = (
  schema: Record<string, unknown>,
  path: string,
  depth: number,
  root: boolean,
): void => {
  if (depth > 8) throw new Error(`${path} exceeds the maximum schema depth.`);
  const unsupported = Object.keys(schema).find((key) => !pluginSchemaKeys.has(key));
  if (unsupported !== undefined) throw new Error(`${path} contains unsupported field: ${unsupported}`);
  const type = schema.type;
  if (typeof type !== "string" || !pluginSchemaTypes.has(type)) {
    throw new Error(`${path}.type is unsupported.`);
  }
  if (root && type !== "object") throw new Error(`${path}.type must be object.`);
  optionalBoundedString(schema.title, `${path}.title`, 200);
  optionalBoundedString(schema.description, `${path}.description`, 500);
  const minLength = optionalNonNegativeInteger(schema.minLength, `${path}.minLength`);
  const maxLength = optionalNonNegativeInteger(schema.maxLength, `${path}.maxLength`);
  if (minLength !== undefined && maxLength !== undefined && minLength > maxLength) {
    throw new Error(`${path}.minLength exceeds maxLength.`);
  }
  if ((minLength ?? 0) > 1_048_576 || (maxLength ?? 0) > 1_048_576) {
    throw new Error(`${path} string length limit is too large.`);
  }
  const minimum = optionalFiniteNumber(schema.minimum, `${path}.minimum`);
  const maximum = optionalFiniteNumber(schema.maximum, `${path}.maximum`);
  if (minimum !== undefined && maximum !== undefined && minimum > maximum) {
    throw new Error(`${path}.minimum exceeds maximum.`);
  }
  if (schema.enum !== undefined && (!Array.isArray(schema.enum) || schema.enum.length < 1 || schema.enum.length > 100)) {
    throw new Error(`${path}.enum must contain 1 to 100 JSON values.`);
  }
  if (type !== "string" && (schema.minLength !== undefined || schema.maxLength !== undefined)) {
    throw new Error(`${path} uses string-only schema keywords.`);
  }
  if (type !== "number" && type !== "integer" && (schema.minimum !== undefined || schema.maximum !== undefined)) {
    throw new Error(`${path} uses numeric-only schema keywords.`);
  }
  if (type === "object") {
    if (schema.additionalProperties !== false) {
      throw new Error(`${path} must set additionalProperties to false.`);
    }
    const properties = schema.properties === undefined ? {} : asRecord(schema.properties);
    if (properties === undefined || Object.keys(properties).length > 64) {
      throw new Error(`${path}.properties must be an object with at most 64 fields.`);
    }
    for (const [propertyName, propertySchema] of Object.entries(properties)) {
      if (propertyName.length < 1 || propertyName.length > 80) {
        throw new Error(`${path} has an invalid property name.`);
      }
      const child = asRecord(propertySchema);
      if (child === undefined) throw new Error(`${path}.properties.${propertyName} must be an object.`);
      validateRuntimePluginSchema(child, `${path}.properties.${propertyName}`, depth + 1, false);
    }
    if (schema.required !== undefined) {
      if (!Array.isArray(schema.required)) throw new Error(`${path}.required must be an array.`);
      const seen = new Set<string>();
      for (const field of schema.required) {
        if (typeof field !== "string" || !owns(properties, field) || !seen.add(field)) {
          throw new Error(`${path}.required must contain unique declared property names.`);
        }
      }
    }
  } else if (
    schema.properties !== undefined ||
    schema.required !== undefined ||
    schema.additionalProperties !== undefined
  ) {
    throw new Error(`${path} uses object-only schema keywords.`);
  }
  if (type === "array") {
    const items = asRecord(schema.items);
    if (items === undefined) throw new Error(`${path}.items must be an object.`);
    validateRuntimePluginSchema(items, `${path}.items`, depth + 1, false);
  } else if (schema.items !== undefined) {
    throw new Error(`${path}.items is only valid for arrays.`);
  }
};

const parsePlugins = (value: unknown): readonly RuntimePluginPackage[] | undefined => {
  if (value === undefined) return undefined;
  if (!Array.isArray(value) || value.length > 32) {
    throw new Error("plugins must be an array with at most 32 items.");
  }
  const seenPluginIds = new Set<string>();
  const seenToolNames = new Set<string>();
  let totalSourceBytes = 0;
  let totalToolCount = 0;
  return value.map((item, pluginIndex) => {
    const plugin = asRecord(item);
    const path = `plugins[${pluginIndex}]`;
    if (plugin === undefined) throw new Error(`${path} must be an object.`);
    const allowedPluginKeys = new Set([
      "id", "name", "version", "description", "sourceSha256", "source", "tools"
    ]);
    const unsupportedPluginKey = Object.keys(plugin).find((key) => !allowedPluginKeys.has(key));
    if (unsupportedPluginKey !== undefined) {
      throw new Error(`${path} contains an unsupported field: ${unsupportedPluginKey}`);
    }
    const { id, name, version, description, sourceSha256, source, tools } = plugin;
    if (typeof id !== "string" || id.length > 80 || !pluginIdPattern.test(id)) {
      throw new Error(`${path}.id is not a valid plugin identifier.`);
    }
    if (!seenPluginIds.add(id)) throw new Error(`Duplicate plugin id: ${id}`);
    if (typeof name !== "string" || name.length < 1 || name.length > 80 || name !== name.trim()) {
      throw new Error(`${path}.name is invalid.`);
    }
    if (typeof version !== "string" || !isValidPluginVersion(version)) {
      throw new Error(`${path}.version must be valid SemVer.`);
    }
    if (
      typeof description !== "string" ||
      description.length > 500 ||
      description !== description.trim()
    ) {
      throw new Error(`${path}.description is invalid.`);
    }
    if (typeof sourceSha256 !== "string" || !pluginHashPattern.test(sourceSha256)) {
      throw new Error(`${path}.sourceSha256 must be a lowercase SHA-256 digest.`);
    }
    if (
      typeof source !== "string" ||
      source.length === 0 ||
      source.includes("\u0000") ||
      utf8Bytes(source) > 120 * 1_024
    ) {
      throw new Error(`${path}.source must contain at most 122880 UTF-8 bytes.`);
    }
    totalSourceBytes += utf8Bytes(source);
    if (totalSourceBytes > 512 * 1_024) {
      throw new Error("Enabled plugin source exceeds the 524288-byte run limit.");
    }
    if (!Array.isArray(tools) || tools.length < 1 || tools.length > 16) {
      throw new Error(`${path}.tools must contain 1 to 16 tools.`);
    }
    totalToolCount += tools.length;
    if (totalToolCount > 64) throw new Error("Enabled plugins can expose at most 64 tools.");
    const parsedTools = tools.map((toolValue, toolIndex): RuntimePluginTool => {
      const tool = asRecord(toolValue);
      const toolPath = `${path}.tools[${toolIndex}]`;
      if (tool === undefined) throw new Error(`${toolPath} must be an object.`);
      const allowedToolKeys = new Set(["name", "description", "risk", "inputSchema"]);
      const unsupportedToolKey = Object.keys(tool).find((key) => !allowedToolKeys.has(key));
      if (unsupportedToolKey !== undefined) {
        throw new Error(`${toolPath} contains an unsupported field: ${unsupportedToolKey}`);
      }
      if (typeof tool.name !== "string" || !pluginToolPattern.test(tool.name)) {
        throw new Error(`${toolPath}.name is invalid.`);
      }
      const qualifiedName = `plugin.${id}.${tool.name}`;
      if (!seenToolNames.add(qualifiedName)) throw new Error(`Duplicate plugin tool: ${qualifiedName}`);
      if (
        typeof tool.description !== "string" ||
        tool.description.length < 1 ||
        tool.description.length > 500 ||
        tool.description !== tool.description.trim()
      ) {
        throw new Error(`${toolPath}.description is invalid.`);
      }
      if (tool.risk !== "read") {
        throw new Error(`${toolPath}.risk must be read; MVP plugins are pure computation only.`);
      }
      const inputSchema = asRecord(tool.inputSchema);
      if (
        inputSchema === undefined ||
        utf8Bytes(JSON.stringify(inputSchema)) > 32 * 1_024
      ) {
        throw new Error(`${toolPath}.inputSchema must be a bounded strict object schema.`);
      }
      validateRuntimePluginSchema(inputSchema, `${toolPath}.inputSchema`, 0, true);
      return {
        name: tool.name,
        description: tool.description,
        risk: "read",
        inputSchema: inputSchema as JsonSchema
      };
    });
    return {
      id,
      name,
      version,
      description,
      sourceSha256,
      source,
      tools: parsedTools
    };
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
    provider,
    systemPrompt,
    messages,
    toolsEnabled,
    plugins,
    resume,
    runtime
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
  const parsedPlugins = parsePlugins(plugins);
  const parsedResume = parseResume(resume);
  const parsedRuntime = (() => {
    if (runtime === undefined) return undefined;
    const value = asRecord(runtime);
    if (
      value === undefined ||
      Object.keys(value).some((key) => key !== "maxConcurrentTools" && key !== "maxTurns") ||
      typeof value.maxConcurrentTools !== "number" ||
      !Number.isSafeInteger(value.maxConcurrentTools) ||
      value.maxConcurrentTools < 1 ||
      value.maxConcurrentTools > 32 ||
      typeof value.maxTurns !== "number" ||
      !Number.isSafeInteger(value.maxTurns) ||
      value.maxTurns < 0 ||
      value.maxTurns > 100_000
    ) {
      throw new Error("runtime must contain valid maxConcurrentTools and maxTurns values.");
    }
    return {
      maxConcurrentTools: value.maxConcurrentTools,
      maxTurns: value.maxTurns
    };
  })();
  return {
    runId,
    projectId,
    task,
    ...(parsedProvider === undefined ? {} : { provider: parsedProvider }),
    ...(systemPrompt === undefined ? {} : { systemPrompt }),
    ...(parsedMessages === undefined ? {} : { messages: parsedMessages }),
    ...(toolsEnabled === undefined ? {} : { toolsEnabled }),
    ...(parsedPlugins === undefined ? {} : { plugins: parsedPlugins }),
    ...(parsedResume === undefined ? {} : { resume: parsedResume }),
    ...(parsedRuntime === undefined ? {} : { runtime: parsedRuntime })
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
    (
      record.type !== "tool.result" &&
      record.type !== "tool.error" &&
      record.type !== "tool.progress"
    ) ||
    !("payload" in record)
  ) {
    throw new Error("Native bridge envelope has an invalid shape.");
  }
  return record as unknown as NativeToJavaScriptEnvelope;
};
