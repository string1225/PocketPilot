import { normalizeWorkspacePath } from "./path.js";
import {
  WorkspaceError,
  type WorkspaceDeleteOptions,
  type WorkspaceEntry,
  type WorkspaceFile,
  type WorkspacePort,
  type WorkspaceSearchMatch,
  type WorkspaceSearchOptions
} from "./types.js";

export interface InMemoryWorkspaceOptions {
  readonly files?: readonly WorkspaceFile[];
  readonly maxFileBytes?: number;
  readonly maxFiles?: number;
}

const utf8Size = (value: string): number => new TextEncoder().encode(value).byteLength;
const compareText = (left: string, right: string): number =>
  left < right ? -1 : left > right ? 1 : 0;

export class InMemoryWorkspace implements WorkspacePort {
  #files = new Map<string, string>();
  readonly #maxFileBytes: number;
  readonly #maxFiles: number;

  public constructor(options: InMemoryWorkspaceOptions = {}) {
    this.#maxFileBytes = options.maxFileBytes ?? 1_048_576;
    this.#maxFiles = options.maxFiles ?? 10_000;
    if (this.#maxFileBytes < 1 || this.#maxFiles < 1) {
      throw new Error("Workspace limits must be positive.");
    }
    this.#files = this.#validatedFileMap(options.files ?? []);
  }

  public async list(directory = ""): Promise<readonly WorkspaceEntry[]> {
    const normalized = normalizeWorkspacePath(directory, { allowRoot: true });
    const prefix = normalized === "" ? "" : `${normalized}/`;
    if (normalized !== "" && this.#files.has(normalized)) {
      throw new WorkspaceError("NOT_A_DIRECTORY", `Path is a file: ${normalized}`);
    }

    const entries = new Map<string, WorkspaceEntry>();
    let directoryExists = normalized === "";
    for (const [path, content] of this.#files) {
      if (!path.startsWith(prefix)) {
        continue;
      }
      directoryExists = true;
      const remainder = path.slice(prefix.length);
      const slash = remainder.indexOf("/");
      const name = slash === -1 ? remainder : remainder.slice(0, slash);
      const childPath = prefix + name;
      if (slash === -1) {
        entries.set(childPath, {
          kind: "file",
          name,
          path: childPath,
          size: utf8Size(content)
        });
      } else if (!entries.has(childPath)) {
        entries.set(childPath, { kind: "directory", name, path: childPath });
      }
    }
    if (!directoryExists) {
      throw new WorkspaceError("NOT_FOUND", `Directory does not exist: ${normalized}`);
    }
    return [...entries.values()].sort((left, right) => compareText(left.path, right.path));
  }

  public async read(path: string): Promise<string> {
    const normalized = normalizeWorkspacePath(path);
    const content = this.#files.get(normalized);
    if (content === undefined) {
      throw new WorkspaceError("NOT_FOUND", `File does not exist: ${normalized}`);
    }
    return content;
  }

  public async write(path: string, content: string): Promise<void> {
    const normalized = normalizeWorkspacePath(path);
    if (!this.#files.has(normalized)) {
      throw new WorkspaceError("NOT_FOUND", `File does not exist: ${normalized}`);
    }
    this.#assertContent(content);
    this.#files.set(normalized, content);
  }

  public async create(path: string, content: string): Promise<void> {
    const normalized = normalizeWorkspacePath(path);
    if (
      this.#files.has(normalized) ||
      this.#hasDirectory(normalized) ||
      this.#hasFileAncestor(normalized)
    ) {
      throw new WorkspaceError("ALREADY_EXISTS", `Path already exists: ${normalized}`);
    }
    if (this.#files.size >= this.#maxFiles) {
      throw new WorkspaceError("FILE_LIMIT", "Workspace file count limit was reached.");
    }
    this.#assertContent(content);
    this.#files.set(normalized, content);
  }

  public async delete(
    path: string,
    options: WorkspaceDeleteOptions = {},
  ): Promise<void> {
    const normalized = normalizeWorkspacePath(path);
    if (this.#files.delete(normalized)) {
      return;
    }
    const prefix = `${normalized}/`;
    const descendants = [...this.#files.keys()].filter((candidate) =>
      candidate.startsWith(prefix),
    );
    if (descendants.length === 0) {
      throw new WorkspaceError("NOT_FOUND", `Path does not exist: ${normalized}`);
    }
    if (options.recursive !== true) {
      throw new WorkspaceError(
        "DIRECTORY_NOT_EMPTY",
        `Directory deletion requires recursive=true: ${normalized}`,
      );
    }
    for (const descendant of descendants) {
      this.#files.delete(descendant);
    }
  }

