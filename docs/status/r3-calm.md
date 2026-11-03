# r3/calm（工作流 ④ UI 形态）状态

**状态：已开始**（2026-11-04 07:3x CST，从 r3/core-base 拉出）

## 计划
1. 读 docs/DESIGN-r3.md（§4 UI、§7、§8.1 文件范围、§8.2 删除纪律、§8.5 验收、附：工作流派发表 ④）与 docs/pi-features.md。
2. 基于 FakeSessionHost 与 PiActionCatalog 实现 UI 形态：pi-features.md 全部功能均有入口；与终端形态共用同一会话/引擎，一键切换，保留草稿、不中断生成。
3. 设置仅保留模型配置；删除 pi 没有的功能（按 §8.2）；无吉祥物；极简。
4. 单测 + Compose 预览/截图测试产物；CI 绿。
5. PR #15 合入 main 后 merge main 进本分支，开 PR 到 main。

## 进度
- 分支已创建。

## 下一步
- 读设计文档，确定文件范围与验收清单。
