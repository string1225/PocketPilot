# PocketPilot Android 模拟器与真机测试指南

本指南覆盖本机 Android Studio 模拟器、USB 真机和 Android 11+ 无线调试。测试设备只需要连接运行 Codex 的这台 Windows 电脑；模型、Git 与 SSH 凭据都应在设备 UI 中录入，不要通过聊天、命令行参数或 `adb input text` 传递。

## 1. 电脑端准备

推荐安装 Android Studio，并在 `Tools > SDK Manager` 安装：

- Android SDK Platform 36
- Android SDK Build-Tools 36.0.0
- Android SDK Platform-Tools
- Android SDK Command-line Tools
- Android Emulator 与一个 API 36 x86_64 system image（仅模拟器需要）

PocketPilot 使用 JDK 17。可以直接复用 Android Studio 自带 JBR：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path += ";$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator"

java -version
adb version
```

如果 SDK 不在默认目录，在 `apps/android/local.properties` 写入本机路径。该文件已被 Git 忽略：

```properties
sdk.dir=C\:\\Users\\YOUR_NAME\\AppData\\Local\\Android\\Sdk
```

## 2. 构建与自动测试

在仓库根目录执行：

```powershell
git switch dev
git pull --ff-only origin dev
corepack enable
pnpm install --frozen-lockfile
pnpm check

Push-Location apps\android
.\gradlew.bat :sshj-android:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
Pop-Location
```

APK：

```text
apps\android\app\build\outputs\apk\debug\app-debug.apk
```

这个 Debug APK 只用于模拟器或可清空的开发测试机。它使用开发签名，不能覆盖 GitHub Release 的正式签名安装，也不能作为“保留数据升级到 Release”的基线。保存真实项目和凭据的日常手机应从 GitHub Release APK 开始安装，并始终沿用 Release 更新通道。

## 3. 本机模拟器

Android Studio 中打开 `Tools > Device Manager`，创建并启动 API 36 设备。当前开发环境使用的 AVD 名称是：

```text
PocketPilot_API_36
```

也可以命令行启动：

```powershell
emulator -avd PocketPilot_API_36
adb wait-for-device
adb devices -l
```

安装并运行测试：

```powershell
adb install -r .\apps\android\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.string1225.pocketpilot/.MainActivity

Push-Location apps\android
.\gradlew.bat connectedDebugAndroidTest
Pop-Location
```

`connectedDebugAndroidTest` 会由 Android Gradle Plugin 管理测试 APK 与目标 App 的安装/卸载，可能清除该安装下的 PocketPilot 数据。只在模拟器或可清空的专用测试机运行；不要在保存了真实项目和凭据的日常手机上运行。

## 4. 用 USB 连接手机

1. 手机进入“设置 > 关于手机”，连续点击“版本号”约 7 次以启用开发者选项。
2. 打开“开发者选项 > USB 调试”。部分厂商还要求开启“USB 调试（安全设置）”。
3. 使用支持数据传输的 USB 线连接电脑，USB 用途选择“文件传输”。
4. 手机保持解锁；RSA 指纹弹窗出现后，核对并允许这台电脑。
5. Windows 未识别时，安装厂商 OEM USB Driver，或使用 Android Studio 的 `Tools > Troubleshoot Device Connections`。

官方参考：[在硬件设备上运行应用](https://developer.android.com/studio/run/device) 与 [ADB](https://developer.android.com/tools/adb)。

验证连接：

```powershell
adb kill-server
adb start-server
adb devices -l
```

目标应显示为 `device`。常见异常：

- `unauthorized`：解锁手机并确认 RSA；必要时撤销 USB 调试授权后重连。
- `offline`：重插数据线并重启 ADB server。
- 无设备：换数据线/USB 口，确认“文件传输”，安装 OEM Driver。
- 多设备：后续命令添加 `-s <SERIAL>`。

安装、启动和日志：

下面的 Debug 安装命令仍只适用于可清空的开发测试机；日常手机请下载并安装 GitHub Release 页面中的 `pocketpilot-<version>.apk`。不要在已有 Release 数据的手机上用 Debug APK 覆盖或运行 instrumentation tests。

```powershell
adb install -r .\apps\android\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.string1225.pocketpilot/.MainActivity

