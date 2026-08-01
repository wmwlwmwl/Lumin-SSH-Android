# 版本与发版约定（Android 独立仓库）

本仓是 **Lumin SSH 的 Android 端**，与桌面仓 **分开版本、分开发版**。

桌面仓：https://github.com/wmwlwmwl/Lumin-SSH  

---

## 1. 版本号字段

| 字段 | 位置 | 含义 |
|------|------|------|
| `versionName` | `app/build.gradle.kts` | 用户可见，如 `0.1.0` |
| `versionCode` | `app/build.gradle.kts` | 整数，**每次商店/覆盖安装必须递增** |

语义化建议（`主.次.修`）：

- **修**：bug、UI 微调、不改同步格式  
- **次**：新功能、可选兼容的同步增强  
- **主**：同步/备份格式不兼容、大改数据模型  

---

## 2. Git 标签（务必带前缀）

本仓只用：

```text
android-v0.1.0
android-v0.1.1
android-v0.2.0
```

**不要**与桌面共用裸 `v0.2.0` 而不加前缀，以免两个仓、两套产物搞混。

桌面仓建议自行使用例如：

```text
desktop-v1.1.8
# 或桌面沿用现有 v*，但在 Release 标题写清 Desktop
```

---

## 3. 发版流程（仅 Android）

1. 改 `versionName` / `versionCode`  
2. 更新 [CHANGELOG.md](CHANGELOG.md)  
3. 合并到 `main`  
4. 打标签并推送：  
   ```bash
   git tag -a android-v0.1.0 -m "Android 0.1.0"
   git push origin android-v0.1.0
   ```  
5. GitHub Release（自动，结构与桌面端一致）：
   - 标题：`Lumin SSH Android v0.1.0`（对齐桌面 `Lumin SSH Client v…`）
   - 正文两块：
     1. `## 更新日志` ← 从 [CHANGELOG.md](CHANGELOG.md) 对应版本自动抽取
     2. `## 产物下载` ← APK + `.sha256` 链接
   - 附件：`Lumin-V0.1.0-android.apk` 与 `Lumin-V0.1.0-android.apk.sha256`
   - 若动了同步，在 CHANGELOG 该版本下写「需桌面 ≥ x.y」

**不需要**同时发桌面包。

---

## 4. 和桌面版本的关系

```
桌面 1.1.8 发布  →  安卓可以仍是 0.1.0（只要同步兼容）
安卓 0.2.0 发布  →  桌面可以不动
同步格式破坏   →  两端都要发，或一端兼容旧格式并在文档写清
```

建议在双方 README 各放一张小表：

| Android | 配套桌面（同步） |
|---------|------------------|
| 0.1.x   | 与当前桌面主线互通（以实测为准） |

---

## 5. 新建 GitHub 仓库时

推荐仓库名（桌面已占用 `Lumin-SSH`）：

| 名称 | 说明 |
|------|------|
| **`Lumin-SSH-Android`** | 推荐，一眼能分端 |
| `lumin-ssh-android` | 小写亦可 |

不要用与桌面完全同名的 `Lumin-SSH` 再开一个，除非换账号/组织。

首次推送示例：

```bash
cd android
git remote add origin https://github.com/wmwlwmwl/Lumin-SSH-Android.git
git checkout -b main   # 或从现有分支整理后推 main
git push -u origin main
git push origin android-v0.1.0   # 若已打 tag
```

桌面仓 README 可加一行：

```markdown
Android 客户端：[Lumin-SSH-Android](https://github.com/wmwlwmwl/Lumin-SSH-Android)
```

---

## 7. GitHub 自动打包（Actions）

仓库已配置：

| Workflow | 触发 | 产物 |
|----------|------|------|
| `Android CI` | push / PR 到 `main` | 构建 debug，上传 Artifact |
| `Android Release` | 推送标签 `android-v*` | 构建 release APK + sha256，创建 GitHub Release（更新日志/产物下载/安装方法，与桌面一致） |

### 日常验证（CI）

推代码到 `main` 或开 PR 即可，无需手动打包。

### 发一版 APK（Release）

```bash
# 1. 改版本（二选一或都改）
echo 0.1.0 > VERSION
# 同时把 app/build.gradle.kts 里 versionCode 手动 +1

# 2. 提交
git add VERSION app/build.gradle.kts CHANGELOG.md
git commit -m "release: Android 0.1.0"
git push origin main

# 3. 打 tag 并推送 → 自动打包 + 发 Release
git tag -a android-v0.1.0 -m "Android 0.1.0"
git push origin android-v0.1.0
```

几分钟后在 GitHub → **Actions** 看进度，在 **Releases** 下载  
`Lumin-SSH-Android-0.1.0.apk`。

### 签名发布（本地 + GitHub）

#### A. 生成密钥（只需一次，务必备份）

在 `android` 目录（PowerShell）：

```powershell
mkdir keystore -Force
keytool -genkeypair -v `
  -keystore keystore/lumin-release.jks `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -alias lumin `
  -storepass 你的仓库密码 `
  -keypass 你的密钥密码 `
  -dname "CN=Lumin SSH, OU=Android, O=Lumin, L=Unknown, ST=Unknown, C=CN"
```

#### B. 本地签名配置

```powershell
copy keystore\key.properties.example keystore\key.properties
# 编辑 key.properties：填 storeFile / 密码 / alias
.\gradlew.bat :app:assembleRelease
# 产物：app\build\outputs\apk\release\app-release.apk
```

`keystore/key.properties` 与 `*.jks` **已在 .gitignore**，不要提交。

#### C. GitHub Actions 签名

仓库 **Settings → Secrets and variables → Actions** 添加：

| Secret | 内容 |
|--------|------|
| `ANDROID_KEYSTORE_BASE64` | 整份 `.jks` 的 base64 |
| `ANDROID_KEYSTORE_PASSWORD` | 与 key.properties 的 storePassword 一致 |
| `ANDROID_KEY_ALIAS` | 如 `lumin` |
| `ANDROID_KEY_PASSWORD` | 与 key.properties 的 keyPassword 一致 |

PowerShell 生成 base64 并复制到剪贴板：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("keystore\lumin-release.jks")) | Set-Clipboard
```

配置后，推送 `android-v*` 标签会打出**签名** APK 并挂到 Release。  
未配置时仍为 **unsigned** release（可 GitHub 分发；Play 商店要签名）。

**重要：** 丢失 keystore 会导致无法用同一签名更新已安装应用，请离线备份 `.jks` 与密码。

---

## 8. 当前起点

| 项 | 值 |
|----|-----|
| versionName | `0.1.5`（根目录 `VERSION`） |
| versionCode | `6` |
| 应用显示名 | Lumin SSH |
| applicationId | `com.lumin.ssh.android`（改名商店会当成新应用，勿轻易改） |
| 自动打包 | `.github/workflows/android-ci.yml` / `android-release.yml` |
