import type {
  WorkspaceFile,
  WorkspaceMutationEvent,
  WorkspacePort
} from "@pocketpilot/workspace";

import {
  CheckpointError,
  type Checkpoint,
  type CheckpointDiff,
  type CheckpointFileChange,
  type CheckpointSource,
  type CheckpointStore
} from "./types.js";

export interface CaptureCheckpointInput {
  readonly projectId: string;
  readonly source: CheckpointSource;
  readonly description: string;
  readonly parentId?: string;
}

export interface RestoreCheckpointOptions {
  readonly source?: CheckpointSource;
  readonly description?: string;
}

export interface CheckpointManagerOptions {
  readonly workspace: WorkspacePort;
  readonly store: CheckpointStore;
  readonly createId?: () => string;
  readonly now?: () => number;
}

export interface MutationCheckpointOptions {
  readonly projectId: string;
  readonly source?: CheckpointSource;
  readonly describe?: (event: WorkspaceMutationEvent) => string;
}

let fallbackId = 0;

const defaultCreateId = (): string => {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }
  fallbackId += 1;
  return `checkpoint-${Date.now()}-${fallbackId}`;
};

const fileMap = (files: readonly WorkspaceFile[]): Map<string, string> =>
  new Map(files.map((file) => [file.path, file.content]));

const compareText = (left: string, right: string): number =>
  left < right ? -1 : left > right ? 1 : 0;

export class CheckpointManager {
  readonly #workspace: WorkspacePort;
  readonly #store: CheckpointStore;
  readonly #createId: () => string;
  readonly #now: () => number;
  #lastCreatedAt = Number.MIN_SAFE_INTEGER;

  public constructor(options: CheckpointManagerOptions) {
    this.#workspace = options.workspace;
    this.#store = options.store;
    this.#createId = options.createId ?? defaultCreateId;
    this.#now = options.now ?? Date.now;
  }

  public async capture(input: CaptureCheckpointInput): Promise<Checkpoint> {
    if (input.projectId.trim().length === 0) {
      throw new CheckpointError("INVALID_PROJECT", "projectId must not be empty.");
    }
    if (input.description.trim().length === 0) {
      throw new CheckpointError("INVALID_DESCRIPTION", "description must not be empty.");
    }
    const latest = await this.latest(input.projectId);
    const parentId = input.parentId ?? latest?.id;
    if (parentId !== undefined) {
      const parent = await this.#store.get(parentId);
      if (parent === undefined) {
        throw new CheckpointError("PARENT_NOT_FOUND", `Checkpoint not found: ${parentId}`);
      }
      if (parent.projectId !== input.projectId) {
        throw new CheckpointError(
          "PROJECT_MISMATCH",
          "Parent checkpoint belongs to another project.",
        );
      }
    }

    const now = this.#now();
    if (!Number.isSafeInteger(now)) {
      throw new CheckpointError("INVALID_TIME", "Checkpoint time must be a safe integer.");
    }
    const createdAt = Math.max(now, this.#lastCreatedAt + 1);
    this.#lastCreatedAt = createdAt;
    const checkpoint: Checkpoint = {
      id: this.#createId(),
      projectId: input.projectId,
      ...(parentId === undefined ? {} : { parentId }),
      source: input.source,
      description: input.description,
      createdAt,
      files: (await this.#workspace.snapshot()).map((file) => ({ ...file }))
    };
    await this.#store.put(checkpoint);
    return checkpoint;
  }

  public async list(projectId: string): Promise<readonly Checkpoint[]> {
    return this.#store.list(projectId);
  }

  public async latest(projectId: string): Promise<Checkpoint | undefined> {
    const checkpoints = await this.#store.list(projectId);
    return checkpoints.length === 0 ? undefined : checkpoints[checkpoints.length - 1];
  }

  public async diff(checkpointId: string, fromId?: string): Promise<CheckpointDiff> {
    const target = await this.#requireCheckpoint(checkpointId);
    const resolvedFromId = fromId ?? target.parentId;
    const base =
      resolvedFromId === undefined
        ? undefined
        : await this.#requireCheckpoint(resolvedFromId);
    if (base !== undefined && base.projectId !== target.projectId) {
      throw new CheckpointError(
        "PROJECT_MISMATCH",
        "Cannot diff checkpoints from different projects.",
      );
    }

    const before = fileMap(base?.files ?? []);
    const after = fileMap(target.files);
    const paths = [...new Set([...before.keys(), ...after.keys()])].sort(compareText);
    const changes: CheckpointFileChange[] = [];
    for (const path of paths) {
      const previous = before.get(path);
      const next = after.get(path);
      if (previous === undefined && next !== undefined) {
        changes.push({ kind: "added", path, after: next });
      } else if (previous !== undefined && next === undefined) {
        changes.push({ kind: "deleted", path, before: previous });
      } else if (previous !== next && previous !== undefined && next !== undefined) {
        changes.push({ kind: "modified", path, before: previous, after: next });
      }
    }

    return {
      ...(resolvedFromId === undefined ? {} : { fromId: resolvedFromId }),
      toId: checkpointId,
      changes,
      summary: {
        added: changes.filter((change) => change.kind === "added").length,
        deleted: changes.filter((change) => change.kind === "deleted").length,
        modified: changes.filter((change) => change.kind === "modified").length
      }
    };
  }

  public async restore(
    checkpointId: string,
    options: RestoreCheckpointOptions = {},
  ): Promise<Checkpoint> {
    const target = await this.#requireCheckpoint(checkpointId);
    await this.#workspace.replaceAll(target.files);
    return this.capture({
      projectId: target.projectId,
      source: options.source ?? "user",
      description: options.description ?? `Restored checkpoint ${checkpointId}`
    });
  }

  public mutationHandler(
    options: MutationCheckpointOptions,
  ): (event: WorkspaceMutationEvent) => Promise<void> {
    return async (event) => {
      const description =
        options.describe?.(event) ??
        `${event.operation} ${event.paths.join(" -> ")}`;
      await this.capture({
        projectId: options.projectId,
        source: options.source ?? "agent",
        description
      });
    };
  }

  async #requireCheckpoint(id: string): Promise<Checkpoint> {
    const checkpoint = await this.#store.get(id);
    if (checkpoint === undefined) {
      throw new CheckpointError("NOT_FOUND", `Checkpoint not found: ${id}`);
    }
    return checkpoint;
  }
}
