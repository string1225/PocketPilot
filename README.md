# PocketPilot

PocketPilot 是一个去中心化 Android Agent Workspace：手机保存项目、会话、Workspace、Checkpoint 和凭据引用，TypeScript Agent 通过受控 Tool 操作本地项目，并可把命令交给用户自己的 SSH 服务器。

当前 `0.1.0` 已打通：

```text
Compose Chat
  -> Application-scoped Agent Run + foreground service
  -> TypeScript Agent Loop
  -> OpenAI-compatible Chat Completions / GLM preset
  -> Native Tool approval boundary
  -> Workspace / Checkpoint / HTTP / JGit / SSHJ
```

## 当前可用

- 首屏直接进入聊天；右滑进入“项目与会话”，左滑进入“产出物”。
- 右上角可切换项目/会话或新建会话；会话和消息持久化在 SQLite。
- 左上角 PocketPilot 图标进入设置：模型、远程服务器、个性化、记忆、工具列表、插件入口、外观和中英文。
- 模型协议默认选择 OpenAI Chat API：用户必须填写 OpenAI-compatible HTTPS Endpoint、文本模型、图片模型和 API Key（AK）。也可选择 GLM；此时隐藏 Endpoint，文本模型默认 `glm-5.3`，图片识别固定使用 `glm-5v-turbo`，AK 仍必填。保存连接前会分别验证文本和图片模型。
- API Key、Git Token、SSH 密码/私钥使用 Android Keystore 保护的 AES-GCM 加密存储，不写进 Workspace、SQLite 明文字段、日志或 Git。
- Agent Run 由 Application 级 Coordinator 管理；离开 Activity 后由前台服务继续，结束、失败或取消后发通知，点击通知回到对应项目和会话。
- Chat Completions 使用 SSE 原位更新同一条 Assistant 消息；消息底部显示状态与 Token usage，运行中的新消息按 Project 进入 FIFO 队列。
- 支持中英文系统语音识别和多图片上传；图片先复制到 App 私有目录，再由受控 `image.analyze` 工具调用配置的视觉模型。
- Workspace 八个工具、自动 Checkpoint、Diff 与 Restore。
- JGit：init、clone、status、diff、commit、pull、push。
- HTTP：对公网 HTTP(S) 发起有超时和正文上限的请求；禁用重定向，拒绝 IP literal、内网/回环/链路本地/元数据地址及敏感请求头，二进制响应以 Base64 无损返回。
- 沙箱脚本：`execute_js` 与 `execute_ts` 在一次性 Web Worker 中运行，隔离 Native Bridge、网络、文件和凭据，并限制执行时间及输入输出。
- 本地插件：导入带清单和源码 SHA-256 的 JSON bundle，预览后安装、默认停用、二次确认启用；首版只允许沙箱内纯计算的 `read` 工具。
- SSHJ：固定主机公钥指纹验证、密码或未加密 PEM/OpenSSH 私钥认证、命令超时和输出上限。
- 没有配置 LLM Key 时自动使用离线命令 Provider，可在模拟器或真机上验证完整 Agent/Tool 链路。

危险或远端操作在 Native 层逐次审批。模型或 WebView Runtime 不能直接读取明文凭据；Native 只在核对当前 Run、Project、Endpoint/Remote 和批准结果后释放对应凭据。

## 仓库结构

```text
apps/android/                 Kotlin + Jetpack Compose 应用、SQLite、JGit、SSHJ
packages/agent-core/          Agent 循环与事件
packages/tool-runtime/        Tool Registry 与权限 contract
packages/workspace/           Workspace Port 与工具
packages/checkpoint/          Snapshot、Diff 与 Restore
packages/providers/           离线与 OpenAI-compatible Provider
packages/storage/             存储接口与 SQLite schema
packages/git/                 Git Port
packages/android-runtime/     Android WebView IIFE Runtime
docs/                         技术方案与设备测试指南
```

`deepseek-harness-master` 用作 Agent 运行机制的设计参考。PocketPilot 复用了结构化事件、Tool Call/Result 配对、取消、权限分层和最大步数等模式，没有把桌面 Node/Cordis 依赖直接带入 Android。

## 本地验证

需要 Node.js 22.12+、pnpm 10.29.1、JDK 17，以及 Android SDK Platform/Build Tools 36。

```powershell
corepack enable
pnpm install --frozen-lockfile
pnpm check

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
Push-Location apps\android
.\gradlew.bat :sshj-android:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
Pop-Location
```

`pnpm check` 会类型检查、运行 TypeScript 测试、构建 Runtime bundle，并同步 Android asset。Debug APK 位于：

```text
apps/android/app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions 在 `dev` 与 `main` 上重复上述验证，另启动 API 36 模拟器执行 instrumentation tests，并上传 7 天有效的 Debug APK artifact。

## 首次运行

1. 打开左上角设置。
2. 在“模型”中使用默认的 OpenAI Chat API，并填写 HTTPS Endpoint、文本模型、图片模型和 API Key；或选择 GLM，确认文本模型（默认 `glm-5.3`）并录入 API Key，图片识别固定使用 `glm-5v-turbo`。点击“保存并测试”后，App 会先验证两种模型再启用连接。
3. 如需私有 HTTPS Git，在“Git HTTPS 凭据”录入低权限 Token。
4. 如需 SSH，添加服务器，填写服务器公钥的 `SHA256:` 指纹，并录入密码或通过系统文件选择器导入 OpenSSH/PEM 私钥。
5. 返回聊天输入任务；需要远端副作用时，在审批框核对完整目标和操作。

仓库不会附带任何默认 API Key。开发、截图和日志分享时不要使用生产凭据。

智谱官方文档给出了 GLM 预设使用的 Coding Plan Base URL，但同时声明套餐权益仅限其列出的受支持工具；PocketPilot 当前不在该名单中。选择 GLM 前请确认你的账号/套餐允许第三方客户端调用；否则使用默认的 OpenAI Chat API 选项并填写你有权访问的 OpenAI-compatible HTTPS Endpoint。

## 离线命令

未配置 LLM Key 时可使用：

```text
/list
/create notes/hello.md | Hello PocketPilot
/read notes/hello.md
/replace notes/hello.md | Hello | Hi
/delete notes/hello.md
```

这些命令仍经过 TypeScript Agent Loop、Bridge、Native Workspace Repository 和 Checkpoint；`/delete` 会验证一次性审批。

## 文档

- [技术规划与实施方案](docs/TECHNICAL_PLAN.md)
- [Android 模拟器、USB/无线真机接入与测试指南](docs/PHONE_TESTING.md)
- [HTTP、沙箱脚本、插件包与 SSH 配置](docs/TOOLS_AND_PLUGINS.md)

## 分支与提交

开发分支固定为 `dev` 并跟踪 `origin/dev`。PocketPilot 的自动 Checkpoint 与 Git Commit 是两套独立历史；恢复 Checkpoint 只恢复 Workspace，不移动 Git HEAD。
