# feat/files（W3）状态

- 状态：**开发中**（2026-11-03 15:20 CST）
- 已合入 origin/feat/modules（W0 契约 749d7b2，CI 绿），merge 提交 2be0478。

## 进度
- [x] core/workspace：GuestPaths（越界/符号链接）、RootfsWorkspace（WorkspaceFs）、GitParse（porcelain v2、统一 diff）、ImagePrep + EXIF、WorkspaceAttachments（AttachmentProcessor）、WorkspaceSessionExporter（Markdown/JSONL/HTML）
- [ ] 单测（路径越界、git 夹具、图片压缩、重名序号、导出）
- [ ] feature/files 界面：文件树、预览、Git 与 diff、附件弹层、输出查看器、导出分享、Share Target
- [ ] 仪器测试与 Compose 测试（编译；本地无模拟器）
- [ ] PR

## 计划
按 docs/feature-gap.md §3.2 W3：只改 core/workspace/src/**、feature/files/src/**、docs/notices/feat-files.md、本文件。FilesBindings 需 app 依赖 :core:workspace/:feature:files（app/build.gradle.kts 归 scaffold），见 PR 描述。
