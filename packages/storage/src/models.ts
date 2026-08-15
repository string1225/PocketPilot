export type ProjectType = "local" | "git";

export interface ProjectRecord {
  readonly id: string;
  readonly name: string;
  readonly type: ProjectType;
  readonly workspacePath: string;
  readonly createdAt: number;
  readonly updatedAt: number;
}

export interface AgentRunRecord {
  readonly id: string;
  readonly projectId: string;
  readonly task: string;
  readonly status:
    | "idle"
    | "running"
    | "waiting_for_approval"
    | "completed"
    | "failed"
    | "cancelled";
  readonly startedAt: number;
  readonly finishedAt?: number;
}

export interface AgentEventRecord {
  readonly runId: string;
  readonly sequence: number;
  readonly type: string;
  readonly payloadJson: string;
  readonly createdAt: number;
}

export interface GitConfigRecord {
  readonly projectId: string;
  readonly remoteUrl?: string;
  readonly branch?: string;
  readonly credentialId?: string;
}

export interface SshServerRecord {
  readonly id: string;
  readonly name: string;
  readonly host: string;
  readonly port: number;
  readonly username: string;
  readonly credentialId: string;
  readonly description: string;
  readonly createdAt: number;
  readonly updatedAt: number;
}

export interface CredentialMetadataRecord {
  readonly id: string;
  readonly kind: "api_key" | "ssh_password" | "ssh_private_key" | "git_token";
  readonly label: string;
  readonly createdAt: number;
  readonly updatedAt: number;
}

export interface ConversationRecord {
  readonly id: string;
  readonly projectId: string;
  readonly title: string;
  readonly createdAt: number;
  readonly updatedAt: number;
}

export interface TaskRecord {
  readonly id: string;
  readonly projectId: string;
  readonly runId?: string;
  readonly description: string;
  readonly status: "pending" | "running" | "completed" | "failed" | "cancelled";
  readonly createdAt: number;
  readonly updatedAt: number;
}

export interface ProjectRepository {
  list(): Promise<readonly ProjectRecord[]>;
  get(id: string): Promise<ProjectRecord | undefined>;
  put(project: ProjectRecord): Promise<void>;
  delete(id: string): Promise<boolean>;
}

export interface AgentRunRepository {
  get(id: string): Promise<AgentRunRecord | undefined>;
  put(run: AgentRunRecord): Promise<void>;
  listForProject(projectId: string): Promise<readonly AgentRunRecord[]>;
  appendEvent(event: AgentEventRecord): Promise<void>;
  listEvents(runId: string): Promise<readonly AgentEventRecord[]>;
}
