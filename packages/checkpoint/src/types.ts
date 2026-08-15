import type { WorkspaceFile } from "@pocketpilot/workspace";

export type CheckpointSource = "agent" | "user" | "git" | "import";

export interface Checkpoint {
  readonly id: string;
  readonly projectId: string;
  readonly parentId?: string;
  readonly source: CheckpointSource;
  readonly description: string;
  readonly createdAt: number;
  readonly files: readonly WorkspaceFile[];
}

export type CheckpointFileChange =
  | {
      readonly kind: "added";
      readonly path: string;
      readonly after: string;
    }
  | {
      readonly kind: "deleted";
      readonly path: string;
      readonly before: string;
    }
  | {
      readonly kind: "modified";
      readonly path: string;
      readonly before: string;
      readonly after: string;
    };

export interface CheckpointDiff {
  readonly fromId?: string;
  readonly toId: string;
  readonly changes: readonly CheckpointFileChange[];
  readonly summary: {
    readonly added: number;
    readonly deleted: number;
    readonly modified: number;
  };
}

export interface CheckpointStore {
  put(checkpoint: Checkpoint): Promise<void>;
  get(id: string): Promise<Checkpoint | undefined>;
  list(projectId: string): Promise<readonly Checkpoint[]>;
}

export class CheckpointError extends Error {
  public readonly code: string;

  public constructor(code: string, message: string) {
    super(message);
    this.name = "CheckpointError";
    this.code = code;
  }
}
