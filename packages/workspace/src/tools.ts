import {
  toolFailure,
  toolSuccess,
  type AgentTool,
  type JsonSchema,
  type JsonValue,
  type ToolResult
} from "@pocketpilot/tool-runtime";

import { normalizeWorkspacePath } from "./path.js";
import { WorkspaceError, type WorkspacePort } from "./types.js";

export type WorkspaceMutation = "create" | "write" | "delete" | "move" | "patch";

export interface WorkspaceMutationEvent {
  readonly operation: WorkspaceMutation;
  readonly paths: readonly string[];
}

export interface WorkspaceToolOptions {
  readonly onDidMutate?: (event: WorkspaceMutationEvent) => void | Promise<void>;
}

const objectSchema = (
  properties: Readonly<Record<string, JsonSchema>>,
  required: readonly string[] = [],
): JsonSchema => ({
  type: "object",
  properties,
  required,
  additionalProperties: false
});

const asRecord = (value: unknown): Record<string, unknown> => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new WorkspaceError("INVALID_INPUT", "Tool input must be an object.");
  }
  return value as Record<string, unknown>;
};

const stringField = (
  input: Record<string, unknown>,
  field: string,
  fallback?: string,
): string => {
  const value = input[field] ?? fallback;
  if (typeof value !== "string") {
    throw new WorkspaceError("INVALID_INPUT", `${field} must be a string.`);
  }
  return value;
};

const booleanField = (
  input: Record<string, unknown>,
  field: string,
  fallback = false,
): boolean => {
  const value = input[field] ?? fallback;
  if (typeof value !== "boolean") {
    throw new WorkspaceError("INVALID_INPUT", `${field} must be a boolean.`);
  }
  return value;
};

const numberField = (
  input: Record<string, unknown>,
  field: string,
  fallback: number,
): number => {
  const value = input[field] ?? fallback;
  if (typeof value !== "number") {
    throw new WorkspaceError("INVALID_INPUT", `${field} must be a number.`);
  }
  return value;
};

const executeSafely = async (
  operation: () => Promise<JsonValue>,
): Promise<ToolResult<JsonValue>> => {
  try {
    return toolSuccess(await operation());
  } catch (error) {
    if (error instanceof WorkspaceError) {
      return toolFailure(error.code, error.message);
    }
    return toolFailure(
      "WORKSPACE_FAILED",
      error instanceof Error ? error.message : String(error),
    );
  }
};

const emitMutation = async (
  options: WorkspaceToolOptions,
  operation: WorkspaceMutation,
  paths: readonly string[],
): Promise<void> => {
  await options.onDidMutate?.({ operation, paths });
};

