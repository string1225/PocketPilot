# PocketPilot

PocketPilot 是一个去中心化 Android Agent Workspace。手机保存 Project、Workspace、Checkpoint 和运行记录；TypeScript Agent 通过受控 Tool 调用操作项目。后续的构建、测试与长任务可以委托给用户自己的远程服务器。

当前 `0.1.0` 是第一阶段纵向切片，重点验证这条真实链路：

```text
Compose UI -> TypeScript Agent Loop -> JSON Bridge -> Workspace Tool -> SQLite Checkpoint
```

## 当前可用

- 创建、浏览和删除 Project，首次启动自动创建“个人项目”。
- 在 App 私有 Workspace 中创建、读取、编辑、移动、搜索和删除 UTF-8 文本文件。
- 运行本地 TypeScript Agent Runtime，并在 UI 中展示消息、Tool Call、Tool Result 与任务状态。
- 每次 Workspace 写操作后自动保存 Checkpoint，显示 Diff 摘要并支持 Restore。
- 用离线命令 Provider 在没有 API Key 的真机上验证完整 Agent 链路。
- 为 OpenAI Compatible Provider、Git 和 SSH 保留独立接口；真实接入安排在下一阶段。

首版不会把未接入的 LLM、Git 或 SSH 显示为可用功能。Agent 发起文件删除时会弹出一次性审批；只有用户明确允许后 Native Tool 才会执行，拒绝或取消都会安全停止该 Run。

## 仓库结构

```text
apps/android/                 Kotlin + Jetpack Compose 应用
packages/agent-core/          Agent 循环与事件
packages/tool-runtime/        Tool Registry 与权限
packages/workspace/           Workspace Port 与八个工具
packages/checkpoint/          Snapshot、Diff 与 Restore
packages/providers/           Provider 接口、离线与 OpenAI-compatible 适配边界
packages/storage/             存储接口与 SQLite schema
packages/git/                 Git 接口边界
packages/android-runtime/     Android 使用的浏览器 IIFE Runtime
docs/                         技术方案与真机指南
```

`deepseek-harness-master` 用作 Agent 运行机制的设计参考。首版复用了结构化事件、Tool Call/Result 配对、取消、权限分层和最大步数等模式，没有把它依赖的桌面 Node/Cordis 运行时直接带入 Android。

## 本地验证

需要 Node.js 22.12+、pnpm 10.29.1、JDK 17、Android SDK Platform 36 和 Build Tools 36.0.0。

```powershell
corepack enable
pnpm install --frozen-lockfile
pnpm check

Push-Location apps\android
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
Pop-Location
```

`pnpm check` 会执行 TypeScript 类型检查、单元测试、构建 Runtime bundle，并把 bundle 同步到 Android assets。Debug APK 生成在 `apps/android/app/build/outputs/apk/debug/app-debug.apk`。

没有 Android SDK 的开发机也可以运行 `pnpm check` 和 `apps/android/gradlew.bat help`；GitHub Actions 会在 `dev` 与 `main` 分支上验证完整 Android 构建并上传 Debug APK。

## 离线 Agent 命令

```text
/list
/create notes/hello.md | Hello PocketPilot
/read notes/hello.md
/replace notes/hello.md | Hello | Hi
/delete notes/hello.md
```

这些命令都会经过真实的 TypeScript Agent Loop、Bridge、Native Workspace Repository 和 Checkpoint。`/delete` 还会验证 Native 一次性审批链路。

## 文档

- [技术规划与实施方案](docs/TECHNICAL_PLAN.md)
- [Android 真机接入与测试指南](docs/PHONE_TESTING.md)

## 分支与提交约定

日常开发使用 `dev`，并跟踪 `origin/dev`。PocketPilot 内部的自动 Checkpoint 与 Git Commit 是两套独立历史；恢复 Checkpoint 只恢复 Workspace，不移动 Git HEAD。
