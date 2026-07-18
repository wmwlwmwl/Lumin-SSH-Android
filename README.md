# Lumin SSH（Android）

跨端 SSH 客户端的 **Android 移动端**。  
与桌面端数据互通（服务器、凭据、快捷命令、代理、云同步）。

| | |
|--|--|
| **产品名** | Lumin SSH |
| **本仓** | Android 客户端 |
| **桌面端** | [wmwlwmwl/Lumin-SSH](https://github.com/wmwlwmwl/Lumin-SSH) |
| **当前版本** | 见 [app/build.gradle.kts](app/build.gradle.kts) 中 `versionName` |

---

## 与桌面端的关系（两个库）

| 仓库 | 内容 | 发版节奏 |
|------|------|----------|
| [Lumin-SSH](https://github.com/wmwlwmwl/Lumin-SSH) | 桌面（Windows / macOS / Linux） | 独立 |
| **本仓** | Android | 独立 |

- **PC 更新，Android 不必更新**（反之亦然）。
- 仅当 **云同步 / 备份格式** 有破坏性变更时，需在 Release 中写明配套桌面版本（例如「需桌面 ≥ 1.x」）。
- 同步协议版本见下方「同步兼容」。

---

## 功能概览

- SSH 终端（Termux terminal-emulator）
- 服务器分组、凭据、快捷命令、代理节点
- 深色 / 浅色 / 跟随系统
- 云同步：WebDAV / R2 / FTP / SFTP（与桌面互通）
- 导入导出（含加密备份）

---

## 构建

要求：JDK 17、Android SDK（compileSdk 35）。

```bash
# Debug
./gradlew.bat :app:assembleDebug
# Linux / macOS / CI
./gradlew :app:assembleDebug

# Release（需自行配置签名）
./gradlew :app:assembleRelease
```

产物：

- Debug：`app/build/outputs/apk/debug/app-debug.apk`
- Release：`app/build/outputs/apk/release/app-release.apk`

### GitHub 自动打包

推送到 `main` 会跑 CI 编 debug。  
打标签并推送即可自动发 Release：

```bash
git tag -a android-v0.1.0 -m "Android 0.1.0"
git push origin android-v0.1.0
```

### 签名包

1. `keytool` 生成 `keystore/lumin-release.jks`（见 [VERSIONING.md](VERSIONING.md)）  
2. 复制 `keystore/key.properties.example` → `keystore/key.properties` 并填密码  
3. 本地：`./gradlew :app:assembleRelease`  
4. GitHub：把 jks 的 base64 与密码放进 Actions Secrets（同上文档）

**不要**把 `.jks` / `key.properties` 提交到 Git。

---

## 版本与发版

见 [VERSIONING.md](VERSIONING.md)。

简要：

- 用户可见版本：`versionName`（如 `0.1.0`）
- 商店递增：`versionCode`（每次上架 +1）
- Git 标签：`android-v0.1.0`（本仓专用，避免和桌面 tag 混淆）

---

## 同步兼容

| 项 | 说明 |
|----|------|
| 同步范围 | 服务器、凭据、快捷命令、代理；桌面 AI 配置透传 |
| 不含 | 图片、二进制、文件管理器设置 |
| 加密 | 可选恢复密码（与桌面一致的 `.lumin2` 等逻辑） |

破坏性变更时请同时更新：

1. 本仓 Release 说明  
2. 桌面仓 README / Release 中的「Android 配套版本」

---

## License

见 [LICENSE](LICENSE)（**Lumin SSH Android Source License 1.1**）。

| | |
|--|--|
| **可以** | 非商业使用、学习、研究、公开二开（保留许可与署名；对外发布须**源码可得**） |
| **不可以** | 商用（出售、收费分发、商业内嵌、营利服务等，定义见 LICENSE） |
| **不可以** | 仅以加密/加壳/强混淆形式对外发布且不提供对应可读源码 |

**范围：** 本许可约束本仓**原创代码**；AndroidX、Termux terminal、OkHttp、JSch 等**第三方组件仍遵守其原许可证**（不得用本许可去削弱第三方已授予的权利）。

本许可为自定义条款，**非正式法律意见**。上架商店或涉及商业边界时请自行咨询律师。
