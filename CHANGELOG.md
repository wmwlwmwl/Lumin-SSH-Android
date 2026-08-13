# Changelog（Android）

本文件只记录 **Android 仓** 的变更。桌面变更见桌面仓。

发版时 GitHub Release 会从对应版本章节自动生成「更新日志」列表（结构与桌面端一致：更新日志 / 产物下载 / 安装方法）。

---

## [Unreleased]

### 计划

- （空）

---

## [0.1.7] - 2026-08-14

### 修复

- 修复 SSH 密码重试时同步状态未正确更新的问题
- 修复服务器别名为空时的显示与同步
- 复制会话对话框文字颜色适配当前主题

### 变更

- 终端菜单仅在 SSH 连接成功后显示，避免未连接时误操作

---

## [0.1.6] - 2026-08-09

### 修复

- SSH shell 会话连接、关闭、写入操作统一串行化，避免连接竞态、关闭后重新挂载 channel / reader，以及并发写入交叉
- 同步时透传 PC 独有连接字段，避免覆盖终端编码等配置

### 变更

- 移除 `ai_global_settings` 同步，避免多端设置冲突

---

## [0.1.5] - 2026-08-01

### 新增

- 终端快捷键栏新增粘滞 `CTRL` 键，可通过软键盘发送 `Ctrl` 组合键

### 修复

- 终端尺寸变化时通知远端 `WINCH`，避免 `nano` 等全屏程序在输入法弹收、改字号或旋屏后显示内容丢失

---

## [0.1.4] - 2026-07-31

### 修复

- AI 全局设置同步时保留最新时间戳，避免较旧设置覆盖较新设置

---

## [0.1.3] - 2026-07-26

### 新增

- 支持旧版 `ssh-rsa` 主机密钥兼容（按服务器开关透传 `allowLegacySshRsa`）

### 修复

- SSH / 对话框内存泄漏：挂后台 detachUi；原生 Dialog 在页面 dispose 时关闭；Host Key / 同步信任确认 complete 防挂死
- 连接竞态：pageAlive + stateRef，避免孤儿 JSch / reader
- 共享 OkHttpClient；终端 postDelayed / Dialog 清理
- 后台会话最多 5 个，超限拒绝并提示

---

## [0.1.2] - 2026-07-23

### 新增

- 终端断开后按回车（或发送栏发送）自动重连，行为对齐桌面端
- 启动发现新版本时弹窗提示，结果缓存后关于页可直接下载

### 修复

- 重连成功后偶发 ConcurrentModificationException 导致会话被关掉、欢迎语打印两次
- 重连连按防抖，避免多次 retry 撞车
- 远程同步目录缺失时提示忽略 / 重试 / 重新创建并上传本地数据；空远程不再误删本地服务器

### 变更

- 同步导入与云端备份仅支持明文 JSON 与 LUMIN2；移除旧版 `.enc` 兼容，格式错误不再误弹密码框

---

## [0.1.1] - 2026-07-21

### 新增

- 服务器克隆（id 置空预填）与 host+port+username 去重
- 终端识别 http(s) 链接：下划线高亮、复制 / 打开
- 应用日志（AppLog）：开关、大小 / 轮转、分享合并 prev、清空

### 修复

- 同步：规范化连接序列化与业务比较，消除与 PC 乒乓上传
- 选择文本布局：近全宽、字号略缩、底边留白；打开在最新、不跟 live 刷新
- 未就绪 / 连接中忽略终端点击，避免误弹输入法
- 终端链接：换行 URL 完整识别；宽字符列对齐与触控命中；shell 分隔符不粘进 URL
- 连接稳定：生命周期不依赖 terminal 重组；timeout=0 / keepalive；关闭连上后远程 WINCH
- 断开提示改代码侧 `\r\n` + 红字；字号去掉错误截断
- 输入栏多行与行尾留白

### 重构

- `sendToShell` 统一发送路径；`connectionIdentityKey` 统一保存 / 导入 / 同步去重
- 删除未用状态与死代码

### 说明

- 同步字段归一后与桌面主线互通更稳；无编辑时跨端打开应 skip 上传

---

## [0.1.0] - 2026-07-19

### 新增

- SSH 终端、服务器分组、凭据 / 快捷命令 / 代理
- 深色 / 浅色 / 跟随系统
- 云同步与导入导出（WebDAV / R2 / FTP / SFTP）
- 品牌显示名：Lumin SSH；关于页双仓说明
- GitHub Actions 自动打包与 Release 说明（对齐桌面结构）

### 说明

- 首个独立仓库发版基线
- 与桌面端同步互通以实际桌面主线为准
- 本端版本与桌面分开发版

---

[Unreleased]: https://github.com/wmwlwmwl/Lumin-SSH-Android/compare/android-v0.1.7...HEAD
[0.1.7]: https://github.com/wmwlwmwl/Lumin-SSH-Android/releases/tag/android-v0.1.7
[0.1.6]: https://github.com/wmwlwmwl/Lumin-SSH-Android/releases/tag/android-v0.1.6
[0.1.5]: https://github.com/wmwlwmwl/Lumin-SSH-Android/releases/tag/android-v0.1.5
[0.1.4]: https://github.com/wmwlwmwl/Lumin-SSH-Android/releases/tag/android-v0.1.4
[0.1.3]: https://github.com/wmwlwmwl/Lumin-SSH-Android/releases/tag/android-v0.1.3
[0.1.2]: https://github.com/wmwlwmwl/Lumin-SSH-Android/releases/tag/android-v0.1.2
[0.1.1]: https://github.com/wmwlwmwl/Lumin-SSH-Android/releases/tag/android-v0.1.1
[0.1.0]: https://github.com/wmwlwmwl/Lumin-SSH-Android/releases/tag/android-v0.1.0
