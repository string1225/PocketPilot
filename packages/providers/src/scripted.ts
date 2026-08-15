import type {
  AgentProvider,
  ProviderRequest,
  ProviderResponse
} from "@agentdock/agent-core";

export type ScriptedProviderStep =
  | ProviderResponse
  | ((request: ProviderRequest) => ProviderResponse | Promise<ProviderResponse>);

export class ScriptedProvider implements AgentProvider {
  public readonly name: string;
  public readonly requests: ProviderRequest[] = [];
  readonly #steps: ScriptedProviderStep[];

  public constructor(
    steps: readonly ScriptedProviderStep[],
    name = "scripted-provider",
  ) {
    this.#steps = [...steps];
    this.name = name;
  }

  public async complete(request: ProviderRequest): Promise<ProviderResponse> {
    this.requests.push({ ...request, messages: [...request.messages], tools: [...request.tools] });
    const step = this.#steps.shift();
    if (step === undefined) {
      throw new Error("Scripted provider has no response left.");
    }
    return typeof step === "function" ? step(request) : step;
  }
}
