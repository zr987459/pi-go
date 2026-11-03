# r3 投票 · 评审员 #1

> 独立评审，写于 2026-11-04（CST）。
> 评审对象：`docs/proposals/r3-A.md`、`r3-B.md`、`r3-C.md`。
> 对照：`docs/pi-features.md`（下称「清单」）、`REVIEW.md`、`docs/feature-gap.md`，以及 main（`247508e`，含 527708e 之后只改了 docs 的提交）的 884 个文件。
> 核对方法：逐节对照清单 §1–§13，看每条在 UI 形态是否有**具体的入口路径**（只写「全覆盖」不算），终端形态是否有呈现。方案里提到的复用、删除文件，逐个在 main 文件树里找过（结果见 §5「事实核对」）。pi 1.5.0 的能力以清单为准，这次没有重新拆 npm 包，因为三份方案在 pi 能力事实上没有互相矛盾的地方。

---

## 1. 投票

**投 C。**

一句话理由：C 是唯一把两条硬性要求都写成「解析清单原文的单测 + 可在 CI 跑的仪器测试 + 带真引擎的分档测试」的方案。它在 UI 形态逐条给出了入口，设置页做到真正「只有模型」，删除也有明确的属主和顺序。A 的完整度和 C 很接近，有几处细节比 C 好，建议融合（见 §7）。

---

## 2. 分数

| 方案 | 分数 | 简评 |
|---|---|---|
| **A** | **8.5 / 10** | 逐项覆盖最细，和 C 几乎打平。①每条清单行都写了终端、UI 两种入口和负责的工作流，连清单写「不单列」的 `-p`、`auth print` 也给了 UI 入口，后者做了锁屏验证保护。②硬性要求二的仪器测试很扎实：扩展对话框、光标位置、图片、连切 10 次、`startCount==1`、命令日志里没有 abort。③删除只在 R5 集中做，事先加 `@Deprecated`，按模块分提交，回收站里的会话做一次性迁移，删除最稳。④主动指出删人民币换算和 11-03 决策 3 冲突，交给所有者确认。扣分：(a) 覆盖测试的数据源 `pi-features.index.json` 在 R0 手工抽取，清单以后改了会和测试脱节；(b) 设置首页另有「pi 设置」「关于与外观」两行，不如 C 纯粹；(c) R1 范围太大（engine、protocol、picmd、store、catalog、workspace、chat 逻辑、commands 数据），是关键路径上的单点；(d) 两处事实小错：R0 要「加入 JetBrains Mono」，但 `core/ui/src/main/res/font/jetbrains_mono_*.ttf` 已经有了；R1 的「真 pi 桌面集成（`realpi/`）」在仓库里没有这个目录或测试框架，要先说清由谁搭；(e) 没有真引擎的自动化测试档，真引擎只靠手工验证。 |
| **B** | **5.5 / 10** | 架构方向正确（单进程、同一状态、两层渲染、桥接扩展）。删除判定和清单一致，`/quit` 降级到设置里并加确认，这个安全处理值得借鉴。问题：①§2 是按节写的概述，多次只写「逐项覆盖」「全覆盖」，没有给出 UI 入口路径。按 06:09 的硬性要求一逐条核对，有十几项落不到具体入口（见遗漏表）。②硬性要求一的验收是 `onNodeWithTag(featureId).assertExists()`。藏在菜单、面板后面的入口在主页面上并不存在，这个断言要么跑不通，要么只能测到「标签挂上了」，测不到「能走到」。③硬性要求二只在第 20 个 delta 切换一次；没测光标和多次切换；用 Fake 时「PID 不变」怎么测没写。④工作流有重叠和空白：`r3/shared` 写了「`core/store`、`core/catalog` 只读调用」，`r3/models` 却拥有并修改 `core/store`；树、会话选择器、MCP、扩展与包、pi 设置这些面板的 UI 实现，没有分给任何工作流（`r3/minimal` 只列了主界面）；`r3/prune` 删完以后要靠「空路由占位」撑到 `r3/switch`。⑤方案的基线写的是 `main@478941df`，不是 527708e。 |
| **C** | **9 / 10** | ①「一张动作表 `PiActions`」配 `featureRefs` 和 `UiEntry`/`TermEntry`。`PiFeatureCoverageTest` **直接解析 `docs/pi-features.md` 每个表格的每一行**，对照 `coverage-map.tsv`，清单增行而映射没跟上就会红，还会检查孤儿动作。清单写「两者都只做说明」的项，用 `Explain` 入口满足「每项有入口」，这是对硬性要求一最干净的解读。②硬性要求二分三级：JVM 层连切 20 次，断言 startCount、restart、abort 都正确、state 是同一实例、不丢段不重复、overlay 不变；仪器 A 档用 Fake，CI 能跑；仪器 B 档用真引擎，断言 PID 和 sessionId 不变，最后追问「刚才最后一个数是几」来验证上下文还在；每档都写了 gradle 命令。③终端形态的验收是 `TermFormatter` 输出和真 pi 在 80 列下的输出逐行比对；还写了页脚在窄屏下的截断顺序、「… N more lines (ctrl+o)」文案、选中即复制。④设置页**只有模型**，pi 的 `/settings` 作为一个动作放进 ⌘，并用仪器测试断言设置页只有一个分组，最贴合用户原话。⑤属主删除、先断引用再删、去掉 gradle include 单独提小 PR、写明合入顺序、host 第一次推送不超过 1 小时就能让三条界面工作流开工。事实核对也最准：`SuggestionText.kt`、`NetworkAndCostScreen`、`DrawerStyle.kt`、JetBrains Mono 已存在，这几处它都说对了。扣分：(a) `r3/host` 的范围同样过大（contract、protocol、engine、workspace、chat 逻辑、commands、新建 core/session、全部 build 文件），而且它前后要推三次（首推、实现、去 include），是瓶颈；(b) 系统分享入口（Share Target）pi 没有，C 却保留了，和「pi 没有就删」的口径不一致，A 删了；(c) 把 `CompactionSummary`、`LineDiff`、`SessionTreeRows`、`Ansi`、`OutputViewer` **复制**进 `core/session`，过渡期会有两份代码，要写明删旧的时间点（顺带一提：`CompactionSummary` 现在本来就有两份，`core/protocol` 和 `feature/usage/logic` 各一份）；(d) `adb shell pidof -s node` 在 PRoot 下可能拿到的是 proot 子进程，应改用 C 自己提到的 `ProcessTree`；(e) UI 形态的 Mermaid 放到 P2，属于「部分」。 |

