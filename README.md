# pi-go

pi-go 是一个原生 Android 的「pi 编程助手」客户端。它在手机上的 PRoot Ubuntu 容器里运行 [pi coding agent](https://www.npmjs.com/package/@earendil-works/pi-coding-agent)（`@earendil-works/pi-coding-agent`，锁定 1.5.0），通过 pi 的 RPC 模式（`pi --mode rpc`，stdin/stdout JSON 行）通信，聊天、工具调用、中断与排队、会话管理都用 Kotlin + Jetpack Compose 做成原生界面，不再内嵌网页。首次启动使用国内镜像安装 Ubuntu、Node.js 和 pi，并对下载包做 SHA256 校验。容器启动与安装流程改写自 PiMet，其中派生自第三方 GPL 项目的代码和二进制不复用，已改为重写或改用可追溯来源（见第三方声明）。

- 设计文档（决策记录、技术栈、模块划分、通信协议、运行环境迁移、MVP 范围与工作流拆分、风险与路线）：[docs/DESIGN.md](docs/DESIGN.md)
- 代码审查重点：[REVIEW.md](REVIEW.md)
- 第三方组件与代码来源：[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- 测试版 APK：每次推送 main 后由 GitHub Actions 构建，发布在 Releases 的「pi-go 测试版」（tag `nightly`）。

当前状态：设计已定稿；工程骨架 `mvp/scaffold` 已提交审查（PR #1），合入后 W1–W4 并行开发。许可证已选定 Apache-2.0（见下）。

## 许可证

pi-go 以 [Apache License 2.0](LICENSE) 授权（Copyright 2026 zr987459）。

第三方组件及其许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。proot 等随 APK 分发的独立可执行程序按其各自的许可证分发（如 proot 按 Termux 打包元数据为 GPL-2.0），不影响 pi-go 自身代码的许可证。
