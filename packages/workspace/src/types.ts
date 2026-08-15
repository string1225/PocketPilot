export type WorkspaceEntry =
  | {
      readonly kind: "directory";
      readonly name: string;
      readonly path: string;
    }
  | {
      readonly kind: "file";
      readonly name: string;
      readonly path: string;
      readonly size: number;
    };

export interface WorkspaceFile {
  readonly path: string;
  readonly content: string;
}

export interface WorkspaceSearchMatch {
  readonly path: string;
  readonly line: number;
  readonly column: number;
  readonly lineText: string;
}

export interface WorkspaceSearchOptions {
  readonly path?: string;
  readonly caseSensitive?: boolean;
  readonly maxResults?: number;
}

export interface WorkspaceDeleteOptions {
  readonly recursive?: boolean;
}

export interface WorkspacePort {
  list(directory?: string): Promise<readonly WorkspaceEntry[]>;
  read(path: string): Promise<string>;
  write(path: string, content: string): Promise<void>;
  create(path: string, content: string): Promise<void>;
  delete(path: string, options?: WorkspaceDeleteOptions): Promise<void>;
  move(from: string, to: string): Promise<void>;
  search(
    query: string,
    options?: WorkspaceSearchOptions,
  ): Promise<readonly WorkspaceSearchMatch[]>;
  snapshot(): Promise<readonly WorkspaceFile[]>;
  replaceAll(files: readonly WorkspaceFile[]): Promise<void>;
}

export class WorkspaceError extends Error {
  public readonly code: string;

  public constructor(code: string, message: string) {
    super(message);
    this.name = "WorkspaceError";
    this.code = code;
  }
}
