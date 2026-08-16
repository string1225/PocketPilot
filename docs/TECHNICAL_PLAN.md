# PocketPilot v0.1 技术规划与实现方案

## 1. 产品目标与本版边界

PocketPilot 是“手机上的 Agent 控制中心”，不是把完整桌面开发环境塞进 Android。手机持有项目、会话、Workspace、Checkpoint 和本机加密凭据；本地 Agent 负责规划与轻量文件工作，真实构建和系统命令可由用户授权的 SSH 服务器承担。

本版交付：

- Chat-first Android UI、项目/会话/产出物三页手势导航。
- 会话与消息 SQLite 持久化、多项目隔离、个性化/记忆/主题/语言设置。
- TypeScript Agent Loop 与 Native JSON Bridge。
- 默认 OpenAI-compatible Chat Completions 与可选 GLM 预设；后端保留 Responses 兼容能力。
- Workspace、Checkpoint、JGit 和 SSHJ 真实工具。
- Native 一次性审批、Android Keystore 凭据、后台前台服务和结果通知。
- 离线 Provider、自动测试、模拟器/USB/无线真机指南。

本版暂不实现：账号或中心云、远程文件系统、目录挂载同步、MCP/插件安装市场、跨进程重启恢复运行中的模型请求。

## 2. 总体架构

```text
Jetpack Compose
  Chat / Library / Artifacts / Settings
          |
PocketPilotViewModel
          |
Application-scoped AgentRunCoordinator
  |       |             |
SQLite  Foreground    Notifications
history Service
          |
RuntimePocketPilotService
          |
local WebView asset: TypeScript Agent Runtime
          |
versioned JSON RPC
          |
Native dispatch chain
  LLM -> Git/SSH -> Workspace
          |
Android Keystore / JGit / SSHJ / private Workspace
```

ViewModel 只负责 UI 状态；Run 的唯一 owner 是 Application 级 Coordinator。这样 Activity 因旋转、切后台或系统重建而消失时，不会连带取消任务。每个 Project 同时最多一个活动 Run，避免 Agent 与另一个会话并发改同一 Workspace。

## 3. UI 与状态模型

启动页固定为中间 Chat：

```text
右滑                       左滑
Projects & conversations <- Chat -> Artifacts
```

- Chat 顶栏左侧 Logo 打开 Settings。
- 顶栏文件夹按钮进入项目/会话页；`+` 创建当前项目下的新会话。
- 产出物页包含 Files 与 Checkpoints。
- 未保存编辑在切项目、Restore、启动 Agent 等冲突动作前阻止覆盖。
- 通知 deep link 同时携带 projectId/conversationId，冷启动和已有 Activity 都会校验关系后导航。

SQLite v5 主要表：

```text
projects -> conversations -> messages
projects -> agent_runs -> agent_events
projects -> checkpoints -> checkpoint_files
settings
git_config
ssh_servers (credential_id only)
plugins (manifest metadata + content hash; source stays in private files)
```

消息 ID 由时间线事件 ID 直接复用并幂等插入。单个 Run 使用串行 Channel 写 transcript，确保 `user -> assistant/tool/status -> finish` 顺序不会被并发 IO 打乱。

## 4. Agent Kernel 与 Bridge

`packages/agent-core` 保持纯 TypeScript、Web 标准 API：

1. 构建 system prompt、同会话历史与 Tool definitions。
2. 请求 Provider。
3. 把结构化 Tool Calls 交给 Tool Registry。
4. Tool Result 无论成功或失败都回填上下文。
5. 循环直到 final、取消、拒绝、超时或最大 step。

Android 使用只加载 `android_asset` 的 WebView 运行 IIFE bundle。Bridge 使用版本化 envelope，所有请求携带 `id/runId/projectId`；Native 校验活动 Run 和 Project 绑定，响应也必须匹配同一上下文。WebView 禁止外部导航、文件/内容访问和弹窗，网络 LLM 请求由 Native 完成，不受 CORS 影响。

`deepseek-harness-master` 仅作为结构化事件、Tool 配对、取消和权限分层的设计参考；其桌面 Node/Cordis 依赖未直接移植。

## 5. LLM Provider

设置页提供两个模型协议选项：

| 字段 | OpenAI Chat API（默认） | GLM |
| --- | --- | --- |
| 协议 | OpenAI-compatible Chat Completions | OpenAI-compatible Chat Completions |
| Endpoint | 用户填写，必填并显示 | 内置 `https://open.bigmodel.cn/api/coding/paas/v4`，不显示 |
| Text model | 用户填写，必填 | 默认 `glm-5.3` |
| Image model | 用户填写，必填 | 固定 `glm-5v-turbo` |
| API Key（AK） | 用户填写，必填 | 用户填写，必填 |
| Native path | 在 Endpoint 后追加 `/chat/completions` | 在内置 Endpoint 后追加 `/chat/completions` |

