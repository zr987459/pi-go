# feat/files（W3）状态

- 状态：**开发中**（2026-11-03 15:45 CST）
- 已合入 origin/feat/modules（W0 契约 749d7b2，CI 绿），merge 提交 2be0478。

## 进度
- [x] core/workspace：GuestPaths（越界/符号链接/link2symlink）、RootfsWorkspace（WorkspaceFs）、GitParse（porcelain v2、统一 diff）、ImagePrep + EXIF、WorkspaceAttachments（AttachmentProcessor）、WorkspaceSessionExporter（Markdown/JSONL/HTML）、WorkspaceServices（装配入口）
- [x] 单测：core/workspace 30 条、feature/files 12 条，本地全过
- [x] feature/files 界面：文件页、预览（代码高亮/Markdown/图片/外部打开）、Git 状态与 diff、附件弹层、输出查看器、导出、Share Target、系统选择器中转
- [ ] 仪器测试与 Compose 测试（编译；本地无模拟器）
- [ ] PR