  public async move(from: string, to: string): Promise<void> {
    const source = normalizeWorkspacePath(from);
    const target = normalizeWorkspacePath(to);
    if (source === target) {
      return;
    }
    if (target.startsWith(`${source}/`)) {
      throw new WorkspaceError("INVALID_MOVE", "A directory cannot be moved into itself.");
    }

    const sourceIsFile = this.#files.has(source);
    const sourcePrefix = `${source}/`;
    const moving = [...this.#files.entries()].filter(
      ([path]) => path === source || path.startsWith(sourcePrefix),
    );
    if (moving.length === 0) {
      throw new WorkspaceError("NOT_FOUND", `Source path does not exist: ${source}`);
    }
    if (this.#files.has(target) || this.#hasDirectory(target)) {
      throw new WorkspaceError("ALREADY_EXISTS", `Target path already exists: ${target}`);
    }

    const movingPaths = new Set(moving.map(([path]) => path));
    const replacements = moving.map(([path, content]) => {
      const replacementPath = sourceIsFile
        ? target
        : `${target}${path.slice(source.length)}`;
      normalizeWorkspacePath(replacementPath);
      if (this.#hasFileAncestor(replacementPath, movingPaths)) {
        throw new WorkspaceError(
          "ALREADY_EXISTS",
          `Move would place a path beneath a file: ${replacementPath}`,
        );
      }
      if (this.#files.has(replacementPath) && !movingPaths.has(replacementPath)) {
        throw new WorkspaceError(
          "ALREADY_EXISTS",
          `Move would overwrite: ${replacementPath}`,
        );
      }
      return [replacementPath, content] as const;
    });

    for (const [path] of moving) {
      this.#files.delete(path);
    }
    for (const [path, content] of replacements) {
      this.#files.set(path, content);
    }
  }

  public async search(
    query: string,
    options: WorkspaceSearchOptions = {},
  ): Promise<readonly WorkspaceSearchMatch[]> {
    if (query.length === 0) {
      throw new WorkspaceError("INVALID_QUERY", "Search query must not be empty.");
    }
    const directory = normalizeWorkspacePath(options.path ?? "", { allowRoot: true });
    const prefix = directory === "" ? "" : `${directory}/`;
    const maxResults = options.maxResults ?? 100;
    if (!Number.isSafeInteger(maxResults) || maxResults < 1 || maxResults > 1_000) {
      throw new WorkspaceError(
        "INVALID_LIMIT",
        "maxResults must be an integer between 1 and 1000.",
      );
    }

    const needle = options.caseSensitive === true ? query : query.toLowerCase();
    const matches: WorkspaceSearchMatch[] = [];
    for (const [path, content] of [...this.#files.entries()].sort(([a], [b]) =>
      compareText(a, b),
    )) {
      if (!path.startsWith(prefix)) {
        continue;
      }
      const lines = content.split("\n");
      for (let lineIndex = 0; lineIndex < lines.length; lineIndex += 1) {
        const line = lines[lineIndex] ?? "";
        const haystack = options.caseSensitive === true ? line : line.toLowerCase();
        let fromIndex = 0;
        while (fromIndex <= haystack.length - needle.length) {
          const column = haystack.indexOf(needle, fromIndex);
          if (column === -1) {
            break;
          }
          matches.push({
            path,
            line: lineIndex + 1,
            column: column + 1,
            lineText: line.endsWith("\r") ? line.slice(0, -1) : line
          });
          if (matches.length >= maxResults) {
            return matches;
          }
          fromIndex = column + Math.max(needle.length, 1);
        }
      }
    }
    return matches;
  }

  public async snapshot(): Promise<readonly WorkspaceFile[]> {
    return [...this.#files.entries()]
      .sort(([left], [right]) => compareText(left, right))
      .map(([path, content]) => ({ path, content }));
  }

  public async replaceAll(files: readonly WorkspaceFile[]): Promise<void> {
    this.#files = this.#validatedFileMap(files);
  }

  #assertContent(content: string): void {
    if (typeof content !== "string") {
      throw new WorkspaceError("INVALID_CONTENT", "File content must be a string.");
    }
    const size = utf8Size(content);
    if (size > this.#maxFileBytes) {
      throw new WorkspaceError(
        "FILE_TOO_LARGE",
        `File exceeds the ${this.#maxFileBytes} byte limit.`,
      );
    }
  }

  #hasDirectory(path: string): boolean {
    const prefix = `${path}/`;
    return [...this.#files.keys()].some((candidate) => candidate.startsWith(prefix));
  }

  #hasFileAncestor(path: string, ignored = new Set<string>()): boolean {
    const segments = path.split("/");
    for (let index = 1; index < segments.length; index += 1) {
      const ancestor = segments.slice(0, index).join("/");
      if (!ignored.has(ancestor) && this.#files.has(ancestor)) {
        return true;
      }
    }
    return false;
  }

  #validatedFileMap(files: readonly WorkspaceFile[]): Map<string, string> {
    if (files.length > this.#maxFiles) {
      throw new WorkspaceError("FILE_LIMIT", "Workspace file count limit was exceeded.");
    }
    const result = new Map<string, string>();
    for (const file of files) {
      const path = normalizeWorkspacePath(file.path);
      this.#assertContent(file.content);
      if (result.has(path)) {
        throw new WorkspaceError("DUPLICATE_PATH", `Duplicate file path: ${path}`);
      }
      const segments = path.split("/");
      for (let index = 1; index < segments.length; index += 1) {
        const ancestor = segments.slice(0, index).join("/");
        if (result.has(ancestor)) {
          throw new WorkspaceError(
            "PATH_CONFLICT",
            `A file cannot contain another file: ${ancestor}`,
          );
        }
      }
      for (const existing of result.keys()) {
        if (existing.startsWith(`${path}/`)) {
          throw new WorkspaceError(
            "PATH_CONFLICT",
            `A file cannot contain another file: ${path}`,
          );
        }
      }
      result.set(path, file.content);
    }
    return result;
  }
}
