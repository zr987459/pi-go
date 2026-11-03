# r3 评审投票 · 独立评审员 #2

> 评审对象：`docs/proposals/r3-A.md`、`r3-B.md`、`r3-C.md`。对照依据：`docs/pi-features.md`（pi 1.5.0 全功能清单，下称「清单」），以及用户 2026-11-04 的要求（含 06:09 的两条硬性要求）。
> 写于 2026-11-04。评审员 #2 独立完成，未参考其他评审员的投票。

## 投票

**投 C。**

| 方案 | 分数（1–10） | 一句话 |
|---|---|---|
| A | **8.5** | 最稳的工程方案：只增不删、集中删除，风险表最全。覆盖测试的数据源是另抽的 index.json，设置首页也多放了两行 |
| B | **6.5** | 架构方向正确，但逐项对照基本是「按清单覆盖」一句话带过，无法逐条核对。覆盖矩阵到最后一个工作流才写，文件范围也有重叠 |
| C | **9** | 唯一直接解析 `pi-features.md` 做覆盖测试的方案；切换验收分 JVM、仪器和真引擎三级；设置页最贴近「只保留模型」。主要短板是 r3/host 工作流过重 |

理由：两条硬性要求里，「一项不少」最容易在实现中悄悄走样。C 的 `PiFeatureCoverageTest` 直接解析清单原文，清单每一行都必须映射到动作 id 或 BASE/FORM/NA，并且禁止出现孤儿动作。清单改了、映射没跟上，测试就会失败。这是三份方案里唯一能防止「对照表本身漏行」的机制。A 的 `pi-features.index.json` 由人手从清单抽取，抽漏了不会报错。

---

## 1. 逐项评审

### 1.1 方案 A（8.5）

**优点**
- 覆盖面完整。§2 按清单 §1–§13 逐行给出「终端 / UI / 实现工作流 / 注」，所列不适用项都有理由。
- 硬性要求 ① 的验收：`PiActionId` 数量必须等于 index 条目数；`PlainCoverageTest` 按入口路径逐个走到目标面板；`TermCoverageTest` 对终端形态做同样检查。
- 硬性要求 ② 的验收是三份中最细的：
  - `FormSwitchContractTest` 用反射断言 `ChatController` 的公开 API 没有形态参数；
  - 仪器测试在 500 条 delta 的流式输出中切换 10 次，断言 startCount=1、命令日志里没有 abort/new_session/对话框 cancelled、文本完整拼接、光标和图片保留、对话框保留、列表仍贴底；
  - 真机步骤：切换前后用 `!echo $PPID` 和 `/session` 对比 PID 和 sessionFile。
- 终端还原：
  - 主题 token 的值用 node 跑 pi 主题模块的输出做夹具；
  - 页脚排版与 `footer.js` 的输出做快照比对；
  - CJK 按东亚宽度占 2 格，框线用绘制而非制表符，混排不错位；
  - 双指缩放 10–18sp。
- UI 形态：
  - 发送键三态：输入框为空时是停止，有文字时是「引导」，长按出现「排队/停止」。解决了第 1 轮 Q4「有草稿时无法停止」的问题。
  - `UiSession` 保存打开中的面板，连选择器里输入到一半的搜索词也保存。
- 删除安全：R1–R4 期间只增不删，待删的类加 `@Deprecated`，用 grep/detekt 禁止新代码引用，删除集中在 R5 按模块分提交。涉及用户数据的有迁移：回收站里的会话移回 `sessions/` 并提示用户。
- 风险表务实：
  - `/trust` 在 RPC 下的行为要实测；
  - `settings.json` 与 pi 并发写，用文件锁、只改目标键；
  - `--no-session` 只能近似实现；
  - **主动指出删除人民币显示与 2026-11-03 已拍板的决策 3 冲突，交所有者确认。** 只有 A 注意到了这一点。
- R1 的真 pi 1.5.0 桥接集成验收具体可跑：tree-nav 后 leafId 改变、label 出现在 get_tree、reload 后新模板出现在 get_commands、生成中发 label 不打断生成。

