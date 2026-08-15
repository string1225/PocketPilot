# AgentDock v0.1 技术规划与实施方案

## 1. 结论与本次交付边界

AgentDock 的长期目标是“手机上的去中心化 Agent 控制中心”，而不是把完整桌面开发环境塞进手机。系统由本地 Android 控制面、纯 TypeScript Agent Kernel、受控 Tool Runtime、项目工作区和可选远程执行端组成。

本次实现的是第一阶段可运行纵向切片：

- 创建、列出和删除 Project，并自动建立默认个人 Project。
- 在 App 私有目录中浏览、创建、读取、修改、删除和搜索文本文件。
- 运行编译后的 TypeScript Agent Loop，展示消息、Tool Call、Tool Result 和任务状态。
- 每次 Workspace 变更后自动建立 SQLite Checkpoint；支持查看变更摘要和恢复。
- 提供离线演示 Provider，使没有 API Key 的真机也能完整验证 Agent -> Tool -> Workspace -> Checkpoint 链路。
- 建立 OpenAI Compatible、Git、SSH 的稳定接口边界，但把真实 Endpoint、Git Remote 和 SSH 凭据执行放到第二阶段。

本次不把未实现的能力包装成“可用功能”。首版界面会明确显示“离线演示模式”；真实 LLM、Git 和 SSH 的接入状态会在路线图中标注。

## 2. 关键架构决策

### 2.1 Monorepo

```text
agentdock/
├── apps/
│   └── android/                 Kotlin + Jetpack Compose App
├── packages/
│   ├── agent-core/              Agent 状态机、消息和事件
│   ├── tool-runtime/            Tool Registry、权限和执行结果
│   ├── workspace/               Workspace Port、内存实现和工具
│   ├── checkpoint/              Snapshot、Diff、Restore
│   ├── providers/               Provider Port 与离线 Provider
│   ├── storage/                 持久化 Port 和 SQLite schema
│   ├── git/                     Git Port（第二阶段实现）
│   └── android-runtime/         浏览器目标的 Agent Runtime bundle
├── docs/
└── scripts/
```

TypeScript 包只依赖 Web 标准 API，不在核心层直接使用 `node:fs`、`child_process` 或系统 Git。这样同一 Kernel 可以运行在 Node 测试环境、Android JavaScript Runtime，以及后续 Desktop/Web Client。

### 2.2 Android 与 TypeScript 的边界

首版 Runtime Provider 使用 Android 系统 WebView 的 JavaScript 引擎加载仓库构建出的本地 bundle：

```text
Compose UI
    |
Android ViewModel / Repositories
    |
Local-only JSON Bridge
    |
TypeScript Agent Runtime
    |
Tool Registry -> Native Workspace Tool
```

选择这一方案是为了先验证产品纵向链路，并避免首版绑定 Node.js Mobile、Hermes 或某个 QuickJS JNI 版本。桥接接口是可替换的：后续迁移到 QuickJS/Hermes 时，Agent Core 和 Tool contract 不变。

安全约束：

- WebView 只加载 `android_asset` 中的本地 bundle。
- 禁用文件访问、内容访问、弹窗和任意页面导航。
- JavaScript 只能调用单一 JSON Tool Bridge。
- Native 层重新校验 Project ID、Tool 名称、参数和 Workspace 路径。
- Workspace 路径规范化后必须位于 `filesDir/projects/<projectId>/workspace` 下。

### 2.3 deepseek-harness 复用策略

`deepseek-harness-master` 是设计参考，不在首版整体移植。它的 Agent Loop 依赖较大的 Harness/Cordis/Session/LLM package graph，根工程要求 Node 22+，并包含主机端、终端和原生隔离能力；直接放进 Android 会把桌面运行时依赖带入移动端。

首版吸收以下成熟模式，并用 AgentDock 自己的轻量 contract 实现：

- Agent 具有明确的 `idle/running/waiting_for_approval/completed/failed/cancelled` 状态。
- 所有重要过程以有序事件记录，UI 不解析日志字符串。
- Tool Call 与 Tool Result 使用 call id 配对。
- Tool Result 即使失败也回填给模型；未知 Tool 不让 Agent Loop 崩溃。
- 取消通过 `AbortSignal` 传播；最大步数阻止无限循环。
- Tool 的权限判断与 Tool body 分离。