---

## 3. 遗漏表（清单条目 × A/B/C）

记法：✓ = 两种形态都有明确入口或呈现；部分 = 有入口但不完整、写得含糊，或推迟到 P2；✗ = UI 形态没有给出入口（只写「全覆盖」或完全没提）。
「NA 说明」= 清单本身标「不适用 / 只做说明」的项，方案在 UI 里给了说明类入口，算 ✓。

| 清单条目 | A | B | C | 备注 |
|---|---|---|---|---|
| §1.1 print `-p` / `--mode json` | ✓ | ✗ | ✓ | A 给了真实入口（⌘ › 一次性提问）；C 用 `help.modes` 说明条目；B 只写「不单列」，UI 里什么都没有 |
| §1.1 `pi install/remove/update/list/config` | ✓ | ✓ | ✓ | |
| §1.1 `pi auth print-*` | ✓ | ✗ | ✓ | A 是受保护的复制入口，C 是说明条目，B 没有 UI 入口 |
| §1.1 `pi mcp …` / §7 MCP | ✓ | ✓ | ✓ | |
| §1.2 `--api-key` | ✓ | 部分 | ✓ | B 只在高级页放一行只读说明 |
| §1.2 系统提示 `SYSTEM.md` / `APPEND_SYSTEM.md` | ✓ | 部分 | ✓ | B 只写了「写文件」，没写 UI 路径 |
| §1.2 `--session-dir` / `--no-session` / `-n` | ✓ | 部分 | ✓ | B 只写了临时会话放在「新建菜单」，`sessionDir` 只能从键值编辑器改 |
| §1.2 `--models`（作用域模型） | ✓ | ✓ | ✓ | |
| §1.2 工具开关 `-t/-xt` | ✓ | ✓ | ✓ | |
| §1.2 `-e/-ne` 扩展、内置扩展 | ✓ | ✓ | ✓ | |
| §1.2 `--skill` / `--prompt-template` | ✓ | ✓ | ✓ | |
| §1.2 主题 | ✓ | ✓ | ✓ | |
| §1.2 `-nc` 上下文文件开关 | ✓ | 部分 | ✓ | B 没写开关入口 |
| §1.2 `--verbose`（页眉里已加载的资源） | ✓ | ✗ | ✓ | B 没写 UI 里在哪看已加载资源 |
| §1.2 `--tui-mode` | ✓ | — | ✓ | B 没提（不适用项，不扣分） |
| §1.2 `-a`（信任） | ✓ | ✓ | ✓ | |
| §1.2 `--offline` | ✓ | ✓ | ✓ | A 和 C 都注明要重启引擎才生效；C 把它定为「唯一允许重启引擎的路径」，并要求界面先确认 |
| §1.2 `-h/-v` | ✓ | 部分 | ✓ | |
| §2.1 33 个 RPC 命令 | ✓ | ✓ | ✓ | B 是一段概述，但每个命令都点到了 |
| §2.1 `abort_retry` 真正调用 | ✓ | 部分 | ✓ | A 特别写明不再用 `stop()` 代替 |
| §2.2 事件（含 `setWidget`、`setTitle`、`set_editor_text`） | ✓ | 部分 | ✓ | B 没写 `setTitle`、`set_editor_text` 在 UI 的落点 |
| §3 `/settings` | ✓ | ✓ | ✓ | C 放在 ⌘，不进设置页 |
| §3 `/scoped-models` | ✓ | ✓ | ✓ | |
| §3 `/login` `/logout` | ✓ | ✓ | ✓ | |
| §3 `/llama` | ✓ | ✓ | ✓ | |
| §3 `/session` | ✓ | ✓ | ✓ | |
| §3 `/tree`（原地切换 + 离开分支时的摘要提示） | ✓ | 部分 | ✓ | B 没写「是否摘要离开的分支」的提示 |
| §3 `/import` | ✓ | ✓ | ✓ | |
| §3 `/copy` | ✓ | ✓ | ✓ | |
| §3 `/export`（HTML 和 JSONL） | ✓ | 部分 | ✓ | B 只写了 HTML |
| §3 `/share` | ✓ | 部分 | ✓ | B 写了「给入口」，没写在哪 |
| §3 `/bug` | ✓ | ✗ | ✓ | B 的设置和关于里都没有 |
| §3 `/trust` | ✓ | ✓ | ✓ | |
| §3 `/reload` | ✓ | ✗ | ✓ | B 没写 UI 入口 |
| §3 `/hotkeys` `/changelog` `/debug` | ✓ | ✓ | ✓ | |
| §3 `/quit` | ✓ | ✓ | ✓ | B 降级到设置里并加确认，值得借鉴 |
| §3 模板参数、`/skill:`、扩展命令 | ✓ | ✓ | ✓ | |
| §4 工具渲染（含截断后的 `full_output_path`） | ✓ | ✓ | ✓ | |
| §4 `codemode` / `tool_search` / MCP 工具 | ✓ | ✓ | ✓ | |
| §5 树筛选（5 种） | ✓ | ✓ | ✓ | |
| §5 标签 + 显示时间（Shift+T） | ✓ | 部分 | ✓ | B 没写时间显示 |
| §5 会话选择器选项（显示路径、排序、只看已命名、重命名） | ✓ | 部分 | ✓ | B 缺「显示路径」和重命名入口 |
| §5 删除会话（5 秒撤销） | ✓ | ✓ | ✓ | 三家都和 `REVIEW.md` 冲突，见 §6 |
| §5 工作目录（显示 + 换目录） | ✓ | ✗ | ✓ | B 没写 UI 怎么换目录 |
| §6 42 个服务商、OAuth、Key 优先级、`!命令` key | ✓ | ✓ | ✓ | |
| §6 自定义服务商（`models.json`、发现、测试） | ✓ | ✓ | ✓ | |
| §6 循环模型、思考 7 档、设为默认、刷新目录 | ✓ | ✓ | ✓ | |
| §6 `thinkingBudgets` / `modelThinkingLevels` | 部分 | 部分 | ✓ | C 放在「设置 › 模型 › 高级」；A 和 B 只能通过键值编辑器改 |
| §7 上下文文件（查看、编辑全局 `AGENTS.md`） | ✓ | ✓ | ✓ | |
| §7 `keybindings.json` | ✓ | ✓ | ✓ | |
| §8 `settings.json` 全部键（按 schema 覆盖） | ✓ | ✓ | ✓ | A 用单测断言 schema 覆盖了清单 §8 的全部键；C 按组列了表；B 只是声明 |
| §8.1 环境变量 | ✓ | ✓ | ✓ | |
| §9 历史提示（↑↓ / 上滑） | ✓ | ✗ | ✓ | B 在 UI 形态没写 |
| §9 全部展开 / 折叠工具 | ✓ | 部分 | ✓ | B 只写了终端的 ⌃O |
| §9 转录搜索 / 跳到上一条、下一条提示 | ✓ | 部分 | ✓ | B 只写了终端的 ⌕，UI 形态没有搜索入口 |
| §9 外部编辑器（全屏编辑） | ✓ | 部分 | ✓ | B 只写了终端的 ⤢ |
| §10 `@文件` | ✓ | ✓ | ✓ | 三家都要求按 pi 的展开格式做；A 要求做快照测试 |
| §10 `!` / `!!` | ✓ | ✓ | ✓ | |
| §10 双击 Esc | ✓ | ✓ | ✓ | |
| §11 页眉 / 空会话信息 | ✓ | 部分 | ✓ | C 还删掉了 pi 没有的空会话建议胶囊（`SuggestionText.kt`） |
| §11 技能调用消息 | ✓ | 部分 | ✓ | |
| §11 页脚（照搬，各段可点） | ✓ | ✓ | ✓ | C 另外写了窄屏截断规则 |
| §11 Mermaid | 部分 | 部分 | 部分 | A 和 C 在 UI 形态都放 P2，B 没写 |
| §13 弹层交接（扩展对话框 id 不丢） | ✓ | ✓ | ✓ | A 还要求保留选择器里输入到一半的搜索词 |
| §13 滚动位置按 `entryId` 定位 | ✓ | ✓ | ✓ | |