**问题**
- 设置首页放了「网络代理」和「pi 设置（settings.json）」两行，偏离「设置里只保留模型」。C 的做法更干净：pi 设置作为 pi 功能放进 ⌘ 面板。
- 覆盖数据源 `docs/pi-features.index.json` 由 R0 手工抽取，抽漏了没有任何测试能发现（见上文投票理由）。
- R0 写「在 `libs.versions.toml` 加 JetBrains Mono 字体资源」是错的。字体是 `res/font` 资源，不经 version catalog。
- R5 工作量过大：删除、接线、NOTICE、总验收都在这一条上，而且要等 R1–R4 全部合入才能开始，是关键路径上的单点。R5 还要改 `core/catalog/RealAppSettingsStore`，这个文件在 R1 期间归 R1。两者时间上先后错开，所以不会冲突，但删汇率的工作被拆成了两处（R1 改 `ModelPrefsStore`，R5 改 `RealAppSettingsStore`）。
- `feature/chat` 由 R1（非 ui）和 R3（ui）按目录分属，可以并行，但 `ChatController` 接口一变，R3 就要跟着改。建议在 R0 冻结 `ChatScreenState`/`PiActions` 接口。
- `auth print-*` 在 UI 形态加了「复制凭据给外部客户端」入口（有二次确认、锁屏验证，60 秒后清空剪贴板）。清单明确建议不做按钮；为了「一项不少」做成受保护的入口说得通，但增加了泄露面。我倾向 C 的「说明条目」。

### 1.2 方案 B（6.5）

**优点**
- 三条铁律清晰：视图是函数、命令只实现一次、用桥接扩展补 RPC 缺口。
- 高级页按 `settings.json` 的真实 schema 生成控件（开关、枚举、数字、字符串），降低写坏配置的风险。A 和 C 也有 schema 编辑器，但 B 把这条理由讲得最明白。
- `/quit` 执行前需要确认，避免误杀正在生成的会话。
- 风险取舍里提出「先交付 UI 形态（用户日常用），终端形态随后」的节奏建议。
- `FormSwitchDuringStreamTest` 的四条断言（PID 与无重启、流式写完、草稿与 `@` 引用、扩展对话框可回包）方向正确。

**问题**
- **逐项对照不可核对。** §2 各节大多是一句「逐项按 pi-features.md 表覆盖」或「全覆盖」，没有逐行写出 UI 入口路径。清单里的以下项在 B 正文中找不到具体的 UI 入口（见下文遗漏表）：输入历史、`hideThinkingBlock`、`setWidget`、`--verbose` 已加载资源、技能调用消息、离开分支时的摘要、会话选择器「显示路径」、转录搜索（UI 侧）。
- **硬性要求 ① 的验收太晚。** 覆盖矩阵 `r3-coverage.md` 和 `UiCoverageTest` 放在最后的 r3/switch 里写，前面四条工作流没有覆盖率可以追踪，缺什么到收尾才发现。
- **文件范围重叠**：
  - r3/shared 写着「`core/catalog`、`core/store` 中登录/凭据读写复用（只读调用）」，而 r3/models 独占 `core/store/src/**`；
  - r3/prune 下沉 `feature/usage` 的压缩、diff、树逻辑「给两形态」，但 r3/terminal、r3/minimal 开发期就要用这些逻辑，依赖方向没写清；
  - r3/prune 改 `THIRD_PARTY_NOTICES.md`，又说「由 scaffold 合」，归属模糊。
- r3/contract 允许「按需调整」`libs.versions.toml`、`settings.gradle.kts` 等构建文件，范围没有边界。
- `@文件` 写的是「默认注入路径（和 pi 一致），大文件给摘要+路径」。「摘要」不是 pi 的行为，应以 1.5.0 源码为准做快照测试（A 的做法）。
- 终端形态的验收没有与真实 pi 输出比对（A 比对 footer.js 快照，C 比对 80 列逐行输出），还原度无法量化。

### 1.3 方案 C（9）

**优点**
- **覆盖机制最可靠。**
  - `coverage-map.tsv` 给清单每张表的每一行一条映射：动作 id，或 BASE（底座）、FORM（形态本身）、NA（不适用）。
  - `PiFeatureCoverageTest` 直接解析 `pi-features.md` 的表格行，检查四件事：每行都有映射、动作在 `PiActions.all` 里、`ui` 与 `term` 两侧入口都不为空、没有孤儿动作。
  - `Explain` 入口必须带非空说明文字，所以「只做说明」的项也有可点击的入口，不会变成遗漏。
  - 仪器测试 `UiCoverageTest`/`TermCoverageTest` 按入口类型（⌘、常驻控件、长按）逐个点击，断言出现 `overlay:<id>`、rpc 调用记录或说明。