后续若直接引入 DeepSeek Harness 发布包，需要先完成浏览器运行时依赖闭包和 license/版本锁定审计。

## 3. 核心模块设计

### 3.1 Agent Core

入口：

```typescript
interface AgentRunner {
  run(input: AgentRunInput): Promise<AgentRunResult>
}
```

循环：

1. 建立 user message 与 run event。
2. 把消息历史、system prompt 和 Tool definitions 交给 Provider。
3. 若 Provider 返回最终文本，结束任务。
4. 若返回 Tool Calls，按模型顺序执行并记录 call/result event。
5. 把 Tool Results 加回上下文，进入下一 step。
6. 遇到取消、拒绝、Provider 异常或最大 step 数时，以结构化状态收敛。

首版同一 step 的 Tool Call 串行执行，保证 Workspace 修改和 Checkpoint 顺序确定。无副作用工具并行化留到后续优化。

### 3.2 Tool Runtime

```typescript
interface AgentTool<TInput = unknown, TOutput = unknown> {
  name: string
  description: string
  inputSchema: JsonSchema
  risk: 'read' | 'write' | 'network' | 'remote'
  execute(input: TInput, context: ToolContext): Promise<ToolResult<TOutput>>
}
```

权限默认值：

| 风险 | 首版策略 | 后续策略 |
| --- | --- | --- |
| read | 自动 | 自动 |
| write | 当前 Project 内自动并建立 Checkpoint | 可由用户改为逐次确认 |
| network | 首版未暴露 | GET 可配置自动，写请求确认 |
| remote | 首版未暴露 | SSH/Git push 必须确认 |

Tool Registry 拒绝重复注册，未知 Tool 返回结构化错误，并为 UI 发出 started/finished 事件。

首版把删除作为 write 中的例外：Native 层暂停具体 Tool RPC，UI 弹出只对该 call id 有效的一次性审批；允许后原调用继续，拒绝或取消则不触碰文件。Run 与 Project 在 Native 侧绑定，Runtime envelope 不能借用其他现存 Project ID。

### 3.3 Workspace

首版 Tool：

- `workspace.list`
- `workspace.read`
- `workspace.write`
- `workspace.create`
- `workspace.delete`
- `workspace.move`
- `workspace.search`
- `workspace.patch`

`workspace.patch` 使用可验证的 exact-text replacement：调用方提供 `oldText` 和 `newText`，默认要求目标只出现一次。相比在手机端实现不完整的 unified diff parser，这种方式能明确检测上下文漂移；后续可增加完整 unified diff adapter。

首版限制：只编辑 UTF-8 文本；单文件和搜索结果设大小/数量上限；不挂载外部目录。导入功能在后续通过 Android Storage Access Framework 把用户选中的文件复制到私有 Workspace。

### 3.4 Checkpoint

Checkpoint 保存完整文件快照，Diff 是相邻快照推导数据：

```typescript
interface Checkpoint {
  id: string
  projectId: string
  parentId?: string
  source: 'agent' | 'user' | 'git' | 'import'
  description: string
  createdAt: number
  files: CheckpointFile[]
}
```

触发点：

- Project 建立后的初始快照。
- 每次成功的 Workspace 写操作之后。
- Restore 改写文件之前的安全快照。
- Restore 完成之后建立一条新的恢复记录。

Restore 只改 Workspace，不执行 `git reset`，也不移动未来加入的 Git HEAD。首版用完整快照换取实现简单和可靠；第二阶段再做内容寻址、压缩和保留策略。

### 3.5 Storage

Android 使用系统 SQLite（`SQLiteOpenHelper`），避免首版额外引入注解处理链。核心表：

- `projects`
- `agent_runs`
- `agent_events`
- `checkpoints`
- `checkpoint_files`
- `git_config`
- `ssh_servers`
- `credentials`

API Key 和 SSH 私钥不得明文放进普通 SQLite；第二阶段用 Android Keystore 加密密钥材料，数据库只保存 credential id 和非敏感 metadata。

## 4. Android App 设计

技术基线：

- Kotlin + Jetpack Compose。
- AGP 9.3.0、Gradle 9.5.0、JDK 17。
- `compileSdk/targetSdk 37`，`minSdk 26`。
- Compose BOM 2026.06.00。
- 单 Activity、ViewModel + Repository；数据库和文件 IO 在后台 dispatcher。

