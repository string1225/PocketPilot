import { fileURLToPath } from "node:url";

import { defineConfig } from "vitest/config";

const packageSource = (name: string): string =>
  fileURLToPath(new URL(`./packages/${name}/src/index.ts`, import.meta.url));

export default defineConfig({
  resolve: {
    alias: {
      "@pocketpilot/agent-core": packageSource("agent-core"),
      "@pocketpilot/android-runtime": packageSource("android-runtime"),
      "@pocketpilot/checkpoint": packageSource("checkpoint"),
      "@pocketpilot/git": packageSource("git"),
      "@pocketpilot/providers": packageSource("providers"),
      "@pocketpilot/storage": packageSource("storage"),
      "@pocketpilot/tool-runtime": packageSource("tool-runtime"),
      "@pocketpilot/workspace": packageSource("workspace")
    }
  },
  test: {
    environment: "node",
    include: ["packages/**/*.test.ts"],
    testTimeout: 5_000
  }
});