OpenAI Chat API 是默认选择，不预置 Endpoint、模型或 AK。GLM 是便捷预设：Endpoint 按[智谱 GLM Coding Plan 接入说明](https://docs.bigmodel.cn/cn/coding-plan/tool/others)固定并从 UI 隐藏，但不会内置 AK。切换选项时必须重新按当前选项校验必填字段，不能把“隐藏 Endpoint”误判为“无需 Endpoint”。

同一官方页面声明 Coding Plan 权益仅限其列出的受支持工具，PocketPilot 当前不在名单中。因此 GLM 预设只代表协议配置，不代表套餐授权；用户必须确认自身账号/套餐允许第三方客户端调用，或使用 OpenAI Chat API 配置其他有权访问的兼容端点。

Provider 后端仍兼容 OpenAI Responses 请求与响应映射，供迁移或内部集成使用；Responses 不再作为设置 UI 中可选择的模型协议。

Provider 支持：system/user/assistant/tool 历史、function tools、tool calls、结构化错误、超时和请求/响应体上限。Endpoint 只允许 HTTPS，拒绝 userinfo、query、fragment、重定向和不受支持路径。

API Key 的流向：

```text
Settings password field
  -> CharArray
  -> Android Keystore-backed AES-GCM ciphertext
  -> fixed credential id: llm.default
  -> Native Authorization header
```

明文 Key 不进入 Settings SQLite、Runtime JSON、项目、日志或 Git。Native LLM dispatcher 固定只允许 `llm.default`，防止 Runtime 把 Git/SSH 凭据借作 Bearer 发送到其他 Endpoint。

## 6. Tool 与安全边界

### Workspace / Checkpoint

支持 list/read/write/create/delete/move/search/patch。Native 规范化路径并拒绝绝对路径、`..`、符号链接逃逸、`.git`、`.pocketpilot` 和遗留 `.agentdock` 元数据目录。

成功写操作自动建立 Checkpoint。Restore 只恢复 Workspace，不执行 `git reset --hard`、不移动 HEAD、也不删除 `.git`。大文件/二进制仓库的 Checkpoint 必须以明确上限和部分失败语义处理，不能在 Git 已产生副作用后伪装为全事务。

### Git

Android 由 JGit 实现：init/clone/status/diff/commit/pull/push。Repository 永久绑定当前 Project 的 canonical Workspace；拒绝 linked worktree、gitdir 文件和 Workspace/.git 符号链接。

- status/diff 只读。
- init/clone/commit/pull/push Native 逐次审批。
- 私有 HTTPS Token 绑定允许的远端 host；审批明确显示主机和是否释放凭据。
- pull merge/rebase 失败、push remote rejection 都返回 Tool failure。
- 取消会传递给 JGit progress/transport；无法证明远端状态时明确返回“结果未知”并要求重查。

### SSH

SSH 仅是 `ssh.execute`，不是远程 Workspace。配置包含 server id、host、port、username、认证方式、固定 `SHA256:` 主机公钥指纹、credential id 和说明。

- 使用 SSHJ 严格 HostKeyVerifier；不接受 TOFU/Promiscuous verifier。
- `apps/android/sshj-android` 对 SSHJ 0.40.0 做可复现、哈希固定的最小 Android Ed25519 兼容构建；只让 Ed25519 使用未注册的 bundled Provider 实例，不新增、替换或重排进程全局 JCA Provider。
- 支持密码和未加密 PEM/OpenSSH 私钥；秘密只在一次连接期间短暂解密并清零可擦除缓冲。加密私钥 passphrase 是后续扩展。
- 命令在进入审批 UI 前做类型、长度、NUL/控制符、timeout 和输出预算校验。
- stdout/stderr 并发 drain，返回 exitCode；超时返回带截断 partial output 的结构化失败。

### HTTP

`http.request` 支持 GET/HEAD/POST/PUT/PATCH/DELETE，返回 `status/headers/body/bodyEncoding/truncated`。`bodyEncoding` 为 `utf8` 或 `base64`：只有严格合法且不含不可读控制字符的 UTF-8 才作为文本返回，其他二进制正文以 Base64 无损返回。HTTPS GET/HEAD 默认自动执行；写方法需要 Native 逐次审批。明文 HTTP 必须由 Tool 参数显式设置 `allowInsecureHttp=true`，且无论方法都必须审批。

Android manifest 在平台层允许 cleartext，唯一目的是让上述显式 opt-in 可以工作；真正的默认拒绝、逐次审批与目标校验全部由 `HttpToolDispatcher` 执行。WebView Runtime 继续通过 CSP、`blockNetworkLoads` 和 Native Bridge 与网络隔离，LLM/Git 端点仍只允许 HTTPS。

- URL 拒绝 userinfo、fragment、IP literal、本地/元数据主机名；只允许 HTTP(S)。
- OkHttp 5.3.0 使用自定义 DNS 校验 CNAME 最终地址，并把已校验地址直接交给连接层；混合公网/私网答案整体拒绝，覆盖 loopback、RFC1918、CGNAT、link-local、benchmark、documentation、multicast、unspecified、IPv4-mapped IPv6 等范围。
- 禁止重定向、自动重试、系统代理和 Cookie；模型只能提供 Accept、Content-Type、If-Match、If-None-Match，不能提供 Host、Authorization、Cookie、Proxy-*、API Key 等敏感头。
- 请求体、响应体、响应头和总调用时间均有独立上限；取消 Agent Run 会取消进行中的 OkHttp Call。响应只回传固定安全头集合，不回传 Set-Cookie 等凭据载体。

### 审批

审批框按操作类型展示 Workspace 目标、Git remote/branch/credential release 或 SSH server/fingerprint/command。批准只绑定一个 call id；拒绝会返回结构化失败并安全收敛当前 Run。

## 7. 后台与通知

`AgentRunCoordinator` 在用户点击发送的可见 Activity 路径立即启动 `dataSync` foreground service，然后启动 Application scope Run。它负责：

- 原子限制每 Project 一个活动 Run。
- 串行持久化 transcript。
- 转发取消与审批。
- 发布活动 Run StateFlow，供重建后的 UI 合并。
- 维护运行中/待审批通知，并在终态发送结果通知。

通知使用 `VISIBILITY_PRIVATE`，锁屏 public version 不显示 prompt 或模型输出。Android 13+ 即使用户拒绝通知权限，合法前台服务仍必须调用 `startForeground`；抽屉内的完成提醒则是可选能力。Android 15 的 `dataSync` 累计时限由 `onTimeout` 处理并取消活动任务。

平台限制依据 [Android foreground service timeout 文档](https://developer.android.com/develop/background-work/services/fgs/timeout)。

普通切出 App、旋转和 Activity 重建不会取消 Run；用户 force-stop、设备重启或进程被彻底杀死会中断网络请求。本版重启后把残留 RUNNING 记录标记为 interrupted，不尝试恢复模型 TCP 请求。

## 8. 验收与 CI

每次提交必须通过：

```powershell
pnpm install --frozen-lockfile
pnpm check

Push-Location apps\android
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
.\gradlew.bat connectedDebugAndroidTest   # 有在线模拟器/真机时
Pop-Location
```

关键验收：

- 首屏 Chat；左右手势与顶栏入口正确。
- 会话、消息、主题、语言和非秘密设置重启后仍在。
- Android Keystore instrumentation 测试通过，仓库 secret scan 无凭据。
- OpenAI-compatible Chat Completions 多轮消息与 Tool calling contract 测试通过；Responses 后端映射仅作为非 UI 兼容 contract 验证。
- Git/SSH 正常、拒绝、超时、取消、远端拒绝、错误主机指纹均有覆盖。
- 切后台后 Run 与通知继续；点击通知定位原会话。
- Runtime bundle 与提交的 Android asset 字节一致。

GitHub Actions 在 `dev/main` 运行同一 TypeScript/Android 构建，并上传 Debug APK artifact。

## 9. 后续路线

1. 跨进程重启恢复运行中的模型请求；当前版本已支持 Chat Completions SSE 增量显示、Token usage 和进程内 FIFO 消息队列。
2. Checkpoint 内容寻址、BLOB/大文件策略、压缩和保留策略。
3. Android Storage Access Framework 显式导入。
4. SSH upload/download 与远端任务日志，但仍不伪装成本地挂载。
5. Skills、MCP 和签名插件；默认最小权限并支持逐插件禁用。
6. Desktop/Web 客户端复用 Agent Core、Tool contract 和 Project model。

## 10. Git 交付约定

- 日常开发固定在 `dev`，跟踪 `origin/dev`。
- 每轮完成后运行验证、只 stage 预期文件、commit 并 push `origin/dev`。
- PocketPilot Checkpoint 与 Git Commit 永远保持独立。