**小结**：
- A 的遗漏：没有实质性遗漏。部分项是 Mermaid（UI 放 P2）和思考预算没有专门入口。
- B 的遗漏：明确 ✗ 的 7 项：`-p`/json 模式、auth print、`--verbose`、`/bug`、`/reload`、换工作目录、UI 历史提示。另有约 20 项「部分」，其中 UI 转录搜索、全部展开工具、外部编辑器只写了终端形态，扩展 `setTitle` 没写落点。按硬性要求一，**B 不达标**。
- C 的遗漏：只有 Mermaid（UI 放 P2）一项「部分」。

---

## 4. 硬性要求核验

### 硬性要求一：清单每项在 UI 形态都有入口，终端形态也完整

| | A | B | C |
|---|---|---|---|
| 覆盖机制 | `PiCommandRegistry`，⌘ 面板是总入口 | 共享层只实现一次，UI 入口靠一份覆盖矩阵 | `PiActions` 动作表，`UiEntry` 和 `TermEntry` 都不能为空 |
| 数据源 | `pi-features.index.json`，R0 手工抽取，**会和清单脱节** | `r3-coverage.md`，手写矩阵 | **直接解析 `pi-features.md` 的表格行**，加 `coverage-map.tsv` |
| 自动化验收 | `PlainCoverageTest`：沿着注册的路径走到目标面板；另有 `TermCoverageTest` | `UiCoverageTest`：`assertExists(tag)`，**只证明标签存在，证明不了能走到** | JVM 层 `PiFeatureCoverageTest`（缺映射或有孤儿动作都失败），仪器层 `UiCoverageTest` 和 `TermCoverageTest`（走到 overlay、rpc 调用或 explain），附 gradle 命令 |
| 结论 | **达标**（数据源有漂移风险） | **不达标**（入口不全，测试也弱） | **达标，最强** |

