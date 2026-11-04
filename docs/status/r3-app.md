# r3/app 状态

> **2026-11-04 10:56 CST 第 3 步完成，PR #21**（「r3/app：接线 UI 形态」）。b36b24e 的 CI 全绿：push 41762225237、PR 41763038142，都含模拟器 job。下一步是 PR 2（等 r3/term 合入），交接见 PR #21 描述「未完成 / 交接」和下文。
>
> 2026-11-04 10:20 CST 第 3 步已开始（新工作者）：merge main（含 #16/#17/#19/#20）→ 切真实实现 → calm 面板补 Form.actions/确认框/OAuth 重试 → 真实 SessionHostImpl 的切换测试 → CI 绿 → 开 PR 1。r3/term 未合入，终端继续占位。

- 2026-11-04 08:11 CST 已开始（基于 main@de09f66）。
- 2026-11-04 08:16 CST 第 1 步（删除准备）完成。
- 2026-11-04 08:18 CST 第 2 步（接线骨架）推送 4ad94f3。
- 2026-11-04 08:30 CST CI 绿（run 41745750846，单测 84 类 644 例 0 失败）。**阶段 1 停在绿色骨架，等依赖 PR 合入。**

## 当前状态：等待依赖
| PR | 分支 | 状态（08:30 CST） | r3/app 等它做什么 |
|---|---|---|---|
| #16 | r3/core | 未合入 | 合入后 merge main（保留它在 di/EngineBindings.kt 加的两行桥接）；PiBridge 用于 SessionOps（标签、reload） |
| #20 | r3/session | 未合入 | SessionHostImpl / PiActionsImpl / PiSettingsImpl / SessionCatalogImpl + SessionOps 由 AppGraph 注入；imageLoader、session.import、env/file/flag 设置写入在 app 接线 |
| #19 | r3/models | 未合入 | RealModelPanels 注入 core/session；设置 › 模型；它改了 app 的 WiredPages.kt NetworkPage（merge 时保留或随路由一起删） |
| #17 | r3/calm | 未合入 | FormHost 的 calm = CalmSurface(host, onOpenSettings=设置›模型)；会话列表换 CalmSessionList，删旧 PiGoDrawer / WiredPages 旧页 |
| #18 | r3/term | 未合入 | 阶段 2（PR 2）：term = TermSurface(host, …) 替换 TermPlaceholder |


## 第 3 步进度（续写，2026-11-04）
- 10:20 推 status；merge main → 522462260（无冲突，含 #16/#17/#19/#20）。
- 10:32 读完代码，确定做法（未推代码）：
  - main 上**没有 PiBridge**（在 r3/core-base），`SessionOps` 由 app 实现：abortRetry=PiEngine.abortRetry；runGuest=runtime.exec(bash -lc)；writeReport=cacheDir；newSessionInDir=engine.newSession(dir)；dequeueAll/stopEngine 用 engine.stop；setLabel/reload/treeNavigate 暂返回「需要 pigo-bridge（r3/core-base 未合入 main）」→ 交接。
  - **面板事件路由缺口**：calm 把面板内点击当 `runAction(面板id, key)`，而 ModelPanels 面板 id 与动作 id 重名（model.select / auth.login / auth.logout / provider.custom），会被 PiActionsImpl 当成「再开一次面板」。修法：calm 新增 `PanelEventSink`（owns(id) + onEvent），CalmSurface 可选参数；app 用 RealModelPanels 实现；Form.actions 按钮、表单提交（JSON）、确认框、OAuth 失败「重试」、OAuth 进度轮询、关闭时 cancel、secret 字段遮挡且不写进 values。
  - 10:50 本地已写（未推，正在编译）：feature/chat/ui/calm/CalmPanels.kt（PanelEventSink + CalmPanelLogic）、CalmSheet/CalmSurface 改动；app/di/AppSessionOps.kt、ModelPanelEvents.kt、SessionBindings.real(Deps)、AppGraph(piSettings/modelPanels/modelPanelEvents/sessionOps/sessionHost)、BindingMode.session=REAL、app/build.gradle.kts 加 :core:session、PiGoNavHost 去旧抽屉与 ChatPage。本地副本 /workspace/work/pigo/r3a/wk（换人后此目录不可见，以分支为准）。
  - 推送 e032b12：上述代码全部提交（本地 :feature:chat/:app compileDebugKotlin 通过）。正在本地跑 CalmPanelLogicTest、RealHostFormSwitchTest、FormToggleNoRpcTest，然后看 CI。
  - 10:50 已开 **PR #21**；本地 CalmPanelLogicTest 6/6、RealHostFormSwitchTest 3/3、FormToggleNoRpcTest 通过。CI：push 41762225237、PR 41763038142（b36b24e）进行中。
  - 10:56 CI 全绿 → 完成。PR 2 交接见 PR #21 描述「未完成 / 交接」。
  - （原计划）CI 绿 → 开 PR 1「r3/app：接线 UI 形态」，PR 描述写验收证据与越界改动（ui/calm 4 个文件、app/build.gradle.kts）。
  - 导航：Chat 路由 = FormHost(calm=CalmSurface)，去掉旧 ModalNavigationDrawer/PiGoDrawer/ChatPage（含 CommandPalette、SkillsScreen 引用）；设置路由 = SettingsScreen(panels, piSettings)。

