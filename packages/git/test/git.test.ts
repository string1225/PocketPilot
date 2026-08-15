import { describe, expect, it } from "vitest";

import { UnsupportedGitProvider } from "../src/index.js";

describe("GitProvider phase-two boundary", () => {
  it("returns a structured unavailable result without touching Git", async () => {
    const provider = new UnsupportedGitProvider();
    const context = {
      projectId: "project-1",
      signal: new AbortController().signal
    };
    await expect(provider.status(context)).resolves.toEqual({
      success: false,
      error: {
        code: "GIT_PROVIDER_UNAVAILABLE",
        message: "A native or isomorphic Git provider has not been configured."
      }
    });
    await expect(provider.push(context)).resolves.toMatchObject({
      success: false,
      error: { code: "GIT_PROVIDER_UNAVAILABLE" }
    });
  });
});