$pocketPilotPid = (adb shell pidof com.string1225.pocketpilot).Trim()
adb logcat --pid=$pocketPilotPid
```

## 5. Android 11+ 无线调试

电脑和手机连接同一可信局域网。手机打开“开发者选项 > 无线调试 > 使用配对码配对设备”，分别记下配对端口和调试端口：

```powershell
adb pair <手机IP>:<配对端口>
adb connect <手机IP>:<调试端口>
adb devices -l
```

测试结束后建议关闭无线调试。

## 6. 首轮 UI 与持久化 Smoke Test

首次运行引导只会在全新数据上出现。请只在模拟器或可清空的专用测试机使用 `adb shell pm clear com.string1225.pocketpilot` 制造该条件；不要在保存真实项目或凭据的手机上清数据。

1. 清空测试安装的数据后启动，欢迎页应显示 PocketPilot Logo、整体架构说明、`1/5` 进度和底部“下一步”；此时不应先弹通知权限。切换“中文/English”，文案应立即切换。
2. 进入模型步骤。默认选择 OpenAI Chat API；不配置时“下一步”保持禁用。填写专用测试 Endpoint、文本模型、图片模型和 Key 后保存，弹窗应转圈等待真实文本与图片测试，两项都成功才自动关闭并允许继续。也应验证 GLM 路径不要求 Endpoint、默认文本模型为 `glm-5.3`、视觉模型固定为 `glm-5v-turbo`。
3. 项目步骤先选择“暂时不要”，确认可继续，再返回选择“是”。分别测试创建本地项目，以及在另一轮干净数据中用 HTTPS URL、可选 branch 和低权限 Token 克隆临时 Git 仓库；HTTP、URL userinfo 和错误凭据必须失败。成功后表单锁定、显示已选择的新项目，并允许继续。
4. 在项目克隆成功后从最近任务划掉 App 并重新启动，再走到项目步骤；应恢复“项目已配置”，不能要求重复克隆。项目和 Token 不得出现在日志中，Token 不得出现在 SQLite 或 `.git/config` URL。
5. SSH 步骤验证“暂时不要”可继续；再返回选择“是”，保存一台带可信 `SHA256:` 主机指纹的临时服务器。密码或私钥不应出现在 SQLite/日志中。完成页应汇总模型、当前项目和服务器数量。
6. 点击“开始使用”后才应进入 Chat 并按 Android 版本请求通知权限；允许通知。强制停止并重新启动 App，应直接进入 Chat，不再显示引导，项目、模型和服务器配置仍保留。
7. 在 Chat 上向右滑，应进入“项目与会话”；新建和切换项目时，当前项目应有明显的选中色/勾选状态。当前项目下绑定 HTTPS Git 仓库后，切到其他项目不应显示或释放前一个项目的 PAT。新建多个会话，确认卡片上下紧凑且标题和更新时间位于同一行，再点 `+` 新建会话。
8. 返回 Chat 向左滑，应进入“产出物”；创建 `src/features/demo.ts` 与 `README.md`，文件页应显示可展开/收起的目录树，而不是完整路径平铺；展开 `src`、`features` 后再编辑文件。
9. 点左上角 PocketPilot Logo，依次验证：模型、远程服务器、个性化、记忆、工具、插件、外观、语言。
10. 切换“跟随系统/亮色/暗色”和“中文/English”，返回后立即生效；强制停止并重启，设置仍保留。
11. 在不同项目和会话之间切换，确认消息与文件不会串项目。
12. 点击话筒并按提示授予麦克风权限；确认中文/English 识别结果逐步写入输入框，点击输入框、切会话或切到后台后识别立即停止。
13. 选择一到多张 JPEG/PNG/WebP 图片，确认输入区出现缩略图；发送后图片仍显示在用户消息中，模型只能通过 `image.analyze` 的附件 ID 读取。
14. 在一个任务运行中保持输入框为空时确认主按钮为“终止”；输入新消息后按钮变回“发送”，点击后显示已排队，并在当前任务结束后按 FIFO 自动发送。

离线模式可发送：

```text
/list
/create src/demo.ts | export const answer = 42;
/read src/demo.ts
/replace src/demo.ts | 42 | 43
/delete src/demo.ts
```

`/delete` 应弹出一次性审批。先拒绝确认文件保留，再允许一次确认删除成功并产生 Checkpoint。

## 7. 真实 LLM 测试

1. 左上角设置 > 模型。
2. 模型协议默认应选中“OpenAI Chat API”；Endpoint、文本模型、图片模型和 API Key（AK）输入框均显示且为空时不能保存。填写专用测试连接后点击“保存”，确认弹窗保持打开并显示“测试中…”转圈，且文本和 1x1 测试图片都成功后才关闭并保存。
3. 发送“列出当前项目文件”；确认只显示用户与 Assistant 消息，不显示 `run.started`、`run.completed` 或 Tool/Status 卡片。在同一会话继续追问，验证多轮历史。
4. 返回设置，把模型协议切换为“GLM”；Endpoint 输入框应隐藏，实际请求固定使用：
   `https://open.bigmodel.cn/api/coding/paas/v4`
