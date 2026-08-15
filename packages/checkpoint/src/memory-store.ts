import {
  CheckpointError,
  type Checkpoint,
  type CheckpointStore
} from "./types.js";

const cloneCheckpoint = (checkpoint: Checkpoint): Checkpoint => ({
  ...checkpoint,
  files: checkpoint.files.map((file) => ({ ...file }))
});

const compareText = (left: string, right: string): number =>
  left < right ? -1 : left > right ? 1 : 0;

export class InMemoryCheckpointStore implements CheckpointStore {
  readonly #checkpoints = new Map<string, Checkpoint>();

  public async put(checkpoint: Checkpoint): Promise<void> {
    if (this.#checkpoints.has(checkpoint.id)) {
      throw new CheckpointError(
        "ALREADY_EXISTS",
        `Checkpoint already exists: ${checkpoint.id}`,
      );
    }
    if (checkpoint.parentId !== undefined) {
      const parent = this.#checkpoints.get(checkpoint.parentId);
      if (parent === undefined) {
        throw new CheckpointError(
          "PARENT_NOT_FOUND",
          `Parent checkpoint does not exist: ${checkpoint.parentId}`,
        );
      }
      if (parent.projectId !== checkpoint.projectId) {
        throw new CheckpointError(
          "PROJECT_MISMATCH",
          "Parent checkpoint belongs to another project.",
        );
      }
    }
    this.#checkpoints.set(checkpoint.id, cloneCheckpoint(checkpoint));
  }

  public async get(id: string): Promise<Checkpoint | undefined> {
    const checkpoint = this.#checkpoints.get(id);
    return checkpoint === undefined ? undefined : cloneCheckpoint(checkpoint);
  }

  public async list(projectId: string): Promise<readonly Checkpoint[]> {
    return [...this.#checkpoints.values()]
      .filter((checkpoint) => checkpoint.projectId === projectId)
      .sort(
        (left, right) =>
          left.createdAt - right.createdAt || compareText(left.id, right.id),
      )
      .map(cloneCheckpoint);
  }
}
