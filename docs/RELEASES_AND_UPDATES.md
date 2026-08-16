# PocketPilot 发布与应用内更新

PocketPilot 使用 GitHub Releases 分发 Android APK。App 从
`https://api.github.com/repos/string1225/PocketPilot/releases/latest` 检查最新稳定版，下载发布资产并在本机完成校验，然后交给 Android 系统安装器确认升级。

## 1. 数据不变的升级约束

PocketPilot 只接受同时满足下列条件的 APK 作为升级包：

- `applicationId` 始终为 `com.string1225.pocketpilot`；
- 新 APK 使用与已安装版本完全相同的签名证书；
- `versionCode` 严格大于已安装版本（Android 平台允许同值覆盖，但 PocketPilot 主动拒绝重放同版本）。

满足这些条件时，系统只替换应用代码和资源。PocketPilot 的 SQLite、Android Keystore 密文、私有 `files/` 下的项目与附件不会被发布流程迁移、清空或覆盖。

以下操作不属于升级，会破坏或绕开上述保障：

- 卸载旧版本后再安装；
- 执行 `adb shell pm clear com.string1225.pocketpilot`；
- 更换发布签名密钥；
- 在保存真实数据的手机上运行会卸载测试包的 instrumentation 测试。

发布密钥一旦丢失，就无法再为现有安装提供兼容升级。至少准备两份加密离线备份，并把恢复步骤和访问权限交给项目所有者。密钥、密码、Base64 文本都不得提交到 Git、Issue、Release 或构建日志。

## 2. GitHub Actions Secrets

在仓库 `Settings > Secrets and variables > Actions` 中配置：

| Secret | 内容 |
| --- | --- |
| `ANDROID_RELEASE_KEYSTORE_BASE64` | 完整 JKS/PKCS12 文件的 Base64（单行或保留换行均可） |
| `ANDROID_RELEASE_KEY_ALIAS` | 发布密钥 alias |
| `ANDROID_RELEASE_STORE_PASSWORD` | keystore 密码 |
| `ANDROID_RELEASE_KEY_PASSWORD` | 私钥密码 |

Workflow 只把 keystore 解码到 GitHub 托管 Runner 的临时目录，构建结束立即删除。仓库的 `.gitignore` 同时拒绝 `*.jks` 与 `*.keystore`，但这不能代替密钥审查。

仓库设置必须开启 GitHub **Release immutability**。它会在发布后锁定对应 tag 与资产，并生成可验证的 Release attestation；workflow 会把“Release 确实不可变且 attestation 可验证”作为发布成功的最后门禁。

首次正式发布前，必须在一台可清空的设备上确认发布 APK 的证书 SHA-256 与预期密钥一致。以后每次发布都保存证书指纹；任何非预期变化都应阻止发布。

仓库把可公开的证书 SHA-256 固定在 `apps/android/app/release-signing-certificate.sha256`。Release workflow 会同时核对该指纹、Gradle 中的 `versionCode`/`versionName`、APK 清单值，并要求 `versionCode` 严格大于上一份稳定 Release APK；私钥本身仍只存在于安全备份和 GitHub Actions Secrets。

## 3. 每轮代码变更的发布流程

每轮开发都分配新的版本：

1. 在 `apps/android/app/build.gradle.kts` 增加 `versionCode`。
2. 把 `versionName` 改成新的稳定语义版本，例如 `0.2.0`。
3. 完成代码、测试和文档，确保生成的 Runtime asset 已提交。
4. 只 stage 本轮文件，commit 并 push 到 `origin/dev`。
5. 从干净且与 `origin/dev` 完全一致的工作树运行发布脚本：

```powershell
.\scripts\release.ps1
```

脚本会再次运行 TypeScript 与 Android Debug 验证，然后创建并推送 `v<versionName>` annotated tag。`-SkipVerification` 仅用于同一 commit 已经完整验证且需要避免重复耗时的受控场景。

`.github/workflows/release.yml` 随后会：

1. 校验 tag 恰好为 `v<versionName>`，且 tag commit 已推送到 `origin/dev`；
2. 执行 `pnpm check`、Release 单元测试与 Lint；
3. 使用仓库 Secrets 构建签名 Release APK；
4. 使用 Android `apksigner` 验证 APK；
5. 生成 `pocketpilot-<version>.apk` 与 `pocketpilot-<version>.apk.sha256`；
6. 创建同 tag 的 GitHub Latest Release；若发布已存在则保持资产不可变，不执行覆盖。

不要复用已有 tag，也不要删除并重建已分发的 tag。构建失败时先修复代码、增加一个新版本并重新走完整流程；不要用不同 APK 覆盖用户已经下载的正式资产。

## 4. 应用内检查和安装

PocketPilot 启动后可以低频自动检查，设置页也提供手动检查。更新链路应遵循以下顺序：

1. 只接受官方仓库最新的非 draft、非 prerelease Release。
2. 只选择与当前 ABI 无关的固定 APK 资产名，并同时下载同名 `.sha256`。
3. 限制响应体、APK 大小、重定向次数与允许的 HTTPS 主机。
4. 校验 SHA-256、包名、`versionCode` 和 APK 签名证书；任一不符立即删除下载文件。
5. 通过 `FileProvider` 把只读 APK URI 交给 Android 系统安装器。

普通 Android 应用不能静默升级。Android 8.0+ 首次从 PocketPilot 安装更新时，用户需要在系统设置中允许该应用“安装未知应用”，每次安装仍由系统界面明确确认。PocketPilot 不请求 root，也不会卸载当前版本。

更新 APK 只保存在 cache 目录。检查、下载失败或用户取消安装都不会修改模型 AK、SSH/Git 凭据、项目、会话或 Checkpoint。

## 5. 发布验收

Release 页面必须同时出现：

```text
pocketpilot-<version>.apk
pocketpilot-<version>.apk.sha256
```

在专用测试设备上：

1. 先安装上一版本，创建项目、会话、Checkpoint，并保存一个测试模型连接。
2. 记录项目内容和设置状态，不要记录明文 AK。
3. 从 App 内检查、下载并安装新版本，全程不要卸载或清数据。
4. 重启后确认版本已更新，项目、会话、文件、Checkpoint 和凭据引用仍在。
5. 发送离线命令并进行一次可撤销的模型连接测试，确认数据库与 Keystore 均可读。

如果系统提示签名不一致，立即停止；不要通过卸载旧版来“解决”，否则会删除应用私有数据。先核对 GitHub Secret 中的 keystore 是否被替换。

## 6. 官方参考

- [Android：应用更新的包名、签名和版本条件](https://developer.android.com/google/play/app-updates)
- [Android：应用签名与密钥保管](https://developer.android.com/studio/publish/app-signing)
- [Android：REQUEST_INSTALL_PACKAGES](https://developer.android.com/reference/android/Manifest.permission#REQUEST_INSTALL_PACKAGES)
- [GitHub：Get the latest release](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)
- [GitHub CLI：gh release create](https://cli.github.com/manual/gh_release_create)
