# PocketPilot Android 真机接入与测试指南

这份指南面向当前的 `0.1.0` 纵向切片。手机接入当前这台 Windows 电脑并被 `adb` 识别后，Codex 可以在同一个终端环境里继续执行构建、安装、启动、抓日志和回归测试；不需要把手机账号或屏幕控制权交给任何云服务。

## 1. 电脑端准备

### 推荐方式：Android Studio

1. 从 [Android Developers](https://developer.android.com/studio) 安装 Android Studio。
2. 打开 `Tools > SDK Manager`。
3. 在 `SDK Platforms` 安装 Android API 36。
4. 在 `SDK Tools` 安装：
   - Android SDK Build-Tools 36.0.0 或更新版本
   - Android SDK Platform-Tools（包含 `adb`）
   - Android SDK Command-line Tools
5. 在 Android Studio 中接受由 SDK Manager 展示的许可协议。

项目固定使用 AGP 9.3.0、Gradle 9.5.0 和 JDK 17。Android Studio 自带的 JBR/JDK 通常可以直接作为 Gradle JDK；官方兼容表也要求 AGP 9.3 使用 JDK 17。

确认命令可用：

```powershell
java -version
adb version
node --version
pnpm --version
```

如果 `adb` 未加入 PATH，可以在当前 PowerShell 临时加入：

```powershell
$env:Path += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools"
adb version
```

官方说明：[SDK Manager](https://developer.android.com/studio/intro/update)、[Platform Tools](https://developer.android.com/tools/releases/platform-tools)。

## 2. 构建首版 APK

在仓库根目录执行：

```powershell
git switch dev
git pull --ff-only origin dev
corepack enable
pnpm install --frozen-lockfile
pnpm check
pnpm build
```

如果 Android SDK 不在默认位置，在 `apps/android/local.properties` 写本机路径；该文件已被 Git 忽略，不要提交：

```properties
sdk.dir=C\:\\Users\\junte\\AppData\\Local\\Android\\Sdk
```

构建并运行 Android 单元测试：

```powershell
Push-Location apps\android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
Pop-Location
```

APK 路径：

```text
apps\android\app\build\outputs\apk\debug\app-debug.apk
```

也可以用 Android Studio 直接打开仓库中的 `apps\android` 目录，等待 Gradle Sync 完成。

## 3. 用 USB 连接手机

1. 手机进入“设置 > 关于手机”，连续点击“版本号”约 7 次，启用开发者选项。
2. 打开“开发者选项 > USB 调试”。部分品牌还需要开启“USB 调试（安全设置）”。
3. 使用支持数据传输的 USB 线连接电脑；USB 用途选择“文件传输”。
4. 手机弹出 RSA 指纹确认时，核对后选择“允许这台电脑进行调试”。
5. Windows 若无法识别设备，安装手机厂商 OEM USB Driver，或使用 Android Studio 的 `Tools > Troubleshoot Device Connections`。

验证：

```powershell
adb kill-server
adb start-server
adb devices -l
```

正常结果包含一行状态为 `device`：

```text
SERIAL_NUMBER    device product:... model:... transport_id:...
```

常见异常：

- `unauthorized`：保持手机解锁，重新确认 RSA 弹窗；必要时在开发者选项撤销 USB 调试授权后重连。
- `offline`：重插数据线，执行 `adb kill-server` / `adb start-server`。
- 没有设备：换数据线/USB 口、切换为文件传输、安装 OEM Driver。
- 多台设备：后续命令加 `-s <SERIAL_NUMBER>` 指定目标。

官方真机说明：[Run apps on a hardware device](https://developer.android.com/studio/run/device)。

## 4. 安装、启动与日志

只有一台设备时：

```powershell
Push-Location apps\android
.\gradlew.bat installDebug
Pop-Location
adb shell am start -n com.string1225.pocketpilot/.MainActivity
```

也可以直接安装已经构建的 APK：

```powershell
adb install -r .\apps\android\app\build\outputs\apk\debug\app-debug.apk
```

只看 PocketPilot 进程日志：

```powershell
$pocketPilotPid = (adb shell pidof com.string1225.pocketpilot).Trim()
adb logcat --pid=$pocketPilotPid
```

保存完整日志到电脑：

```powershell
adb logcat -c
adb logcat -v threadtime | Tee-Object -FilePath .\pocketpilot-logcat.txt
```

日志采集完成后按 `Ctrl+C`。日志可能包含项目文件名或测试输入，分享前先检查内容；API Key/SSH 凭据在当前版本尚未接入。

## 5. 首轮 Smoke Test

### Project 与文件

1. 首次启动应自动出现且只出现一个“个人项目”。
2. 新建 `真机测试` Project，然后进入。
3. 在 Files 中创建 `notes/hello.md`。
4. 写入 `Hello PocketPilot` 并保存。
5. 关闭 App 再打开，确认 Project、文件和内容仍在。

### TypeScript Agent Runtime

Agent 页面当前明确标注“离线演示模式”。它仍然经过真实的 TypeScript Agent Loop、Tool Registry、Native Bridge 和 Workspace Repository，只是不向外部 LLM 发请求。

依次执行：

```text
/list
/create src/demo.ts | export const answer = 42;
/read src/demo.ts
/replace src/demo.ts | 42 | 43
/read src/demo.ts
```

预期：

- 时间线依次出现 user、assistant、tool started、tool finished、run completed。
- Files 页面真实出现 `src/demo.ts`，内容最终为 `43`。
- 每次成功写操作都新增 Checkpoint。

再执行：

```text
/delete src/demo.ts
```

预期弹出一次性 Native Tool 审批框，并显示 `workspace.delete` 与目标路径：

1. 第一次点“拒绝”，确认 Run 安全失败且文件保持不变。
2. 再次执行 `/delete src/demo.ts`，点“允许一次”。
3. 确认文件被删除、Run 完成，并新增一条 Agent Checkpoint。

审批期间状态显示为 `WAITING_FOR_APPROVAL`，输入框保持锁定，仍可用“停止 Run”取消；审批结果只对当前 Tool Call 生效。

### Checkpoint 与恢复

1. 进入 Checkpoints，选择批准删除之前的 Checkpoint，阅读确认框并 Restore。
2. 回到 Files，确认 `src/demo.ts` 与当时的内容一起恢复。
3. 再修改并保存一次，确认新增 Checkpoint 能显示修改数量。
4. 再选择更早的 Checkpoint 并 Restore，确认内容回到目标快照。
5. Checkpoint 历史始终保留，并且每次 Restore 前都有安全快照，完成后也有新的恢复记录。

### 进程重启

```powershell
adb shell am force-stop com.string1225.pocketpilot
adb shell am start -n com.string1225.pocketpilot/.MainActivity
```

确认 Project、Workspace 和 Checkpoint 都仍存在。若强制停止发生在 Agent Run 中间，重启后数据库会把残留的 `RUNNING` 状态标为失败/中断，不会假装任务已完成。

## 6. Android 11+ 无线调试

电脑和手机连接同一可信局域网。手机打开“开发者选项 > 无线调试”，选择“使用配对码配对设备”，分别记下配对端口和调试端口：

```powershell
adb pair <手机IP>:<配对端口>
adb connect <手机IP>:<调试端口>
adb devices -l
```

配对成功后，安装与日志命令和 USB 相同。测试结束建议关闭无线调试。Android 11 及以上的无线调试由 ADB 官方支持，详见 [ADB 文档](https://developer.android.com/tools/adb)。

## 7. 如何把手机交给 Codex 继续测试

完成下面三项即可：

1. 手机接在运行 Codex 的这台电脑上，并保持解锁。
2. `adb devices -l` 显示目标状态为 `device`。
3. 在当前任务里告诉 Codex：“手机已连接，可以做真机测试”。如果有多台设备，同时提供目标 serial。

之后 Codex 可以在本机执行只针对该测试设备的以下操作：构建 Debug APK、安装/覆盖安装、启动/停止 PocketPilot、运行 instrumentation test、抓取 PocketPilot logcat、查看应用级崩溃信息。涉及清空 App 数据、卸载、重启手机或改系统设置等破坏性/扩大范围操作，会先明确说明并征得确认。

早期测试只使用临时 Project、测试仓库和低权限测试服务器。不要在首版 APK 中放生产 API Key、生产 Git Token 或生产 SSH 私钥。
