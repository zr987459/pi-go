# mvp/integration 进度

状态：**进行中**（2026-11-03 13:1x CST）

## 已完成
- [x] 从 main（635dfdc）切出，普通 merge `origin/mvp/engine`（e82843e）→ c91ffca。
- [x] BindingMode 四组全部切为 REAL。
- [x] 导航接入真实页面：聊天 ↔ 会话（当前会话、打开/新建/删除当前会话）↔ 设置（Key 末 4 位、引擎重启、运行环境、自定义端点预填）。
- [x] 「去设置模型」：错误卡 / 失败气泡 / 未配置模型横条 → `settings?focus=models`。
- [x] Pi-Web 缺口 a–e（停止收起对话框、获取模型列表、测试连接、重试黄条、阶段提示）+ 单测。
- [x] app/src/androidTest 冒烟测试。

## 进行中
- [ ] CI 绿
- [ ] docs/testing/mvp-acceptance.md
- [ ] 推送前再 fetch 并 merge engine/main；开 PR
