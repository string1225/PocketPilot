import { copyFile, mkdir, rename, rm, stat } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const source = resolve(
  repositoryRoot,
  "packages/android-runtime/dist/agentdock-runtime.js",
);
const destination = resolve(
  repositoryRoot,
  "apps/android/app/src/main/assets/agentdock-runtime.js",
);

try {
  await stat(source);
} catch {
  throw new Error(`Runtime bundle does not exist: ${source}. Run pnpm build first.`);
}

await mkdir(dirname(destination), { recursive: true });
const temporary = `${destination}.${process.pid}.tmp`;
try {
  await copyFile(source, temporary);
  await rename(temporary, destination);
} finally {
  await rm(temporary, { force: true });
}
process.stdout.write(`Synced Android runtime asset to ${destination}\n`);
