# mvp/integration 进度

> **审查第 2 轮修复：已推送，等待 CI**（2026-11-03 CST，普通 merge origin/mvp/engine 8d811e2，随后 mvp/engine 已合入 main，再普通 merge origin/main c211cd1）：Important（预热与抢先发送竞态：WarmUpGate 闸门 + openSession 前复核）、Nit（空串运行标记不打开旧会话；改地址/Key 清空旧模型列表）均已修复并补单测；修法见 PR #7 描述「审查第 2 轮修复」。PiEngine 侧「仅在未启动时以目标会话 boot」需要 engine 处理。

> **审查第 1 轮修复：已推送，等待 CI**（2026-11-03 CST，基于 a4f6ecc）：Important 1–5 全部修复（Key 不进 argv、模型获取取消竞态、多次重试黄条、L5 冷启动回到上次会话、打开/新建会话失败提示），Nit 6–10 全部处理；修法与测试见 PR #7 描述「审查第 1 轮修复」。

状态：**代码与文档已交付，等待真机验收**（2026-11-03 13:2x CST）

## 已完成
- [x] 从 main（635dfdc）切出，普通 merge `origin/mvp/engine`（e82843e）。推送前再次 fetch：engine 未合入 main 且无新提交，无需再 merge。
- [x] BindingMode 四组全部切为 REAL。
- [x] 导航接入真实页面：聊天 ↔ 会话 ↔ 设置；自定义端点预填经 nav 传入设置页。
- [x] 「去设置模型」：错误卡 / 失败气泡 / 未配置模型横条 → `settings?focus=models`。
- [x] Pi-Web 缺口 a–e + 单测；THIRD_PARTY_NOTICES 记录来源。
- [x] app/src/androidTest 冒烟测试（CI 只编译）。
- [x] docs/testing/mvp-acceptance.md（§5 全表逐条标注）。
- [x] CI（Android debug build）绿；本地单测 318 个，1 个失败为 pre-existing（见验收文档 R7），9 个跳过（需真 pi）。

## 未完成（见验收文档「遗留问题」）
- 真机验收 §5 全表；G7 通知；L3 重命名入口；G8 大会话名称。
- 不打 v0.1.0。
