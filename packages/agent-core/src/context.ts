import type { AgentMessage } from "./types.js";

export interface ContextBudget {
  readonly contextWindowTokens: number;
  readonly reserveOutputTokens: number;
  readonly summaryTokens: number;
}

export interface CompactedContext {
  readonly messages: readonly AgentMessage[];
  readonly compacted: boolean;
  readonly beforeTokens: number;
  readonly afterTokens: number;
  readonly omittedMessages: number;
}

const encoder = new TextEncoder();

/** Conservative tokenizer-independent estimate used before the native API call. */
export const estimateMessageTokens = (message: AgentMessage): number => {
  const serialized = JSON.stringify(message);
  return Math.ceil(encoder.encode(serialized).byteLength / 3) + 8;
};

export const estimateContextTokens = (messages: readonly AgentMessage[]): number =>
  messages.reduce((total, message) => total + estimateMessageTokens(message), 0);

interface MessageGroup {
  readonly messages: readonly AgentMessage[];
  readonly tokens: number;
}

const groupMessages = (messages: readonly AgentMessage[]): readonly MessageGroup[] => {
  const groups: MessageGroup[] = [];
  for (let index = 0; index < messages.length;) {
    const message = messages[index]!;
    const grouped: AgentMessage[] = [message];
    index += 1;
    if (message.role === "assistant" && (message.toolCalls?.length ?? 0) > 0) {
      while (index < messages.length && messages[index]?.role === "tool") {
        grouped.push(messages[index]!);
        index += 1;
      }
    }
    groups.push({
      messages: grouped,
      tokens: grouped.reduce((sum, item) => sum + estimateMessageTokens(item), 0)
    });
  }
  return groups;
};

const clipped = (value: string, maximum: number): string => {
  const normalized = value.replace(/\s+/gu, " ").trim();
  return normalized.length <= maximum ? normalized : `${normalized.slice(0, maximum)}…`;
};

const summarize = (messages: readonly AgentMessage[], maximumTokens: number): AgentMessage => {
  const maximumCharacters = Math.max(256, maximumTokens * 3);
  const lines: string[] = [
    "Earlier context was compacted locally. Treat this as background only; recent messages below are authoritative."
  ];
  for (const message of messages) {
    const line = message.role === "tool"
      ? `tool ${message.name} (${message.result.success ? "success" : message.result.error.code}): ${clipped(message.content, 360)}`
      : `${message.role}: ${clipped(message.content, 420)}`;
    if (lines.join("\n").length + line.length + 1 > maximumCharacters) break;
    lines.push(line);
  }
  return { role: "system", content: lines.join("\n") };
};

/**
 * Keeps system instructions and newest complete tool-call groups. Old content
 * is replaced with a bounded, deterministic summary so compaction does not
 * require another provider call or leak data outside the selected endpoint.
 */
export const compactContext = (
  messages: readonly AgentMessage[],
  budget: ContextBudget,
): CompactedContext => {
  const beforeTokens = estimateContextTokens(messages);
  const usableTokens = budget.contextWindowTokens - budget.reserveOutputTokens;
  if (usableTokens < 1_024) throw new Error("Provider context budget is too small.");
  if (beforeTokens <= usableTokens) {
    return { messages, compacted: false, beforeTokens, afterTokens: beforeTokens, omittedMessages: 0 };
  }

  const system = messages.filter((message) => message.role === "system");
  const nonSystem = messages.filter((message) => message.role !== "system");
  const systemTokens = estimateContextTokens(system);
  const recentBudget = Math.max(512, usableTokens - systemTokens - budget.summaryTokens);
  const groups = groupMessages(nonSystem);
  const kept: MessageGroup[] = [];
  let keptTokens = 0;
  for (let index = groups.length - 1; index >= 0; index -= 1) {
    const group = groups[index]!;
    if (kept.length > 0 && keptTokens + group.tokens > recentBudget) break;
    kept.unshift(group);
    keptTokens += group.tokens;
  }
  const keptMessages = kept.flatMap((group) => group.messages);
  const omittedCount = nonSystem.length - keptMessages.length;
  const omitted = nonSystem.slice(0, omittedCount);
  const summary = summarize(omitted, budget.summaryTokens);
  let result: AgentMessage[] = [...system, summary, ...keptMessages];
  while (result.length > system.length + 2 && estimateContextTokens(result) > usableTokens) {
    result = [...result.slice(0, system.length + 1), ...result.slice(system.length + 2)];
  }
  const afterTokens = estimateContextTokens(result);
  if (afterTokens > usableTokens) {
    throw new Error("System instructions exceed the provider context budget.");
  }
  return {
    messages: result,
    compacted: true,
    beforeTokens,
    afterTokens,
    omittedMessages: omittedCount
  };
};
