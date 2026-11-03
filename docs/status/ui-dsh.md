# ui/dsh-style 状态（工作者 A：主题 + 聊天主页）

状态：**已开始**（2026-11-03 14:40 CST）

## 计划
1. 主题：把 dsh-Go 的浅色/深色配色、Typography、Shapes、渐变背景、胶囊按钮/卡片组件和吉祥物资源移植到 core/ui，单独提交（以 `theme:` 开头），并在本文件列出给 B 用的 API。
2. 聊天主页：渐变背景、顶部白色胶囊按钮、空会话问候 + 引擎状态 + “启动引擎”、吉祥物、底部圆角输入框。
3. 消息气泡、工具卡片、Markdown/代码块、提示、动画、抽屉容器。
4. CI 修到绿，补 @Preview（浅色+深色），写差距说明。

范围：core/ui/**、feature/chat/ui/**、app 的 nav/PiGoNavHost.kt、MainActivity 外观部分、app/res。不改 feature/sessions|settings|onboarding。