- **硬性要求 ② 分三级验收：**
  - JVM `SessionHostFormSwitchTest`：切换 20 次，断言 start/restart/abort 计数、同一 state 实例、分段无丢失无重复、`agent_settled` 只出现一次、草稿逐字段相等、overlay 栈不变、sessionFile 不变；
  - 仪器 A 档：不需要 Key，CI 可跑；
  - 仪器 B 档：真引擎，PID 与 sessionId 不变、不出现 Starting/Stopped，切换后追问「刚才最后一个数是几？」，回复包含 300，以此验证上下文确实保留。
- **最贴合「设置只保留模型」**：设置页只有「模型」一组，有仪器测试断言根节点只有一个分组。pi 的 `/settings` 作为 pi 功能放进 ⌘ 和 `/settings`，模型设置页底部有一行指引。
- **终端还原可量化**：`TermFormatter` 是纯函数，用真机 pi 在 80 列下的输出做逐行比对夹具（差异不超过 5%，只允许宽字符边界差异）。另有 wcwidth 表、关闭连字保证一字一格、选择器底部提示行变成可点的键帽、窄屏页脚按 pi 的截断规则处理。
- `SessionHost` 持有 draft、overlay 栈、form、promptHistory、scrollAnchor，都是形态无关的状态。扩展对话框切换期间继续计时，不会被当成取消。
- 删得更彻底且合理：空会话的建议胶囊（pi 没有）、`NetworkAndCostScreen`、`DrawerStyle`。
- 删除规则清楚：谁的范围谁删，先断引用再删文件。`settings.gradle.kts` 去掉 include 的改动单独提一个小 PR，有明确的合入顺序。

**问题**
- **r3/host 过重，是瓶颈。** 它独占 `core/contract`、`core/protocol`、`core/engine`、`core/workspace`、`feature/chat` 逻辑、`feature/commands`、新模块 `core/session`、全部 `build.gradle.kts` 和 `gradle/**`，还要实现 `PiActions.run`、桥接、`@` 搜索、导入、删除撤销。虽然有「第一次推送不超过 1 小时」的接口先行，后面仍是关键路径。建议拆出 A 式的 r0（只加文件）。
- **逻辑「复制」进 `core/session`，再由 r3/app 删除原模块**（`CompactionSummary`、`LineDiff`、`SessionTreeRows`、`Ansi`、`OutputViewer`）。这样会有一段时间存在两份代码，修 bug 可能只修一份。建议改为移动（git mv），引用方同一个 PR 改指向。
- **会话删除撤销逻辑分在两处**：r3/models 改 `FileSessionRepository`（回收站改撤销），r3/host 的 `SessionCatalog` 也做「导入和删除撤销」。需要指定由一方实现、另一方调用。
- **字体依赖跨工作流**：r3/calm 独占 `core/ui/**`，r3/term 却依赖 `core/ui/res/font` 里的 JetBrains Mono。C 写的是「仓库里已有」，需要核实；如果没有，就要由 r0 或 r3/host 先放进去。
- UI 形态的 Mermaid 定为 P2（先显示代码块加「预览」）。入口存在，不算违反硬性要求 ①，但与清单「渲染成图」不一致，需要所有者认可。
- **保留了系统分享进入（Share Target）**。pi 没有这个功能，A 删了。我倾向删；如果要保留，应标为「平台入口」并说明理由。
- 没有指出删除人民币显示与 2026-11-03 决策 3 的冲突（A 指出了）。
- 外接键盘的形态切换键：C 用 `Ctrl+Alt+T`，A 用 `Ctrl+Shift+U`。两个都要确认 pi 的 `keybindings.json` 默认表里没有占用，写成测试。

---

## 2. 遗漏表（对照清单）

记号：✔ 有明确的两种形态入口；△ 有，但含糊、只在一种形态有，或有偏差；✘ 正文里找不到。只列至少有一份方案不是 ✔ 的项。

