import type {
  GitCommitResult,
  GitDiff,
  GitOperationContext,
  GitProvider,
  GitResult,
  GitStatus,
  GitSyncResult
} from "./types.js";

const unavailable = <T>(): GitResult<T> => ({
  success: false,
  error: {
    code: "GIT_PROVIDER_UNAVAILABLE",
    message: "A native or isomorphic Git provider has not been configured."
  }
});

export class UnsupportedGitProvider implements GitProvider {
  public async init(
    _context: GitOperationContext,
  ): Promise<GitResult<{ readonly initialized: true }>> {
    return unavailable();
  }

  public async clone(
    _context: GitOperationContext,
    _input: { readonly url: string; readonly branch?: string; readonly credentialId?: string },
  ): Promise<
    GitResult<{ readonly head: string; readonly branch: string }>
  > {
    return unavailable();
  }

  public async status(_context: GitOperationContext): Promise<GitResult<GitStatus>> {
    return unavailable();
  }

  public async diff(
    _context: GitOperationContext,
    _input?: { readonly staged?: boolean; readonly maxBytes?: number },
  ): Promise<GitResult<GitDiff>> {
    return unavailable();
  }

  public async commit(
    _context: GitOperationContext,
    _input: Parameters<GitProvider["commit"]>[1],
  ): Promise<GitResult<GitCommitResult>> {
    return unavailable();
  }

  public async pull(
    _context: GitOperationContext,
    _input?: Parameters<GitProvider["pull"]>[1],
  ): Promise<GitResult<GitSyncResult>> {
    return unavailable();
  }

  public async push(
    _context: GitOperationContext,
    _input?: Parameters<GitProvider["push"]>[1],
  ): Promise<GitResult<GitSyncResult>> {
    return unavailable();
  }
}
