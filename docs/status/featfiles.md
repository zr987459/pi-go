# feat/files（W3）状态



- 状态：**已完成，PR 已开**（2026-11-03 15:40 CST）
- 已合入 origin/feat/modules（W0，最新 02bc39b）。W0 合入 main 后会 `merge origin/main`（不 rebase）。

## 结果
- core/workspace：WorkspaceFs / AttachmentProcessor / SessionExporter 的真实实现 + WorkspaceServices 装配入口
- feature/files：文件页、预览、Git 改动与 diff、附件弹层、输出查看器、导出弹层、系统分享入口、系统选择器中转
- 单测：core/workspace 30 条、feature/files 12 条，本地全过；CI 只编译（assembleDebug），不跑单测
- 仪器测试 / Compose 测试：5 条，已编译出 androidTest APK；本机无 KVM，未能运行

## 遗留
- FilesBindings.kt：app 模块尚未依赖 :core:workspace、:feature:files（app/build.gradle.kts 归 scaffold），见 PR 描述
- 返回键：模块不依赖 activity-compose，W5 需提供 LocalFilesBackHandler

## 审查第 1 轮修复（2026-11-03 16:10 CST，已完成）
- 已 merge origin/main（f1f0e02，W0 契约追加），无冲突，无需适配
- Important 4 条全修：gitDiff 只对确认未跟踪的文件兜底；分享入口只收别的应用提供的 content://；HTML 导出核对当前会话，非当前会话走 `pi --export`；仪器测试改测真实实现（ActivityScenario、content://、真实 codec、swipeUp 连续滑动、无帧即失败）
- Nit 7 条全修：状态页措辞、git 入口不扩大允许范围、非 JPEG 的 EXIF、diff 截断提示、GitParse O(n²)、空文件 diff、分享缓存清理与 inbox 限额
- 单测：core/workspace 36、feature/files 16，本地全过；CI 只编译
- 未完成：androidTest 本机（aarch64，aapt2 不可用）无法编译和运行，CI 也不跑，**尚未在任何模拟器或真机上跑过**；Share 加真实处理器加真实工作区的端到端需 W5 接线后在 app 测
