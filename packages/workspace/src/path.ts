import { WorkspaceError } from "./types.js";

export interface NormalizePathOptions {
  readonly allowRoot?: boolean;
}

const drivePath = /^[a-zA-Z]:/u;
const controlCharacter = /[\u0000-\u001f\u007f]/u;

export const normalizeWorkspacePath = (
  input: string,
  options: NormalizePathOptions = {},
): string => {
  if (typeof input !== "string") {
    throw new WorkspaceError("INVALID_PATH", "Workspace path must be a string.");
  }
  if (input === "" && options.allowRoot === true) {
    return "";
  }
  if (input.length === 0) {
    throw new WorkspaceError("INVALID_PATH", "Workspace path must not be empty.");
  }
  if (input.length > 1_024) {
    throw new WorkspaceError("INVALID_PATH", "Workspace path exceeds 1024 characters.");
  }
  if (
    input.startsWith("/") ||
    input.startsWith("\\") ||
    input.includes("\\") ||
    drivePath.test(input)
  ) {
    throw new WorkspaceError(
      "PATH_ESCAPE",
      "Absolute and platform-specific paths are not allowed.",
    );
  }
  if (controlCharacter.test(input)) {
    throw new WorkspaceError("INVALID_PATH", "Control characters are not allowed in paths.");
  }

  const segments = input.split("/");
  for (const segment of segments) {
    if (segment === "" || segment === "." || segment === "..") {
      throw new WorkspaceError(
        "PATH_ESCAPE",
        "Empty, current-directory, and parent-directory path segments are not allowed.",
      );
    }
    if (segment.length > 255) {
      throw new WorkspaceError("INVALID_PATH", "A path segment exceeds 255 characters.");
    }
  }
  return segments.join("/");
};
