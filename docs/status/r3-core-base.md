# r3/core-base 状态

状态：已开始（2026-11-04 06:46 CST）

## 计划（DESIGN-r3.md §8.3 第 1 步）
1. 读 DESIGN-r3.md（①段、§8.1-8.5、§7）与 pi-features.md
2. 新模块骨架 core/session、feature/term，注册到 settings.gradle
3. 只新增的契约接口 + FakeSessionHost（可编程流式 + 命令日志）
4. PiActionCatalog 第一版：pi-features.md 全部 id 占位
5. CI 改动（跑单测）
6. 单测：FakeSessionHost / PiActionCatalog
7. 开 PR 到 main，CI 绿

## 当前进度
- 已建分支，正在读设计文档

## 下一步
- 读文档后写骨架
