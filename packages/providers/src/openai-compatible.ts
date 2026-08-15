import type {
  AgentProvider,
  ProviderRequest,
  ProviderResponse
} from "@agentdock/agent-core";

export interface OpenAICompatibleConfig {
  readonly baseUrl: string;
  readonly model: string;
  readonly credentialId: string;
}

export interface NativeLlmTransport {
  complete(
    config: OpenAICompatibleConfig,
    request: ProviderRequest,
  ): Promise<ProviderResponse>;
}

export class OpenAICompatibleProvider implements AgentProvider {
  public readonly name: string;
  readonly #config: OpenAICompatibleConfig;
  readonly #transport: NativeLlmTransport;

  public constructor(
    config: OpenAICompatibleConfig,
    transport: NativeLlmTransport,
  ) {
    const baseUrl = new URL(config.baseUrl);
    if (baseUrl.protocol !== "https:" && baseUrl.protocol !== "http:") {
      throw new Error("LLM endpoint must use http or https.");
    }
    if (
      baseUrl.username.length > 0 ||
      baseUrl.password.length > 0 ||
      baseUrl.search.length > 0 ||
      baseUrl.hash.length > 0
    ) {
      throw new Error("LLM endpoint must not embed credentials, query parameters, or fragments.");
    }
    if (config.model.trim().length === 0 || config.credentialId.trim().length === 0) {
      throw new Error("model and credentialId must not be empty.");
    }
    this.#config = { ...config, baseUrl: baseUrl.toString().replace(/\/$/u, "") };
    this.#transport = transport;
    this.name = `openai-compatible:${config.model}`;
  }

  public complete(request: ProviderRequest): Promise<ProviderResponse> {
    return this.#transport.complete(this.#config, request);
  }
}