首版页面：

1. Project 列表：默认个人 Project、新建、删除、进入。
2. Files：文件列表、文本编辑、新建、保存、删除、搜索。
3. Agent：离线演示说明、对话输入、运行/取消、Tool 调用轨迹。
4. Checkpoints：时间、来源、描述、变更摘要、恢复确认。

离线 Provider 支持可重复的验收指令，例如：

- `/list`
- `/create notes/hello.md | Hello AgentDock`
- `/read notes/hello.md`
- `/replace notes/hello.md | Hello | Hi`
- `/delete notes/hello.md`

它只负责把确定性指令转换为标准 Tool Call，不绕过 Agent Loop，也不直接碰文件。

## 5. 第二阶段接口

### 5.1 LLM Endpoint

实现 OpenAI Compatible Provider，配置 `baseUrl/apiKey/model`；HTTP 请求从 Native 网络层发出，避免 WebView CORS，并以流式事件回传 TypeScript Runtime。API Key 进入 Android Keystore。

### 5.2 Git

优先验证 `isomorphic-git` 在选定移动 Runtime 中的依赖闭包和性能；若文件系统 adapter 成本过高，则 Native JGit 作为 Android Provider，但保持统一 Git Port。`commit`、`pull`、`push` 均进入确认流程。

### 5.3 SSH

Native SSH Provider 使用 Android 兼容库和 Keystore credential。只暴露 `ssh.execute({serverId, command, timeoutMs})`，不把远端目录伪装成本地 Workspace。命令审计、超时、输出上限和危险命令二次确认是上线前置条件。

## 6. 验收标准

### TypeScript

- 所有 package 能 typecheck 和 build。
- Agent 能完成“Provider -> Tool -> Result -> Provider -> Final”多步循环。
- 未知 Tool、Tool 失败、权限拒绝、取消和最大步数都有测试。
- Workspace 防目录逃逸；八个 Tool 的关键路径有测试。
- Checkpoint 能生成 diff 并恢复删除/新增/修改的文件。

### Android

- Gradle unit tests 通过，Debug APK 可构建。
- 首次启动自动出现个人 Project。
- 手动创建文件并保存后出现 Checkpoint。
- 离线 Agent 指令能创建/读取/修改文件，UI 展示 Tool Call 和 Tool Result。
- Restore 后文件内容回到目标快照，Checkpoint 历史仍保留。
- 删除 Project 不影响其他 Project。

## 7. 实施顺序

1. 初始化 workspace、统一 TypeScript 配置、CI 友好的脚本。
2. 完成 Tool Runtime 与 Agent Core，并用 scripted provider 测试循环。
3. 完成 Workspace 和 Checkpoint 的纯 TypeScript 实现。
4. 完成 Android SQLite/File repositories 与单元测试。
5. 完成 Compose Project/Files/Agent/Checkpoint UI。
6. 构建 `android-runtime` bundle 并接通只加载本地资源的 JSON Bridge。
7. 完成真机验收说明和脚本，构建 Debug APK。

## 8. 风险与控制

- **Runtime 替换风险**：所有 JS/Native 通信只经过版本化 JSON envelope，避免 UI 绑定 WebView API。
- **数据损坏**：写文件采用临时文件 + 原子替换；写成功后才保存 Checkpoint。
- **路径穿越**：Native 与 TypeScript 两层校验；拒绝绝对路径、`..` 和 Workspace 外 canonical path。
- **大仓库性能**：首版设置文本文件和搜索上限；增量索引、二进制文件和大型 Git 仓库留到后续。
- **后台限制**：首版前台运行短任务；长任务在加入真实 LLM/SSH 时迁移到 WorkManager/前台服务。
- **凭据泄露**：首版不持久化秘密；第二阶段必须先接 Android Keystore。

## 9. Git 交付约定

- 开发分支固定为 `dev`，跟踪 `origin/dev`。
- 每轮修改先验证，再只 stage 本轮预期文件。
- 使用明确 commit message，提交后推送 `origin/dev`。
- Checkpoint 与 Git Commit 保持独立；App 内的自动 Checkpoint 永远不自动产生 Git Commit。