## 计划
1. ✅ §8.3 第 2 步：只被 app 引用的删除准备。
2. ✅ 接线骨架（FakeSessionHost、Crossfade、形态偏好持久化、终端占位），CI 绿。
3. ⏳ core/session/models/calm 合入 main 后普通 merge main，切真实实现，CI 绿后开 PR 1（接线 UI 形态）。
4. ⏳ term 合入后：接线终端形态、删除旧模块（feature/usage、文件浏览器、Git 页、ExportSheet 等）、AppCoverageTest、FormSwitchStreamingTest、合并 THIRD_PARTY_NOTICES、docs/testing/r3-device-checklist.md（PR 1 未合则在 r3/app-2 上做）。

## 已完成
- a19fcf2：删除系统分享入口 Share Target（X10）：feature/files/share/{Share,ShareCache,ShareUriPolicy}.kt、ShareSecurityTest、清单里的 ShareTargetActivity；分享副本清理并入 FileShare.cleanStale（顺带清旧 inbox 缓存）+ FileShareCleanTest；FilesDeviceTest 去掉两条分享用例；PiGoApp 去掉为分享入口预热工作区。
  （feature/files、feature/usage 只被 app 引用，已核对：`grep -rln "feature\.files\|feature\.usage"` 只命中 app/ 下 3 个文件。）
- 6bf51d2：待删类加 `@Deprecated("r3: r3/app 删除…")`（§8.2 第 4 条）：feature/files 的 FilesHost、FilesController、GitRoots、FilesBrowserScreen、FilePreviewScreen、GitStatusScreen、DiffScreen、DiffView、ExportSheet；feature/usage 的 TurnSummary、EditToolDiff、UsageController、SessionTreeController、UsageFormat、CompactionCard、DiffView、SessionTreeContent、TurnSummaryRow、UsageSheetContent、ContextBarView。未碰 r3/session 正在移动的 LineDiff / SessionTreeRows / CompactionSummary / Ansi / OutputViewer。
- 4ad94f3：接线骨架
  - `di/SessionBindings.kt`：BindingMode.session=FAKE → FakeSessionHost（REAL 分支 notWired，待 #20）。
  - `form/FormPreference.kt`：UiForm 存 SharedPreferences（`pigo_app` / `ui_form`），启动读回（默认 CALM）、变化写回（跳过初值、去重）。
  - `form/FormHost.kt`：`Crossfade(form, tween(120))`；CALM = 现有聊天页（真实引擎），TERM = `TermPlaceholder`「终端形态尚未合入」+「⇄ UI」（testTag `form:toggle`）；`SessionHost.toggleForm()` 只改 form。
  - AppGraph：`formPrefs`、`sessionHost` 单例；抽屉加「切到终端形态」（`form:toggleFromDrawer`）。
  - 测试：FormPreferenceTest（5）、FormToggleNoRpcTest（流式中切 20 次：命令日志不变、start=1、restart=0、草稿相等、settled 一次）、FileShareCleanTest（JVM，CI 通过）；FormHostTest（仪器，CI 编译通过）。

## 越界改动
- 无（app/**、feature/files/**、feature/usage/**、docs/status 都在 §8.1 第 6 行范围内）。未改 .github。

## 下一步（精确）
1. 轮询 `GET /pulls?state=all`：#16/#20/#19/#17 任一合入 → `POST /merges {base:"r3/app", head:"main"}`（冲突则手工合并提交，双父），看 CI。
2. 四个都合入后：
   - `di/SessionBindings.real()`：`SessionHostImpl(engine=graph.engine, extras=graph.extras（同一 PiEngine）, scope=appScope, settings=PiSettingsImpl(...), sessions=SessionCatalogImpl(...), initialForm, pid=ProcessTree, actionsFactory={ PiActionsImpl(it, RealModelPanels(...), SessionOps(PiEngine+PiBridge)) })`，注入 imageLoader；BindingMode.session=REAL。
   - FormHost calm = `CalmSurface(graph.sessionHost, onOpenSettings = 设置›模型)`；删 ChatPage / 旧抽屉入口里依赖 feature/commands/ui 的引用。
   - app/build.gradle.kts 加 `implementation(project(":core:session"))`（r3/core 范围，最小越界，PR 描述列出）。
   - CI 绿 → 开 PR 1「r3/app：接线 UI 形态」。
