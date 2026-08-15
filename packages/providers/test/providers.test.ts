import { describe, expect, it, vi } from "vitest";

import type { AgentMessage, ProviderRequest } from "@pocketpilot/agent-core";
import { toolSuccess } from "@pocketpilot/tool-runtime";

import {
  OfflineCommandProvider,
  OpenAICompatibleProvider,
  ScriptedProvider
} from "../src/index.js";

const request = (messages: readonly AgentMessage[], step = 1): ProviderRequest => ({
  runId: "run-1",
  projectId: "project-1",
  step,
  messages,
  tools: [],
  signal: new AbortController().signal
});

describe("OfflineCommandProvider", () => {
  it.each([
    ["/list", "workspace.list", { path: "" }],
    ["/list src", "workspace.list", { path: "src" }],
    ["/read readme.md", "workspace.read", { path: "readme.md" }],
    [
      "/create notes/a.md | hello",
      "workspace.create",
      { path: "notes/a.md", content: "hello" }
    ],
    [
      "/replace notes/a.md | hello | hi",
      "workspace.patch",
      {
        path: "notes/a.md",
        oldText: "hello",
        newText: "hi",
        expectedOccurrences: 1
      }
    ],
    [
      "/delete notes/a.md",
      "workspace.delete",
      { path: "notes/a.md", recursive: false }
    ]
  ])("maps %s to %s", async (command, name, args) => {
    const response = await new OfflineCommandProvider().complete(
      request([{ role: "user", content: command }]),
    );
    expect(response.toolCalls).toEqual([
      {
        id: "run-1:1:offline",
        name,
        arguments: args
      }
    ]);
  });

  it("turns a tool result into final user-readable text", async () => {
    const response = await new OfflineCommandProvider().complete(
      request(
        [
          { role: "user", content: "/list" },
          {
            role: "tool",
            content: "result",
            name: "workspace.list",
            toolCallId: "call",
            result: toolSuccess([{ path: "a.txt" }])
          }
        ],
        2,
      ),
    );
    expect(response).toMatchObject({ content: expect.stringContaining("a.txt") });
    expect(response.toolCalls).toBeUndefined();
  });
});

describe("provider adapters", () => {
  it("records scripted requests without mutating them", async () => {
    const provider = new ScriptedProvider([{ content: "done" }]);
    const original = request([{ role: "user", content: "hello" }]);
    await expect(provider.complete(original)).resolves.toEqual({ content: "done" });
    expect(provider.requests).toHaveLength(1);
    expect(provider.requests[0]?.messages).not.toBe(original.messages);
  });

  it("delegates OpenAI-compatible calls through an injected native transport", async () => {
    const complete = vi.fn(async () => ({ content: "native" }));
    const provider = new OpenAICompatibleProvider(
      {
        baseUrl: "https://llm.example.test/v1/",
        model: "demo",
        credentialId: "credential-1"
      },
      { complete },
    );
    const providerRequest = request([{ role: "user", content: "hello" }]);
    await expect(provider.complete(providerRequest)).resolves.toEqual({ content: "native" });
    expect(complete).toHaveBeenCalledWith(
      {
        baseUrl: "https://llm.example.test/v1",
        model: "demo",
        credentialId: "credential-1"
      },
      providerRequest,
    );
  });

  it("rejects endpoints that embed credentials", () => {
    expect(
      () =>
        new OpenAICompatibleProvider(
          {
            baseUrl: "https://user:secret@llm.example.test/v1",
            model: "demo",
            credentialId: "credential-1"
          },
          { complete: async () => ({ content: "unused" }) },
        ),
    ).toThrow("must not embed credentials");
  });
});
