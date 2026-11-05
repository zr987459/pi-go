# r5/openminis 状态

- 2026-11-05 11:01 CST 已开始（分支 r5/openminis，基于 main@4e3e2b7）。

## 目标
照 OpenMinis Android 端（src/android，Kotlin/Compose）重做 pi-go 的 UI 形态界面与交互：布局、消息流/气泡、输入框、会话列表、设置与模型配置、动效。底层保留 pi --mode rpc 引擎、模型配置与 Key 热加载；pi 原生功能（命令、扩展面板、会话恢复、排队、中止等）不丢；终端形态保留可切换。借鉴代码在 THIRD_PARTY_NOTICES.md 记来源。

## 计划
1. 通读 OpenMinis src/android 的界面结构、主题、组件与动效，列对照表（OpenMinis 组件 → pi-go 现有 SessionHost/动作表）。
2. 主题与基础组件移植（颜色、字体、形状、动效规格）。
3. 消息流与气泡、工具调用卡片、流式渲染。
4. 输入框（附件、发送/停止/排队、斜杠命令补全）。
5. 会话列表/抽屉、设置与模型配置页。
6. 接入 pi 原生功能入口（命令、扩展面板、恢复、排队、中止），终端形态切换保留。
7. 测试与 CI 全绿（build、单测、模拟器、真 pi 桥），开 PR 到 main。