export const createWorkspaceTools = (
  workspace: WorkspacePort,
  options: WorkspaceToolOptions = {},
): readonly AgentTool<unknown, JsonValue>[] => [
  {
    name: "workspace.list",
    description: "List files and directories immediately inside a workspace directory.",
    risk: "read",
    executionMode: "parallel",
    inputSchema: objectSchema({ path: { type: "string" } }),
    execute: (input) =>
      executeSafely(async () => {
        const path = stringField(asRecord(input), "path", "");
        return (await workspace.list(path)) as unknown as JsonValue;
      })
  },
  {
    name: "workspace.read",
    description: "Read one UTF-8 text file from the project workspace.",
    risk: "read",
    executionMode: "parallel",
    inputSchema: objectSchema({ path: { type: "string" } }, ["path"]),
    execute: (input) =>
      executeSafely(async () => {
        const path = normalizeWorkspacePath(stringField(asRecord(input), "path"));
        return { path, content: await workspace.read(path) };
      })
  },
  {
    name: "workspace.write",
    description: "Replace the complete contents of an existing UTF-8 text file.",
    risk: "write",
    inputSchema: objectSchema(
      { path: { type: "string" }, content: { type: "string" } },
      ["path", "content"],
    ),
    execute: (input) =>
      executeSafely(async () => {
        const record = asRecord(input);
        const path = normalizeWorkspacePath(stringField(record, "path"));
        const content = stringField(record, "content");
        await workspace.write(path, content);
        await emitMutation(options, "write", [path]);
        return { path, bytes: new TextEncoder().encode(content).byteLength };
      })
  },
  {
    name: "workspace.create",
    description: "Create a new UTF-8 text file, including implicit parent directories.",
    risk: "write",
    inputSchema: objectSchema(
      { path: { type: "string" }, content: { type: "string" } },
      ["path", "content"],
    ),
    execute: (input) =>
      executeSafely(async () => {
        const record = asRecord(input);
        const path = normalizeWorkspacePath(stringField(record, "path"));
        const content = stringField(record, "content");
        await workspace.create(path, content);
        await emitMutation(options, "create", [path]);
        return { path, bytes: new TextEncoder().encode(content).byteLength };
      })
  },
  {
    name: "workspace.delete",
    description: "Delete a file or, with recursive=true, a non-empty directory.",
    risk: "write",
    inputSchema: objectSchema(
      { path: { type: "string" }, recursive: { type: "boolean" } },
      ["path"],
    ),
    execute: (input) =>
      executeSafely(async () => {
        const record = asRecord(input);
        const path = normalizeWorkspacePath(stringField(record, "path"));
        await workspace.delete(path, { recursive: booleanField(record, "recursive") });
        await emitMutation(options, "delete", [path]);
        return { path, deleted: true };
      })
  },
  {
    name: "workspace.move",
    description: "Move a workspace file or directory without overwriting a target.",
    risk: "write",
    inputSchema: objectSchema(
      { from: { type: "string" }, to: { type: "string" } },
      ["from", "to"],
    ),
    execute: (input) =>
      executeSafely(async () => {
        const record = asRecord(input);
        const from = normalizeWorkspacePath(stringField(record, "from"));
        const to = normalizeWorkspacePath(stringField(record, "to"));
        await workspace.move(from, to);
        await emitMutation(options, "move", [from, to]);
        return { from, to };
      })
  },
  {
    name: "workspace.search",
    description: "Search UTF-8 workspace files for exact text.",
    risk: "read",
    executionMode: "parallel",
    inputSchema: objectSchema(
      {
        query: { type: "string" },
        path: { type: "string" },
        caseSensitive: { type: "boolean" },
        maxResults: { type: "number" }
      },
      ["query"],
    ),
    execute: (input) =>
      executeSafely(async () => {
        const record = asRecord(input);
        const query = stringField(record, "query");
        const path = stringField(record, "path", "");
        const caseSensitive = booleanField(record, "caseSensitive");
        const maxResults = numberField(record, "maxResults", 100);
        return (await workspace.search(query, {
          path,
          caseSensitive,
          maxResults
        })) as unknown as JsonValue;
      })
  },
  {
    name: "workspace.patch",
    description: "Apply an exact-text replacement after verifying its occurrence count.",
    risk: "write",
    inputSchema: objectSchema(
      {
        path: { type: "string" },
        oldText: { type: "string" },
        newText: { type: "string" },
        expectedOccurrences: { type: "number" }
      },
      ["path", "oldText", "newText"],
    ),
    execute: (input) =>
      executeSafely(async () => {
        const record = asRecord(input);
        const path = normalizeWorkspacePath(stringField(record, "path"));
        const oldText = stringField(record, "oldText");
        const newText = stringField(record, "newText");
        const expectedOccurrences = numberField(record, "expectedOccurrences", 1);
        if (oldText.length === 0) {
          throw new WorkspaceError("INVALID_PATCH", "oldText must not be empty.");
        }
        if (!Number.isSafeInteger(expectedOccurrences) || expectedOccurrences < 1) {
          throw new WorkspaceError(
            "INVALID_PATCH",
            "expectedOccurrences must be a positive safe integer.",
          );
        }
        const content = await workspace.read(path);
        const occurrences = content.split(oldText).length - 1;
        if (occurrences !== expectedOccurrences) {
          throw new WorkspaceError(
            "PATCH_MISMATCH",
            `Expected ${expectedOccurrences} occurrence(s), found ${occurrences}.`,
          );
        }
        const nextContent = content.split(oldText).join(newText);
        await workspace.write(path, nextContent);
        await emitMutation(options, "patch", [path]);
        return { path, replacements: occurrences };
      })
  }
];
