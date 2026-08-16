import {
  RiskPermissionPolicy,
  type ToolPermissionPolicy
} from "./permissions.js";
import {
  toolFailure,
  type AgentTool,
  type ToolContext,
  type ToolDefinition,
  type ToolResult
} from "./types.js";

export type ToolRegistryEvent =
  | {
      readonly type: "tool.started";
      readonly name: string;
      readonly input: unknown;
      readonly context: ToolContext;
    }
  | {
      readonly type: "tool.finished";
      readonly name: string;
      readonly result: ToolResult<unknown>;
      readonly context: ToolContext;
    };

export interface ToolRegistryOptions {
  readonly permissionPolicy?: ToolPermissionPolicy;
  readonly onEvent?: (event: ToolRegistryEvent) => void;
}

const isAbortError = (error: unknown): boolean =>
  error instanceof DOMException
    ? error.name === "AbortError"
    : typeof error === "object" &&
      error !== null &&
      "name" in error &&
      error.name === "AbortError";

const errorMessage = (error: unknown): string =>
  error instanceof Error ? error.message : String(error);

export class ToolRegistry {
  readonly #tools = new Map<string, AgentTool>();
  readonly #permissionPolicy: ToolPermissionPolicy;
  readonly #onEvent: ((event: ToolRegistryEvent) => void) | undefined;

  public constructor(options: ToolRegistryOptions = {}) {
    this.#permissionPolicy = options.permissionPolicy ?? new RiskPermissionPolicy();
    this.#onEvent = options.onEvent;
  }

  public register(tool: AgentTool): this {
    const name = tool.name.trim();
    if (name.length === 0) {
      throw new Error("Tool name must not be empty.");
    }
    if (name !== tool.name) {
      throw new Error(`Tool name must already be normalized: ${tool.name}`);
    }
    if (this.#tools.has(name)) {
      throw new Error(`Tool is already registered: ${name}`);
    }
    this.#tools.set(name, tool);
    return this;
  }

  public registerAll(tools: readonly AgentTool[]): this {
    for (const tool of tools) {
      this.register(tool);
    }
    return this;
  }

  public has(name: string): boolean {
    return this.#tools.has(name);
  }

  public list(): readonly ToolDefinition[] {
    return [...this.#tools.values()].map((tool) => ({
      name: tool.name,
      description: tool.description,
      inputSchema: tool.inputSchema,
      risk: tool.risk
    }));
  }

  public async execute(
    name: string,
    input: unknown,
    context: ToolContext,
  ): Promise<ToolResult<unknown>> {
    if (context.signal.aborted) {
      return toolFailure("CANCELLED", "Tool execution was cancelled.");
    }

    const tool = this.#tools.get(name);
    if (tool === undefined) {
      return toolFailure("UNKNOWN_TOOL", `Unknown tool: ${name}`);
    }

    let decision;
    try {
      decision = await this.#permissionPolicy.evaluate({ tool, input, context });
    } catch (error) {
      return toolFailure(
        "PERMISSION_POLICY_FAILED",
        `Permission policy failed: ${errorMessage(error)}`,
      );
    }

    if (decision.outcome === "deny") {
      return toolFailure("PERMISSION_DENIED", decision.reason);
    }
    if (decision.outcome === "require_approval") {
      return toolFailure("APPROVAL_REQUIRED", decision.reason);
    }
    if (context.signal.aborted) {
      return toolFailure("CANCELLED", "Tool execution was cancelled.");
    }

    this.#emit({ type: "tool.started", name, input, context });

    let result: ToolResult<unknown>;
    try {
      result = await tool.execute(input, context);
      if (context.signal.aborted) {
        result = toolFailure("CANCELLED", "Tool execution was cancelled.");
      }
    } catch (error) {
      result = isAbortError(error)
        ? toolFailure("CANCELLED", "Tool execution was cancelled.")
        : toolFailure("TOOL_EXECUTION_FAILED", errorMessage(error));
    }

    this.#emit({ type: "tool.finished", name, result, context });
    return result;
  }

  #emit(event: ToolRegistryEvent): void {
    try {
      this.#onEvent?.(event);
    } catch {
      // Observers must never be able to change tool execution semantics.
    }
  }
}
