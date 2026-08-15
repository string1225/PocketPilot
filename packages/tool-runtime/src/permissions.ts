import type { AgentTool, ToolContext, ToolRisk } from "./types.js";

export type PermissionDecision =
  | { readonly outcome: "allow" }
  | { readonly outcome: "deny"; readonly reason: string }
  | { readonly outcome: "require_approval"; readonly reason: string };

export interface PermissionRequest {
  readonly tool: AgentTool;
  readonly input: unknown;
  readonly context: ToolContext;
}

export interface ToolPermissionPolicy {
  evaluate(request: PermissionRequest): PermissionDecision | Promise<PermissionDecision>;
}

export interface RiskPermissionOptions {
  readonly read?: PermissionDecision;
  readonly write?: PermissionDecision;
  readonly network?: PermissionDecision;
  readonly remote?: PermissionDecision;
}

const defaultDecisionByRisk: Readonly<Record<ToolRisk, PermissionDecision>> = {
  read: { outcome: "allow" },
  write: { outcome: "allow" },
  network: {
    outcome: "require_approval",
    reason: "Network tools require explicit approval."
  },
  remote: {
    outcome: "require_approval",
    reason: "Remote tools require explicit approval."
  }
};

export class RiskPermissionPolicy implements ToolPermissionPolicy {
  readonly #decisions: Readonly<Record<ToolRisk, PermissionDecision>>;

  public constructor(options: RiskPermissionOptions = {}) {
    this.#decisions = {
      read: options.read ?? defaultDecisionByRisk.read,
      write: options.write ?? defaultDecisionByRisk.write,
      network: options.network ?? defaultDecisionByRisk.network,
      remote: options.remote ?? defaultDecisionByRisk.remote
    };
  }

  public evaluate(request: PermissionRequest): PermissionDecision {
    return this.#decisions[request.tool.risk];
  }
}

export class AllowAllPermissionPolicy implements ToolPermissionPolicy {
  public evaluate(): PermissionDecision {
    return { outcome: "allow" };
  }
}