### 硬性要求二：同一会话、同一引擎，切换不丢上下文、不重启引擎、不中断生成、保留草稿、一键切换

| 检查点 | A | B | C |
|---|---|---|---|
| 单进程、只换渲染层 | ✓（形态值放在 `CompositionLocal`，`ChatController` 不知道当前形态，用反射断言） | ✓ | ✓（`SessionHost` 和进程同寿，用 `Crossfade(form)` 切换） |
| 草稿含光标、附件、`@`、`!` | ✓（`TextFieldValue`） | ✓（没写光标） | ✓（`DraftState`，含选区） |
| 扩展对话框不丢 | ✓（测试里覆盖了） | ✓（测试里覆盖了） | ✓（`OverlayStack`，测试里覆盖了） |
| 一键入口 | ✓（按键条、页脚、顶栏、`/ui` `/term`、Ctrl+Shift+U） | ✓ | ✓（Ctrl+Alt+T 等） |
| 自动化测试强度 | 仪器测试：500 条 delta，连切 10 次，断言 startCount、没有 abort 或 switch 命令、文本完整、光标和图片还在、对话框回复用同一个 id、仍贴底 | 仪器测试：第 20 个 delta 切 1 次，四条断言 | JVM 层连切 20 次；仪器 A 档（CI 可跑）；仪器 B 档用**真引擎**，断言 PID 和 sessionId 不变，并追问来验证上下文 |
| 真机验证 | 手工：`!echo $PPID`、`/session` | 手工脚本 | 自动 B 档 + 手工 `pidof`（建议改用 `ProcessTree`） |
| 结论 | **达标** | **基本达标，但测试偏弱** | **达标，最强** |

