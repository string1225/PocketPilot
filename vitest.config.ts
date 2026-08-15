import { fileURLToPath } from "node:url";

import { defineConfig } from "vitest/config";

const packageSource = (name: string): string =>
  fileURLToPath(new URL(`./packages/${name}/src/index.ts`, import.meta.url));

export default defineConfig({
  resolve: {
    alias: {
      "@agentdock/agent-core": packageSource("agent-core"),
      "@agentdock/android-runtime": packageSource("android-runtime"),
      "@agentdock/checkpoint": packageSource("checkpoint"),
      "@agentdock/git": packageSource("git"),
      "@agentdock/providers": packageSource("providers"),
      "@agentdock/storage": packageSource("storage"),
      "@agentdock/tool-runtime": packageSource("tool-runtime"),
      "@agentdock/workspace": packageSource("workspace")
    }
  },
  test: {
    environment: "node",
    include: ["packages/**/*.test.ts"],
    testTimeout: 5_000
  }
});
