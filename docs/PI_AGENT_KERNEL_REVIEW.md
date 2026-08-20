# PocketPilot Agent Kernel 与 pi 对照评审

本评审基于本地 `pi-main/packages/agent`、`pi-main/packages/coding-agent` 与 PocketPilot 当前实现做结构对照。PocketPilot 没有复制或依赖 pi；pi 只作为成熟 Agent Loop 行为的设计参考。

## 当前 PocketPilot Kernel

`packages/agent-core/src/runner.ts` 的主循环是不设固定轮次的 `for (;;)`：

1. 组合 System Prompt、会话历史和本次 User 消息。
2. 调用 Provider，并把 SSE delta 作为同一 Assistant 消息增量发布。
3. 如果模型没有返回 Tool Call，则保存最终 Assistant 消息并结束。
4. 如果有 Tool Call，则逐个通过 Tool Registry 执行，把结构化结果追加为 Tool 消息，再进入下一轮模型调用。
5. 用户取消、审批拒绝、Provider/Tool 失败或 Android 系统终止会安全收敛 Run。

Android 侧在每次 Run 启动时构造 System Prompt。稳定内核规则位于最前面；随后按当前 Project 注入绑定的 Git remote、可用 SSH Server；再追加用户的个性化提示词；最后追加中文或英文的强制回答语言。会话记忆作为历史消息独立传入，不拼进 System Prompt。Tool schema 由 Runtime 的 Tool Registry 提供，因此模型看到的能力与 Native 实际可执行能力保持一致。

## 与 pi 的主要差异

pi 的 Loop 额外具备以下通用能力：

- 同一 Run 内的 steering 与 follow-up 队列，可在一次模型/工具循环之间插入用户修正，而不是另起 Run。
- 根据模型上下文窗口做 token 预算和自动 compaction，并保留近期工作区间。
- 读取 Provider finish reason；如果输出因长度截断，则拒绝执行可能不完整的 Tool Call 参数。
- Tool 可声明并行或串行执行策略，读操作可并发，写操作保持顺序屏障。
- turn 前后转换、Tool hook、动态 Provider/API Key 解析和可配置停止条件。

PocketPilot 继续保留 Android 安全边界：每个 Project 同时一个 Run、Native 一次性审批、项目级 Workspace/Checkpoint/Git 凭据隔离、前台服务和持久化消息队列。同一会话“运行中继续发送”现在进入当前 Run 的 follow-up mailbox；不同会话或无法接纳时才保留为下一 Run。

## 已落地的 Kernel 改进

以下方向已在 PocketPilot 自有实现中落地，pi 仍仅是行为设计参考：

1. **上下文 token 预算与 compaction**：按 Provider 暴露的 model context window 做保守 token 估算；保留 System Prompt 与最近完整 Tool Call/Result 组，并以本地确定性摘要替换更早内容。
2. **finish reason 安全闭环**：Provider contract 增加 `stop/length/tool_calls/content_filter` 等原因；`length` 或不完整 JSON 时绝不执行 Tool Call。
3. **Tool 调度策略**：在 registry 中声明 `parallel` 与 `sequential`。只读、互不依赖的调用可并发；Workspace/Git/SSH 写操作继续串行并受 Project mutation gate 保护。
4. **同一 Run steering/follow-up**：允许用户在 Agent 工作时补充约束，并在当前工具批次完成后注入；必须保持 SQLite 事件顺序、取消优先级和审批 call id 绑定。
5. **可组合的 turn hooks**：Runner 暴露 Provider 前后、Tool 前后与安全边界 hook；多个 hook 按注册顺序组合，方便后续加入策略、观测、重试或 Skills 适配，而不改主循环控制流。
6. **可恢复 Run 状态**：`provider_ready` 边界持久化完整规范化消息；进程重启后重建 WebView/Provider 调用并从该边界继续，而不是恢复中断的 TCP/SSE。进入 Tool 前会先写 `tool_in_flight`，如果进程死在可能有副作用的工具中则标记失败并要求检查，不自动重放。

前两项属于正确性与安全改进，应优先于更多自治能力。并行工具和同 Run steering 会改变事件顺序与副作用语义，必须在 Native approval、Checkpoint 和项目级互斥之上实现。
