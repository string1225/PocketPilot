# PocketPilot 工具、插件与 SSH 配置

本文说明 `http.request`、沙箱脚本、插件包和 SSH 凭据的当前边界。所有工具仍由 Agent Loop 调用；Native 层会再次校验参数、Run/Project 归属和需要的用户审批，不能把模型输出直接当成已授权操作。

## `http.request`

支持 `GET`、`HEAD`、`POST`、`PUT`、`PATCH` 和 `DELETE`。请求包含以下字段：

```json
{
  "method": "GET",
  "url": "https://example.com/api/health",
  "headers": {
    "Accept": "application/json"
  },
  "timeoutMillis": 30000,
  "maxResponseBytes": 262144,
  "allowInsecureHttp": false
}
```

- HTTPS `GET`/`HEAD` 默认可自动执行；写方法以及任何明文 HTTP 请求都必须逐次审批。
- 审批会展示完整规范化 URL、允许的请求头、正文大小与 SHA-256，以及安全转义后的正文预览；条件请求头也属于本次一次性授权范围。
- 只连接 DNS 全部解析为公网单播地址的主机。IP literal、localhost、内网、链路本地、CGNAT、文档/测试网段和云元数据目标会被拒绝。
- 不跟随重定向、不使用系统代理、不保存 Cookie，也不允许 `Authorization`、`Cookie` 等敏感请求头。需要认证的第三方 API 应以后续的“已注册 Endpoint + 绑定凭据”能力接入，不能让模型任意选择凭据和目标。
- 请求体、响应体、响应头和超时都有硬上限。文本响应标记为 UTF-8；非 UTF-8 响应以 Base64 返回，避免二进制损坏。

## `execute_js` 与 `execute_ts`

脚本是一个异步函数体，可以读取 JSON 变量 `input`、使用有界 `console`，并返回 JSON 值：

```json
{
  "source": "const values: number[] = input.values; console.log('count', values.length); return { sum: values.reduce((a, b) => a + b, 0) };",
  "input": {
    "values": [1, 2, 3]
  },
  "timeoutMillis": 5000
}
```

每次调用都创建一个一次性 Web Worker。Worker 没有 DOM、Native Bridge、Workspace、凭据、网络、文件系统、动态 import、`eval` 或 `Function`；完成、取消或超时后立即终止。源码、输入、输出、控制台条数/字节和执行时长都有上限，同一 Runtime 最多并发 4 个 Worker。

这是“能力隔离 + 独立 Worker”的轻量沙箱，不是用于运行不受信任原生程序的虚拟机。恶意脚本可能让自己的 Worker 或 WebView Renderer 因内存压力退出，但不应获得 Android Native 权限；Runtime 会把 Renderer 退出报告为 Run 失败。

## 本地插件包

当前插件是用户主动导入的本地 JSON bundle。插件只允许声明 `risk: "read"` 的纯计算工具；不能调用 HTTP、SSH、Git、Workspace、模型、Native Bridge 或凭据。安装前显示名称、版本、工具清单、源码大小和 SHA-256；安装后默认停用，启用时再次确认权限。

设置页既可通过 Android 系统文件选择器读取 `.json`，也可直接粘贴 JSON。文件与粘贴内容使用同一套校验：bundle 最多 384 KiB、manifest 最多 64 KiB、单插件源码最多 120 KiB；最多安装 32 个插件，同时启用的插件最多暴露 64 个工具且源码合计不超过 512 KiB。ID、SemVer、重复工具、SHA-256 和严格 JSON Schema 都会在写盘前验证。

可直接导入仓库中的 [PLUGIN_EXAMPLE.json](PLUGIN_EXAMPLE.json) 验证完整流程。bundle 结构：

