# Changelog（Android）

本文件只记录 **Android 仓** 的变更。桌面变更见桌面仓。

发版时 GitHub Release 会从对应版本章节自动生成「更新日志」列表（结构与桌面端一致：更新日志 / 产物下载 / 安装方法）。

---

## [Unreleased]

### 计划

- （空）

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

[Unreleased]: https://github.com/wmwlwmwl/Lumin-SSH-Android/compare/android-v0.1.1...HEAD
[0.1.1]: https://github.com/wmwlwmwl/Lumin-SSH-Android/releases/tag/android-v0.1.1
[0.1.0]: https://github.com/wmwlwmwl/Lumin-SSH-Android/releases/tag/android-v0.1.0
