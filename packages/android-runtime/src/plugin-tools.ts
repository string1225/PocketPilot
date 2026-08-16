import {
  toolFailure,
  toolSuccess,
  type AgentTool,
  type JsonSchema,
  type JsonValue,
  type ToolResult
} from "@pocketpilot/tool-runtime";

import type { RuntimePluginPackage } from "./protocol.js";
import { WorkerSandbox } from "./sandbox.js";

const MAX_VALIDATION_NODES = 10_000;
const PLUGIN_TIMEOUT_MILLIS = 5_000;
const owns = (value: object, key: PropertyKey): boolean =>
  Object.prototype.hasOwnProperty.call(value, key);

interface ValidationBudget {
  remaining: number;
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const sameJsonValue = (
  left: unknown,
  right: unknown,
  budget: ValidationBudget,
): boolean => {
  budget.remaining -= 1;
  if (budget.remaining < 0) return false;
  if (left === right) return true;
  if (Array.isArray(left) || Array.isArray(right)) {
    if (!Array.isArray(left) || !Array.isArray(right) || left.length !== right.length) return false;
    return left.every((item, index) => sameJsonValue(item, right[index], budget));
  }
  if (!isRecord(left) || !isRecord(right)) return false;
  const leftKeys = Object.keys(left);
  const rightKeys = Object.keys(right);
  if (leftKeys.length !== rightKeys.length) return false;
  return leftKeys.every((key) =>
    owns(right, key) && sameJsonValue(left[key], right[key], budget)
  );
};

const validateInput = (
  value: unknown,
  schema: JsonSchema,
  path: string,
  budget: ValidationBudget,
): string | undefined => {
  budget.remaining -= 1;
  if (budget.remaining < 0) return "Plugin input is too complex.";
  if (schema.enum !== undefined) {
    const matches = schema.enum.some((candidate) => sameJsonValue(value, candidate, budget));
    if (budget.remaining < 0) return "Plugin input is too complex.";
    if (!matches) return `${path} is not one of the allowed values.`;
  }
  const type = schema.type;
  if (typeof type !== "string") return `${path} uses an unsupported schema type.`;
  switch (type) {
    case "null":
      return value === null ? undefined : `${path} must be null.`;
    case "boolean":
      return typeof value === "boolean" ? undefined : `${path} must be a boolean.`;
    case "string": {
      if (typeof value !== "string") return `${path} must be a string.`;
      const minLength = typeof schema.minLength === "number" ? schema.minLength : undefined;
      const maxLength = typeof schema.maxLength === "number" ? schema.maxLength : undefined;
      if (minLength !== undefined && value.length < minLength) {
        return `${path} must contain at least ${minLength} characters.`;
      }
      if (maxLength !== undefined && value.length > maxLength) {
        return `${path} must contain at most ${maxLength} characters.`;
      }
      return undefined;
    }
    case "number":
    case "integer": {
      if (
        typeof value !== "number" ||
        !Number.isFinite(value) ||
        (type === "integer" && !Number.isSafeInteger(value))
      ) {
        return `${path} must be ${type === "integer" ? "an integer" : "a finite number"}.`;
      }
      const minimum = typeof schema.minimum === "number" ? schema.minimum : undefined;
      const maximum = typeof schema.maximum === "number" ? schema.maximum : undefined;
      if (minimum !== undefined && value < minimum) return `${path} must be at least ${minimum}.`;
      if (maximum !== undefined && value > maximum) return `${path} must be at most ${maximum}.`;
      return undefined;
    }
    case "array": {
      if (!Array.isArray(value)) return `${path} must be an array.`;
      if (value.length > MAX_VALIDATION_NODES) return `${path} contains too many items.`;
      const items = schema.items;
      if (items === undefined) return `${path} has no item schema.`;
      for (let index = 0; index < value.length; index += 1) {
        const failure = validateInput(value[index], items, `${path}[${index}]`, budget);
        if (failure !== undefined) return failure;
      }
      return undefined;
    }
    case "object": {
      if (!isRecord(value)) return `${path} must be an object.`;
      if (schema.additionalProperties !== false) {
        return `${path} does not use a strict object schema.`;
      }
      const properties = schema.properties ?? {};
      const required = schema.required ?? [];
      for (const field of required) {
        if (!owns(value, field)) return `${path}.${field} is required.`;
      }
      const unknown = Object.keys(value).find((field) => !owns(properties, field));
      if (unknown !== undefined) return `${path}.${unknown} is not allowed.`;
      for (const [field, fieldValue] of Object.entries(value)) {
        const fieldSchema = owns(properties, field) ? properties[field] : undefined;
        if (fieldSchema === undefined) continue;
        const failure = validateInput(fieldValue, fieldSchema, `${path}.${field}`, budget);
        if (failure !== undefined) return failure;
      }
      return undefined;
    }
    default:
      return `${path} uses an unsupported schema type.`;
  }
};

const pluginSourceBody = (plugin: RuntimePluginPackage, toolName: string): string =>
  `const __pocketpilot_plugin = (${plugin.source});\n` +
  `if (typeof __pocketpilot_plugin !== "function") throw new Error("Plugin source must evaluate to a function.");\n` +
  `return await __pocketpilot_plugin(${JSON.stringify(toolName)}, input);`;

export const createPluginTools = (
  plugins: readonly RuntimePluginPackage[],
  sandbox: WorkerSandbox,
): readonly AgentTool<unknown, JsonValue>[] => plugins.flatMap((plugin) =>
  plugin.tools.map((tool): AgentTool<unknown, JsonValue> => ({
    name: `plugin.${plugin.id}.${tool.name}`,
    description:
      `${tool.description} This installed plugin is pure computation only and has no native, network, file, model, or credential access.`,
    risk: "read",
    inputSchema: tool.inputSchema,
    execute: async (input, context): Promise<ToolResult<JsonValue>> => {
      const validationFailure = validateInput(
        input,
        tool.inputSchema,
        "arguments",
        { remaining: MAX_VALIDATION_NODES },
      );
      if (validationFailure !== undefined) {
        return toolFailure("PLUGIN_INVALID_ARGUMENTS", validationFailure);
      }
      const result = await sandbox.execute(
        {
          language: "javascript",
          source: pluginSourceBody(plugin, tool.name),
          input: input as JsonValue,
          timeoutMillis: PLUGIN_TIMEOUT_MILLIS
        },
        context.signal,
      );
      if (!result.success) {
        return toolFailure(
          `PLUGIN_${result.error.code}`,
          `Plugin ${plugin.id}/${tool.name} failed: ${result.error.message}`,
          {
            ...(result.error.retryable === undefined
              ? {}
              : { retryable: result.error.retryable }),
            details: {
              pluginId: plugin.id,
              pluginVersion: plugin.version,
              sandboxCode: result.error.code,
              ...(result.error.details === undefined ? {} : { sandboxDetails: result.error.details })
            }
          },
        );
      }
      const consoleEntries: JsonValue = result.data.console.map((entry) => ({
        level: entry.level,
        arguments: entry.arguments
      }));
      return toolSuccess(result.data.value, {
        pluginId: plugin.id,
        pluginVersion: plugin.version,
        durationMillis: result.data.durationMillis,
        console: consoleEntries,
        consoleTruncated: result.data.consoleTruncated
      });
    }
  })),
);