| 清单条目 | A | B | C | 说明 |
|---|---|---|---|---|
| §1.2 `--verbose` / 页眉已加载资源（UI 侧） | ✔ | ✘ | ✔ | B 只在终端页眉提到 |
| §1.2 `--no-session` 临时会话 | △ 近似实现，已列风险 | △ 只有一句「新建菜单」 | △ 近似实现 | 三份都受 RPC 限制，可以接受；B 没写实现方式 |
| §1 `pi auth print-*` | △ 加了受保护的复制入口（偏离清单） | ✔ `!pi auth` | ✔ 说明条目 | |
| §2.2 `setWidget` / `setStatus`（UI 侧） | ✔ | ✘ | ✔ | |
| §3 `/quit` | ✔ | △ 降级到设置 › 停止引擎，终端命令要确认 | ✔ | B 的确认值得吸收，降级不必要 |
| §3 `/share`（Radius / gh 登录前置） | △ | △ | ✔ 提到 Radius | A 和 B 没写登录前置的提示流程 |
| §5 离开分支时的分支摘要 | ✔ | ✘ | ✔ | |
| §5 会话选择器「显示路径」 | ✔ | ✘ | ✔ | |
| §8 `hideThinkingBlock` | ✔ | ✘（只在通用键值页里） | ✔ | B 没写渲染层如何生效 |
| §8 `markdown.mermaid`（UI 侧渲染） | △ P2 可选 | △ 只列名称 | △ P2 | 入口都在，渲染成图都延后 |
| §9 输入历史 ↑↓ | ✔ | ✘ | ✔ | |
| §9 外接键盘读取 `keybindings.json` | ✔ | △「照 pi」，没写怎么读 | ✔ | |
| §10 双击 Esc（UI 侧） | △ 不做手势，入口在会话菜单 | △ | △ 同 A | 清单本身同意 UI 不做手势，不扣分 |
| §11 技能调用消息 | ✔ | ✘ | ✔ | |
| §11 转录搜索（UI 侧） | ✔ 会话菜单 › 搜索 | △ 只在终端 ⌕ | ✔ | |
| §12 空会话建议胶囊（pi 没有） | ✘ 没提删除 | ✘ | ✔ 删除 | 漏删 |
| §12 Share Target（pi 没有） | ✔ 删除 | ✘ 没提 | △ 保留为平台入口 | |
| §12 人民币显示与 11-03 决策冲突 | ✔ 已标出 | ✘ | ✘ | 需要所有者拍板 |

**小结**：A 和 C 对清单的覆盖基本完整，差别只在 Mermaid、临时会话这类长尾，以及删除口径上的细节。B 有 8 项以上在正文里找不到 UI 入口，必须补逐行映射。

---

## 3. 两条硬性要求的落实对比

| 要求 | A | B | C |
|---|---|---|---|
| ① UI 形态全功能入口 · 机制 | `PiCommandRegistry` + ⌘ 面板 | 共享层 + 底部面板 | `PiActions`（带 `featureRefs`）+ ⌘ 命令中心 |
| ① 可执行验收 | index.json 计数 + `PlainCoverageTest`/`TermCoverageTest` | 收尾时写 `UiCoverageTest`（testTag） | **直接解析清单原文**的 JVM 测试 + 两种形态的仪器测试 + 禁止孤儿动作 |
| ① 漏行防护 | 弱（index 靠人工抽取） | 弱（矩阵靠人工维护） | **强** |
| ② 单进程、只换渲染层 | ✔ CompositionLocal | ✔ when(form) | ✔ Crossfade + SessionHost |
| ② 草稿（文字、光标、图片、@、!） | ✔ `TextFieldValue` 放在 controller | ✔ DraftState | ✔ `SessionHost.draft` |
| ② 弹层与扩展对话框交接 | ✔ 含选择器里的半截搜索词 | ✔ | ✔ overlay 栈，计时不中断 |
| ② 可执行验收 | 反射契约 + 仪器 + 真机 PID | 仪器四条断言 | **JVM + 仪器 A 档 + 真引擎 B 档（含上下文追问）** |

---

## 4. 建议融合进最终稿的优点（以 C 为底稿）

### 4.1 来自 A