---

## 5. 删除清单评估

| 项 | pi 有吗 | A | B | C | 评价 |
|---|---|---|---|---|---|
| 吉祥物残留 | 没有 | 删 + grep | 删 + grep | 删 + grep | 三家一致；main 文件树里确实没有 mascot 相关文件 |
| 汇率、人民币 | 没有 | 删（**指出和决策 3 冲突，要所有者确认**） | 删 | 删（`NetworkAndCostScreen` 确实存在于 `CustomAndSettingsScreens.kt:204`） | A 的风险提示最到位 |
| 文件浏览器、预览、Git 页 | 没有 | 删（`Highlight` 先迁移再删） | 删 | 删 | 一致；A 保留了高亮器给代码块用，C 改为输出 `syntax*` token，两种做法都对 |
| 自动起名 `AutoNamer`/`AutoTitle` | 没有 | 删 | 删 | 删 | 两个文件都在 `feature/commands/data/` 下，确实存在 |
| 用量页、每轮统计卡 | 部分 | 改造成 `/session` + 页脚 | 改造 | 整个 `feature/usage` 删掉，逻辑先迁走 | 都正确，`/session` 都保留了 |
| HTML 导出 | **有** | 保留 | 保留 | 保留 | 三家都没有误删 ✓ |
| 回收站 | 没有 | 改成 5 秒撤销 + 迁移已有会话 | 改成 5 秒撤销 | 改成 5 秒撤销 | **三家都没提要同步改 `REVIEW.md`**：它把「删除会话没进回收目录」列为 Important。不改的话后续审查会误报，需要所有者在 `REVIEW.md` 和 `DESIGN.md` 里同步口径。A 的迁移方案最完整 |
| 系统分享入口（Share Target） | 没有 | **删** | 没提（保留了 share） | **保留** | A 更符合「pi 没有就删」；C 把它算作平台入口，可以接受，但应写明理由 |
| 空会话建议胶囊 `SuggestionText` | 没有 | 没提 | 没提 | **删** | 只有 C 发现了（文件确实存在） |
| dsh 视觉组件 | 不适用 | 删 | 删 | 删（包括 `DrawerStyle.kt`；`dsh_icons.ttf` 也应一起删，三家都没点名） | |
| 「本轮耗时」 | 有（loader 计时） | **保留** | 没提 | 没提 | A 的判断最细 |
| 草稿持久化、ROM 和后台保活引导、前台服务、代理、测试连接 | 宿主必需或部分有 | 保留 | 保留 | 保留 | 三家都没有误删 |

**没有方案误删 pi 原生功能。** B 的问题在于 UI 形态里漏了入口，不在于删错了东西。

---

## 6. 工作流评估

