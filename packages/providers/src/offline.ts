import type {
  AgentMessage,
  AgentProvider,
  ProviderRequest,
  ProviderResponse,
  ProviderToolCall
} from "@pocketpilot/agent-core";

const help = [
  "Offline demo commands:",
  "/list [path]",
  "/create path | content",
  "/read path",
  "/replace path | old text | new text",
  "/delete path"
].join("\n");

const lastMessage = (messages: readonly AgentMessage[]): AgentMessage | undefined =>
  messages.length === 0 ? undefined : messages[messages.length - 1];

const toolCall = (
  request: ProviderRequest,
  name: string,
  args: unknown,
): ProviderResponse => ({
  content: `Running ${name}.`,
  toolCalls: [
    {
      id: `${request.runId}:${request.step}:offline`,
      name,
      arguments: args
    }
  ]
});

const parsePiped = (body: string): readonly string[] =>
  body.split("|").map((part) => part.trim());

const commandResponse = (
  request: ProviderRequest,
  task: string,
): ProviderResponse => {
  const firstSpace = task.indexOf(" ");
  const command = (firstSpace === -1 ? task : task.slice(0, firstSpace)).toLowerCase();
  const body = firstSpace === -1 ? "" : task.slice(firstSpace + 1).trim();

  switch (command) {
    case "/list":
      return toolCall(request, "workspace.list", { path: body });
    case "/read":
      return body.length === 0
        ? { content: `A file path is required.\n${help}` }
        : toolCall(request, "workspace.read", { path: body });
    case "/create": {
      const [path, ...contentParts] = parsePiped(body);
      if (path === undefined || path.length === 0 || contentParts.length === 0) {
        return { content: `Use /create path | content.\n${help}` };
      }
      return toolCall(request, "workspace.create", {
        path,
        content: contentParts.join("|").trim()
      });
    }
    case "/replace": {
      const [path, oldText, ...newTextParts] = parsePiped(body);
      if (
        path === undefined ||
        path.length === 0 ||
        oldText === undefined ||
        oldText.length === 0 ||
        newTextParts.length === 0
      ) {
        return { content: `Use /replace path | old text | new text.\n${help}` };
      }
      return toolCall(request, "workspace.patch", {
        path,
        oldText,
        newText: newTextParts.join("|").trim(),
        expectedOccurrences: 1
      });
    }
    case "/delete":
      return body.length === 0
        ? { content: `A file path is required.\n${help}` }
        : toolCall(request, "workspace.delete", { path: body, recursive: false });
    default:
      return { content: help };
  }
};

const summarizeToolResult = (message: Extract<AgentMessage, { role: "tool" }>): string => {
  if (message.result.success) {
    let data: string;
    try {
      data = JSON.stringify(message.result.data, null, 2);
    } catch {
      data = "(non-serializable result)";
    }
    return `${message.name} completed.\n${data}`;
  }
  return `${message.name} failed: ${message.result.error.code}: ${message.result.error.message}`;
};

export class OfflineCommandProvider implements AgentProvider {
  public readonly name = "offline-command-provider";

  public async complete(request: ProviderRequest): Promise<ProviderResponse> {
    if (request.signal.aborted) {
      throw new DOMException("The operation was aborted.", "AbortError");
    }
    const latest = lastMessage(request.messages);
    if (latest?.role === "tool") {
      return { content: summarizeToolResult(latest) };
    }
    const userMessage = [...request.messages]
      .reverse()
      .find((message): message is Extract<AgentMessage, { role: "user" }> =>
        message.role === "user",
      );
    return userMessage === undefined
      ? { content: help }
      : commandResponse(request, userMessage.content.trim());
  }
}

export const OFFLINE_COMMAND_HELP = help;

export type { ProviderToolCall };