1. **工作流结构：加一个 r0。** 照 A 的 R0 `r3/base`，只新增文件，必须最先合入，范围是 settings.gradle、新模块 build 文件、contract 只追加、字体资源。把 C 中 r3/host 的「scaffold 角色」拆出来，缓解瓶颈。同时改正 A 自己的错误：字体放 `res/font`，不经 version catalog。
2. **删除纪律**（A §7.3）：
   - 待删的类先加 `@Deprecated("r3: …")`，用 grep 检查新代码不得引用；
   - 删除按模块分提交，每步跑 `assembleDebug testDebugUnitTest`；
   - PR 描述列出删除文件清单逐项核对；
   - 与 C 的「属主删除、先断引用」规则合用。
3. **用户数据迁移**：回收站里还有会话时，做一次性迁移移回 `sessions/`，并提示「已恢复 N 个会话」。
4. **人民币显示**：把 A §8.2 的冲突条目放进最终稿的「待所有者确认」。默认按用户最新的话删除；如果所有者要保留，只在会话信息页加一行「约合 ¥」。
5. **`settings.json` 并发写**：读-改-写放在与 `AuthJsonLock` 同款的文件锁里，只改目标键、保留未知键（用夹具做前后快照单测），写完 reload。
6. **R1 的真 pi 桥接集成验收四条**：
   - tree-nav 后 leafId 改变、sessionFile 不变；
   - label 出现在 get_tree；
   - reload 后新模板出现在 get_commands；
   - 生成中发 label 立即执行、不打断生成。
   放进 C 的 r3/host 验收（C 目前只有 reload 和 tree-nav 的冒烟测试）。
7. **终端还原夹具**：
   - 56 个 token 的值用 node 跑 pi 主题模块的输出做夹具；
   - 页脚与 `footer.js` 在同一组输入下做快照比对；
   - CJK 框线用绘制而非制表符。
   与 C 的 80 列逐行比对并用。
8. **发送键三态**：空输入时是停止，有文字时是「引导」，长按出现「排队/停止」（修第 1 轮 Q4）。
9. **`UiSession` 保存选择器里输入到一半的搜索词**，并入 C 的 overlay 模型。
10. **`FormSwitchContractTest`**：用反射断言控制器的公开 API 没有形态参数。断言形态切换不发出任何 RPC 命令（命令日志前后无新增）。
11. **遗留 Important 的单测**：`abortRetry` 只发 `abort_retry`、不发 `clear_queue`；切换会话或重启引擎时，排队中或发送中的文字回到输入框。
12. 服务商搜索支持拼音首字母（「qw」能找到 qwen）。
13. `/session`、`/hotkeys`、`/changelog` 的本地输出作为 `customMessage` 色块插入消息区，**不写入会话文件**。
14. 删除 Share Target（pi 没有）；`TurnStats` 只保留本轮耗时（pi loader 上的计时）。

### 4.2 来自 B

1. `/quit`（停止引擎）在生成中执行前要确认。
2. 交付节奏：先交付 UI 形态（用户日常用），终端形态随后。不影响并行开发，只影响验收和发布顺序。
3. `--api-key` 的「说明条目」写明「本 App 用 auth.json 管理凭据」。C 已有说明条目，补上这句文案即可。
4. schema 编辑器只生成四类控件（开关、枚举、数字、字符串），不提供自由文本 JSON 编辑，防止写坏配置。

### 4.3 对 C 自身的修正

1. 把 `CompactionSummary`、`LineDiff`、`SessionTreeRows`、`Ansi`、`OutputViewer` 从「复制」改为「移动」：引用方同一个 PR 改指向，避免同时存在两份代码。
2. 会话删除撤销只由 `core/store/FileSessionRepository`（r3/models）实现，`SessionCatalog`（r3/host）只调用。
3. 确认 `core/ui/res/font` 里确实有 JetBrains Mono；没有就由 r0 放入，避免 r3/term 依赖 r3/calm。
4. 外接键盘的形态切换组合键写成单测：断言不与 pi 1.5.0 默认 `keybindings.json` 冲突。
5. **CI 目前只跑 `assembleDebug`，不跑单测。** `PiFeatureCoverageTest` 不进 CI，就挡不住任何东西。最终稿应把「CI 增加 `testDebugUnitTest`，模拟器任务跑 A 档仪器测试」列为所有者事项（改 `.github/` 不在 r3 工作流范围内）。在此之前，每个 PR 描述必须贴出本地运行结果。
