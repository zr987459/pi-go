# r3/app 状态

- 2026-11-04 08:11 CST 已开始（基于 main@de09f66）。

## 计划
1. §8.3 第 2 步：只被 app 引用的删除准备（标记、拆引用，暂不删模块）。
2. 接线骨架：按契约 SessionHost 编写，开发期用 FakeSessionHost；Crossfade 两形态切换；形态偏好持久化；终端未合入时显示占位。
3. core/session/models/calm 合入 main 后普通 merge main，切真实实现，CI 绿后开 PR 1（接线 UI 形态）。
4. term 合入后：接线终端形态、删除旧模块（feature/usage、文件浏览器、Git 页、Share Target 等）、AppCoverageTest、FormSwitchStreamingTest、合并 THIRD_PARTY_NOTICES、docs/testing 真机清单（必要时用 r3/app-2）。

## 当前
- 读 DESIGN-r3.md §7、§8.1-8.5 与工作流派发表⑥。