5. GLM 文本模型应默认为 `glm-5.3`、图片模型固定为 `glm-5v-turbo`；API Key（AK）仍为空且必填。使用专用 GLM 测试 AK 点击“保存”，等待连接测试完成，再验证 SSE 打字机效果、图片识别、Tool calling 与多轮历史。
6. 切回 OpenAI Chat API，确认 Endpoint 输入框重新显示且必填。设置 UI 不应再显示 Responses 协议选项；Responses 仅保留为后端兼容能力。
7. 移除 Key 后再次发送 `/list`，应自动回到离线 Provider。
8. 用测试 Provider 或可控模型触发至少 12 次连续 Tool Call，确认第 9 次以后仍继续运行并最终完成；PocketPilot 不应再返回 `MAX_STEPS_EXCEEDED`，也不应在 30 分钟处由 App 主动终止 Run。

不要使用生产主 Key。建议创建可撤销、限额的测试 Key。不要截图 Key 输入框，不要把 Key 放进项目文件或 logcat。

注意：智谱的 [Coding Plan 接入说明](https://docs.bigmodel.cn/cn/coding-plan/tool/others)同时声明套餐权益仅限页面列出的受支持工具，PocketPilot 当前不在名单中。请先确认账号/套餐允许第三方客户端调用；否则选择 OpenAI Chat API 并填写你有权访问的 OpenAI-compatible Endpoint，不能仅凭 GLM 内置 Endpoint 可连接就推断套餐授权成立。

### HTTP Tool Smoke Test

1. 请求 Agent 使用 `http.request` GET `https://example.com/`，应无需审批并返回 `status=200`、`bodyEncoding=utf8` 和非空正文。
2. 请求 POST 一个专用测试 Endpoint，审批框应显示 method、最终 host/port、请求体 UTF-8 字节数、timeout 和响应上限；拒绝后不应产生网络请求。
3. `http://` 默认必须失败；只有显式 `allowInsecureHttp=true` 才进入审批，并显示 cleartext 警告。测试环境之外不建议允许。
4. `https://127.0.0.1/`、私网/链路本地/metadata 域名及 Authorization/Cookie/Host 等模型提供的请求头应被拒绝；3xx 响应不能被自动跟随。
5. 二进制或非法 UTF-8 响应应返回 `bodyEncoding=base64`；`truncated=true` 表示 Base64/文本仅代表捕获到的有界原始前缀。

## 8. Git 实接 Smoke Test

使用专门的临时仓库：

1. 公共仓库：让 Agent 在空项目执行 clone，再请求 status/diff。
2. 私有 HTTPS 仓库：右滑到“项目与会话”，选择项目，在该项目的 Git 卡片中填写 remote 名称、仓库 HTTPS URL、可选默认分支和 **Personal access token (PAT)**。GitHub 应优先使用只授权该测试仓库和必要 Contents 权限的 fine-grained PAT。
3. 请求 commit，检查审批框中的项目、消息和目标。
4. 请求 pull/push，核对审批框显示的远端主机、分支和“将发送凭据”提示后再允许。
5. 制造 non-fast-forward，确认 push 明确失败而不是显示成功。
6. 测试完立即撤销 Token，并在设置中移除本地凭据。

Checkpoint Restore 不会执行 `git reset --hard`，也不会移动 Git HEAD。

## 9. SSH 实接 Smoke Test

使用无生产权限的临时账号：

1. 从服务器管理员或可信本机命令获取 SSH 主机公钥的 OpenSSH `SHA256:` 指纹；不要从首次连接错误信息中盲目信任指纹。
2. 设置 > 远程服务器，填写名称、Host、Port、Username、认证方式、指纹和用途。
3. 录入测试密码或未加密的 PEM/OpenSSH 私钥；数据库只保存 credential id，密文由 Android Keystore 保护。当前版本尚未建模加密私钥的 passphrase。
4. 请求 Agent 执行只读命令，例如 `pwd`、`uname -a`；审批框核对 server id、主机、指纹、timeout 和完整命令。
5. 验证 stdout/stderr/exitCode；再测试非零退出、超时和大量输出上限。
6. 指纹的作用是验证远端服务器身份，不能因为已经填写密码或私钥而省略。后续如果 App 增加“扫描主机公钥”，也必须先展示算法与 SHA-256，并让用户通过服务器控制台或管理员提供的可信渠道核对后确认。
7. 测试结束删除服务器配置并撤销测试凭据。

首轮不要批准 `sudo`、删除、服务管理、Docker 清理或生产发布命令。

## 10. 后台运行与通知

1. 允许 PocketPilot 通知权限。
2. 启动一个会持续数十秒的测试会话。
3. 立即按 Home 或切换到其他 App；通知栏应出现“PocketPilot is working”。
4. 任务需要工具审批时，通知变为“needs your approval”；点击应回到对应项目/会话。
5. 完成、失败或取消后应收到结果通知；点击结果通知同样回到原会话。
6. 返回 App，时间线应完整且顺序不乱。

`adb shell am force-stop` 是用户强制停止，不等于普通切出 App；系统会终止任务和通知。普通后台测试不要执行 force-stop。

辅助检查：

```powershell
adb shell dumpsys activity services com.string1225.pocketpilot
adb shell dumpsys notification --noredact | Select-String PocketPilot
```

## 11. 应用内更新与数据保留 Smoke Test

只在使用正式发布签名、可验证数据的测试设备上执行。上一版基线必须也是 GitHub Release APK；Debug APK 与 Release APK 签名不同，无法用来验证无损覆盖升级。不要使用 `adb uninstall`、`pm clear` 或 `connectedAndroidTest`：这些操作会使“升级后数据保留”的结果失真，甚至直接删除数据。

1. 安装上一版 GitHub Release APK，创建两个项目、两个会话、一个文件和一个 Checkpoint。
2. 在设置中保存专用测试模型连接和 SSH/Git 凭据；只记录“已配置”状态，不导出或截图明文。
3. 打开“设置 > 版本更新”，检查最新 Release；确认展示版本高于当前版本。
4. 下载后断网，再点击安装；这可确认安装使用的是已校验的本地 APK，而不是浏览器页面。
5. Android 8.0+ 首次使用时，系统可能要求允许 PocketPilot“安装未知应用”。授权后返回 App，再次点击安装并在系统安装器确认更新。
6. 更新后打开 PocketPilot，确认版本号变化，项目、会话、文件、Checkpoint、模型连接和服务器配置仍然存在。
7. 运行 `/list`，再执行一次模型连接测试；确认 Workspace、SQLite 和 Android Keystore 都可正常读取。
8. 再次检查更新，应显示已经是最新版，不重复下载或尝试降级。

若系统报告“应用未安装”或签名冲突，停止测试并核对发布证书。不要卸载旧版本继续安装，因为卸载会删除应用私有文件、数据库和 Keystore 密文。详细发布与签名约束见 [发布与应用内更新](RELEASES_AND_UPDATES.md)。

## 12. 把手机交给 Codex 测试

完成三项即可：

1. 手机连接这台电脑并保持解锁。
2. `adb devices -l` 中目标状态为 `device`。
3. 在当前任务告诉 Codex：“手机已连接，可以测试”；多设备时附目标 serial。

随后 Codex 可以构建/覆盖安装 Debug APK、启动/停止 PocketPilot、抓取应用进程日志和崩溃信息。Instrumentation tests 只会在模拟器、专用测试机，或你明确同意清空 PocketPilot 数据的设备上运行；清空 App 数据、卸载、重启手机或修改系统设置属于破坏性或扩范围操作，会先说明并征得确认。
