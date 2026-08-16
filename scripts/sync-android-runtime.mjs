import { copyFile, mkdir, readFile, rename, rm, stat, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const source = resolve(
  repositoryRoot,
  "packages/android-runtime/dist/pocketpilot-runtime.js",
);
const destination = resolve(
  repositoryRoot,
  "apps/android/app/src/main/assets/pocketpilot-runtime.js",
);
const maxRuntimeBundleBytes = Math.floor(4.5 * 1024 * 1024);

let sourceStats;
try {
  sourceStats = await stat(source);
} catch {
  throw new Error(`Runtime bundle does not exist: ${source}. Run pnpm build first.`);
}
const bundle = await readFile(source, "utf8");
const normalizedBundle = bundle.replace(/[\t ]+$/gmu, "");
if (normalizedBundle !== bundle) {
  await writeFile(source, normalizedBundle, "utf8");
  sourceStats = await stat(source);
}
if (sourceStats.size > maxRuntimeBundleBytes) {
  throw new Error(
    `Runtime bundle is ${sourceStats.size} bytes; the limit is ${maxRuntimeBundleBytes} bytes.`,
  );
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
