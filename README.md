# pi-go

pi-go 是一个原生 Android 的「pi 编程助手」客户端。它在手机上的 PRoot Ubuntu 容器里运行 [pi coding agent](https://www.npmjs.com/package/@earendil-works/pi-coding-agent)，通过 pi 的 RPC 模式（stdin/stdout JSON 行）通信，聊天、工具调用、中断与排队、会话管理都用 Kotlin + Jetpack Compose 做成原生界面，不再内嵌网页。容器与安装能力复用自 PiMet，首次启动使用国内镜像安装 Ubuntu、Node.js 和 pi。

- 设计文档（技术栈、模块划分、通信协议、MVP 范围与工作流拆分、后续路线）：[docs/DESIGN.md](docs/DESIGN.md)
- 代码审查重点：[REVIEW.md](REVIEW.md)
- 第三方组件与代码来源：[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- 测试版 APK：每次推送 main 后由 GitHub Actions 构建，发布在 Releases 的「pi-go 测试版」（tag `nightly`）。

当前状态：设计阶段，工程骨架（`mvp/scaffold`）尚未合入。