```json
{
  "manifest": {
    "manifestVersion": 1,
    "id": "com.example.plugin",
    "name": "Example",
    "version": "1.0.0",
    "description": "Pure computation example",
    "sourceSha256": "<lowercase SHA-256 of the exact UTF-8 source string>",
    "tools": [
      {
        "name": "echo",
        "description": "Return the supplied text.",
        "risk": "read",
        "inputSchema": {
          "type": "object",
          "properties": {
            "text": { "type": "string", "maxLength": 1000 }
          },
          "required": ["text"],
          "additionalProperties": false
        }
      }
    ]
  },
  "source": "async (toolName, input) => ({ text: input.text })"
}
```

源码必须计算为 `(toolName, input) => JSON value` 形式的函数。工具会注册成 `plugin.<plugin-id>.<tool-name>`，在与 `execute_js`/`execute_ts` 相同的一次性 Worker 沙箱中执行。

当前 Schema 是有界安全子集：支持 object/array/string/number/integer/boolean/null、required、enum、字符串长度和数值范围；所有 object（包括嵌套 object）必须设置 `additionalProperties: false`。本版明确拒绝正则 `pattern` 和未知关键字，避免“清单声明了约束但执行器没有落实”以及主线程正则拒绝服务问题。

安装包以“插件 ID + 源码哈希 + 规范化清单哈希”内容寻址写入应用私有目录；即使源码不变，版本或工具清单变化也会生成独立包。完成文件同步和原子目录切换后，SQLite v4 才切换元数据引用并清理旧包。每次 Agent Run 载入启用插件前会重新读取并校验源码与清单；Service 层以同一个原子门协调 Run 启动和插件变更，活动 Run 期间的安装、停用、启用或卸载会直接拒绝。

当前版本没有插件签名、在线市场、依赖下载或 Native 扩展。SHA-256 用于检测安装后内容被篡改，不代表作者可信；只导入你已审查、来源可信的 bundle。

## SSH：密码登录

在“设置 > 远程服务器 > 添加服务器”填写：

1. 服务器名称、IP/域名和端口。
2. 登录用户名，认证方式选择“密码”。
3. 从服务器管理员或服务器控制台核对服务器 Host Key 的 `SHA256:` 指纹。
4. 输入密码并保存。密码只进入 Android Keystore 保护的加密凭据存储；数据库只保存不含秘密的引用。

每次 `ssh.execute` 都会显示服务器、用户名、命令、超时和输出上限，用户批准后才连接。远端命令的权限就是该 SSH 用户本身的权限，因此日常使用应创建低权限专用账号，不建议使用 `root`。

## SSH：私钥登录

先在可信电脑生成用户登录密钥：

```powershell
ssh-keygen -t ed25519 -a 64 -f $env:USERPROFILE\.ssh\pocketpilot_ed25519
```

把 `pocketpilot_ed25519.pub` 的公钥加入服务器目标用户的 `~/.ssh/authorized_keys`。`.pub` 是应放到服务器上的公钥，不是导入手机的文件。

从服务器控制台核对服务器主机公钥指纹：

```bash
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

然后在 PocketPilot：

1. 认证方式选择“私钥”。
2. 点击“从文件导入私钥”，选择不带 `.pub` 的私钥文件。
3. 填写上一步独立核对的服务器 Host Key 指纹并保存。

“用户登录私钥”和“服务器 Host Key 指纹”解决的是两个方向的身份验证，不能互换。当前 UI 支持未加密 OpenSSH/PEM 私钥；加密私钥及独立 passphrase 存储属于后续增强。在该能力完成前，应只为 PocketPilot 创建独立、低权限、可随时吊销的密钥，不要复用个人主密钥。

## 建议回归任务

配置好模型后，可以分别要求 Agent：

- “必须调用 `execute_ts` 计算 1 到 100 的平方和，并报告工具返回值。”
- “必须调用 `http.request` GET `https://example.com/`，报告状态码和响应编码。”
- 导入并启用示例插件后：“必须调用 `plugin.com.pocketpilot.text.text_stats` 统计一段文本。”
- “必须在我配置的测试服务器调用 `ssh.execute` 执行只读命令 `id -u && uname -s`。”

审批框中的目标或命令与预期不一致时应拒绝，不要因为请求来自模型就默认信任。
