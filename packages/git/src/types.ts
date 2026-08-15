export interface GitAuthor {
  readonly name: string;
  readonly email: string;
}

export interface GitStatusEntry {
  readonly path: string;
  readonly index: "unmodified" | "added" | "modified" | "deleted" | "renamed" | "untracked";
  readonly worktree: "unmodified" | "added" | "modified" | "deleted" | "renamed" | "untracked";
}

export interface GitStatus {
  readonly branch?: string;
  readonly head?: string;
  readonly entries: readonly GitStatusEntry[];
}

export interface GitDiff {
  readonly patch: string;
  readonly truncated: boolean;
}

export interface GitCommitResult {
  readonly oid: string;
}

export interface GitSyncResult {
  readonly before?: string;
  readonly after?: string;
  readonly updated: boolean;
}

export interface GitError {
  readonly code: string;
  readonly message: string;
  readonly retryable?: boolean;
}

export type GitResult<T> =
  | { readonly success: true; readonly data: T }
  | { readonly success: false; readonly error: GitError };

export interface GitOperationContext {
  readonly projectId: string;
  readonly signal: AbortSignal;
}

export interface GitProvider {
  init(context: GitOperationContext): Promise<GitResult<{ readonly initialized: true }>>;
  clone(
    context: GitOperationContext,
    input: { readonly url: string; readonly branch?: string; readonly credentialId?: string },
  ): Promise<GitResult<{ readonly head: string; readonly branch: string }>>;
  status(context: GitOperationContext): Promise<GitResult<GitStatus>>;
  diff(
    context: GitOperationContext,
    input?: { readonly staged?: boolean; readonly maxBytes?: number },
  ): Promise<GitResult<GitDiff>>;
  commit(
    context: GitOperationContext,
    input: { readonly message: string; readonly author: GitAuthor },
  ): Promise<GitResult<GitCommitResult>>;
  pull(
    context: GitOperationContext,
    input?: { readonly remote?: string; readonly branch?: string },
  ): Promise<GitResult<GitSyncResult>>;
  push(
    context: GitOperationContext,
    input?: {
      readonly remote?: string;
      readonly branch?: string;
      readonly credentialId?: string;
    },
  ): Promise<GitResult<GitSyncResult>>;
}