| 维度 | A（R0→R1‖R2‖R3‖R4→R5） | B（contract→5 条并行→switch） | C（host 首推→models‖calm‖term→host 实现→app→host 去 include） |
|---|---|---|---|
| 文件互不重叠 | ✓ 按目录切分，比如 `feature/chat` 分成 ui 和非 ui 两部分，`feature/commands` 也分 ui 和数据层 | ✗ `core/store` 和 `core/catalog` 归属不清（shared 写「只读调用」，models 却在修改）；各个面板的 UI 没有归属 | ✓ 每个文件只有一个属主，表里写得很清楚 |
| 能否并行 | R0 只新增文件，R1–R4 依赖 Fake 就能开工 | 用 Fake 能开工 | host 首推不超过 1 小时就提供 Fake 和全部 id，三条界面线随即开工；覆盖测试从第一天就能用来追踪进度 |
| 删除是否安全 | **最稳**：R1–R4 只增不删，旧类加 `@Deprecated` 并用 grep 检查，R5 按模块分提交、出红就回退、删除清单逐个文件写进 PR | 删除集中在 prune，但删完要靠空路由占位撑到最后 | 属主删除、先断引用再删、gradle include 单独提小 PR、`merge`（不 rebase、不 force） |
| 风险控制 | 风险表很全（桥接扩展先做最小实测、settings.json 并发写加锁、汇率冲突） | 风险表简略 | 风险表完整，桥接失败时退化为重启引擎 |
| 复用 | 完整列出：protocol、engine、W2、W3、W4、Ansi、LineDiff、PendingModel 等 | 列出了，但较粗 | 完整列出；用复制的方式迁移逻辑，过渡期会有重复代码 |
| 瓶颈 | R1 太大；R5 删除量大 | 职责边界不清 | host 太大，而且要推三次 |
| 每条工作流的验收 | 每条都有单测、Compose 测试和覆盖测试 | 每条一行，偏弱 | 每条都有可执行的门槛（逐行比对真 pi 输出、设置页只有一个分组、grep 不到 cny 等） |

---

## 7. 建议融合点（以 C 为主体，吸收 A 和 B）

**从 A 吸收：**
1. **A §7.3 的删除纪律**：R1–R4 期间只增不删，旧类先加 `@Deprecated("r3: R5 删除")`，用 grep 或 detekt 禁止新引用；删除按模块分提交，每步跑 `assembleDebug testDebugUnitTest`；PR 里逐个列出被删文件。可以叠加在 C 的「属主删除」之上。
2. **A §7.3 第 4 点的回收站迁移**：把回收站里现有的会话一次性移回 `sessions/`，并提示用户恢复了几个。同时请所有者更新 `REVIEW.md` 里关于回收目录的 Important 条款（三家都漏了这一点）。
3. **A §8.2 的汇率风险项**：明确交给所有者确认，因为它和 11-03 的决策 3 冲突。
4. **A §5.3 的 `UiSession.openPanel` 要带上选择器里输入到一半的搜索词**：并入 C 的 `OverlayStack` 模型。
5. **A §4.3 的发送键三态**：输入框为空时是 ■ 停止，有文字时是「引导」，长按出现「排队 / 停止」。这能修好第 1 轮 Q4「有草稿时停不下来」的问题。另外，⌘ 面板每一项都显示 pi 的原命令名（「分支树 /tree」）。
6. **A §2.10 和 R1 验收第 3 条的 `PiSettingsSchema` 单测**：断言 schema 覆盖了清单 §8 的全部键，并做 `settings.json` 前后快照（只改目标键、保留未知键）；读-改-写放在和 `AuthJsonLock` 同款的文件锁里。
7. **A R1 验收第 5、6 条**：`abortRetry` 只发 `abort_retry`；切换会话或重启引擎时，排队中的文字回到输入框（第 2 轮遗留的 Important）。
8. **A 的终端验收**：页脚排版的快照和 pi `footer.js` 的输出一致；56 个 token 的期望值取自在 node 里运行 pi 主题模块的输出。和 C 的 80 列逐行比对互相补充。
9. **A 的取舍**：删除 Share Target、保留「本轮耗时」。

**从 B 吸收：**
1. **B ★ 对 `/quit` 的调整**：UI 形态的停止引擎入口放到设置或关于里，并且执行前必须确认；终端形态的 `/quit` 也要先确认，避免误杀正在进行的生成。
2. **B §8 风险 1 的交付顺序**：先交付 UI 形态（用户日常用），终端形态随后。可以写进 C 的合入顺序里，作为发布节奏的建议，并不改变并行开发。

**C 自身要修的地方：**
- 把 `r3/host` 拆开：首推（契约和 Fake）、`core/session`（动作表和设置）、engine 加桥接，最好由两个人或两条子线完成，减轻瓶颈。
- 写明复制进 `core/session` 的旧逻辑什么时候删（随 r3/app 删除 `feature/usage` 时一起删），以及 `core/protocol/CompactionSummary.kt` 和 `feature/usage/logic/CompactionSummary.kt` 最后保留哪一份。
- 真机 PID 改用 `ProcessTree` 读取，不用 `pidof node`。
- 删除 `core/ui/src/main/res/font/dsh_icons.ttf`（dsh 视觉的一部分）。

---

*评审员 #1 · 2026-11-04*
