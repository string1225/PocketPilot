import type {
  AgentProvider,
  ProviderRequest,
  ProviderResponse
} from "@pocketpilot/agent-core";

export const DEFAULT_LLM_MODEL = "glm-5.3";
export const DEFAULT_CHAT_COMPLETIONS_BASE_URL =
  "https://open.bigmodel.cn/api/coding/paas/v4";
export const DEFAULT_RESPONSES_BASE_URL = "https://open.bigmodel.cn/api/v1";

export type OpenAICompatibleProtocol = "chat_completions" | "responses";

/**
 * Provider configuration deliberately contains only a credential reference.
 * API key material is owned by the Android native credential store and must
 * never cross the WebView bridge.
 */
export interface OpenAICompatibleConfig {
  readonly protocol?: OpenAICompatibleProtocol;
  readonly baseUrl?: string;
  readonly model?: string;
  readonly credentialId: string;
}

export interface ResolvedOpenAICompatibleConfig {
  readonly protocol: OpenAICompatibleProtocol;
  readonly baseUrl: string;
  readonly model: string;
  readonly credentialId: string;
}

export const DEFAULT_CHAT_COMPLETIONS_CONFIG: ResolvedOpenAICompatibleConfig =
  Object.freeze({
    protocol: "chat_completions",
    baseUrl: DEFAULT_CHAT_COMPLETIONS_BASE_URL,
    model: DEFAULT_LLM_MODEL,
    credentialId: ""
  });

export const DEFAULT_RESPONSES_CONFIG: ResolvedOpenAICompatibleConfig =
  Object.freeze({
    protocol: "responses",
    baseUrl: DEFAULT_RESPONSES_BASE_URL,
    model: DEFAULT_LLM_MODEL,
    credentialId: ""
  });

export interface NativeLlmTransport {
  complete(
    config: ResolvedOpenAICompatibleConfig,
    request: ProviderRequest,
  ): Promise<ProviderResponse>;
}

const normalizedEndpoint = (rawBaseUrl: string): string => {
  if (rawBaseUrl.length > 2_048) {
    throw new Error("LLM endpoint is too long.");
  }
  const baseUrl = new URL(rawBaseUrl);
  if (baseUrl.protocol !== "https:") {
    throw new Error("LLM endpoint must use HTTPS.");
  }
  if (baseUrl.port === "0") {
    throw new Error("LLM endpoint port is invalid.");
  }
  if (
    baseUrl.username.length > 0 ||
    baseUrl.password.length > 0 ||
    baseUrl.search.length > 0 ||
    baseUrl.hash.length > 0
  ) {
    throw new Error("LLM endpoint must not embed credentials, query parameters, or fragments.");
  }
  return baseUrl.toString().replace(/\/$/u, "");
};

export const resolveOpenAICompatibleConfig = (
  config: OpenAICompatibleConfig,
): ResolvedOpenAICompatibleConfig => {
  const protocol = config.protocol ?? "chat_completions";
  const defaultBaseUrl = protocol === "responses"
    ? DEFAULT_RESPONSES_BASE_URL
    : DEFAULT_CHAT_COMPLETIONS_BASE_URL;
  const rawBaseUrl = config.baseUrl?.trim() || defaultBaseUrl;
  const model = config.model?.trim() || DEFAULT_LLM_MODEL;
  const credentialId = config.credentialId.trim();
  if (model.length > 128) {
    throw new Error("LLM model identifier is too long.");
  }
  if (credentialId.length === 0 || credentialId.length > 256) {
    throw new Error("credentialId must contain between 1 and 256 characters.");
  }
  return {
    protocol,
    baseUrl: normalizedEndpoint(rawBaseUrl),
    model,
    credentialId
  };
};

export class OpenAICompatibleProvider implements AgentProvider {
  public readonly name: string;
  public readonly contextWindowTokens: number;
  readonly #config: ResolvedOpenAICompatibleConfig;
  readonly #transport: NativeLlmTransport;

  public constructor(
    config: OpenAICompatibleConfig,
    transport: NativeLlmTransport,
  ) {
    this.#config = resolveOpenAICompatibleConfig(config);
    this.#transport = transport;
    this.name = `openai-compatible:${this.#config.protocol}:${this.#config.model}`;
    const normalizedModel = this.#config.model.toLowerCase();
    this.contextWindowTokens = normalizedModel.startsWith("gpt-5")
      ? 400_000
      : normalizedModel.startsWith("glm-5")
        ? 128_000
        : 128_000;
  }

  public complete(request: ProviderRequest): Promise<ProviderResponse> {
    return this.#transport.complete(this.#config, request);
  }
}
