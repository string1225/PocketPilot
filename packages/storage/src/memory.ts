import type { ProjectRecord, ProjectRepository } from "./models.js";

const cloneProject = (project: ProjectRecord): ProjectRecord => ({ ...project });
const compareText = (left: string, right: string): number =>
  left < right ? -1 : left > right ? 1 : 0;

export class InMemoryProjectRepository implements ProjectRepository {
  readonly #projects = new Map<string, ProjectRecord>();

  public constructor(projects: readonly ProjectRecord[] = []) {
    for (const project of projects) {
      if (this.#projects.has(project.id)) {
        throw new Error(`Duplicate project id: ${project.id}`);
      }
      this.#projects.set(project.id, cloneProject(project));
    }
  }

  public async list(): Promise<readonly ProjectRecord[]> {
    return [...this.#projects.values()]
      .sort(
        (left, right) =>
          left.createdAt - right.createdAt || compareText(left.id, right.id),
      )
      .map(cloneProject);
  }

  public async get(id: string): Promise<ProjectRecord | undefined> {
    const project = this.#projects.get(id);
    return project === undefined ? undefined : cloneProject(project);
  }

  public async put(project: ProjectRecord): Promise<void> {
    if (project.id.trim().length === 0 || project.name.trim().length === 0) {
      throw new Error("Project id and name must not be empty.");
    }
    this.#projects.set(project.id, cloneProject(project));
  }

  public async delete(id: string): Promise<boolean> {
    return this.#projects.delete(id);
  }
}
