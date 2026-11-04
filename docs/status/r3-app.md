# r3/app 状态

> **2026-11-04 11:33 CST 完成，PR #21（第 2 轮：接真实 pigo-bridge）。** 代码提交 a02dd60 的 CI 全绿：push run 41766629007（build + 仪器测试（模拟器））、PR run 41766636900、真 pi 桥接集成测试 41766628966 / 41766636904。单测 122 类 831 例 0 失败（含 AppSessionOpsTest 10、AppFileSearchTest 3、RealHostSessionOpsTest 1、PiEngineTest 46）。SessionOps 不再有任何桩；逐项证据见 PR #21 描述「第 2 轮」。本文件这次更新后的最终 head 的 CI 结果也记在 PR 描述里。下一步仍是 PR 2（等 r3/term 合入）。
>
> 2026-11-04 11:12 CST 续做开始（PR #21 第 2 轮：接真实 pigo-bridge）**。r3/core 已经由 PR #22 合入 main（d15a637）。计划：
> 1. 普通 merge main → r3/app（保留 di/EngineBindings.kt 两行桥接）。
> 2. AppSessionOps 去掉「需要 pigo-bridge」桩：标签 / reload / 树内原地切换 / 取回排队 / 停止引擎 / abortRetry 走 PiBridge / PiEngine；@文件搜索注入 core/workspace FileSearch；每项补测试（FakePiBridge）。
> 3. 核对单引擎共享；FormToggleNoRpcTest、RealHostFormSwitchTest 仍绿。
> 4. CI 全绿（含模拟器 job）→ 更新 PR #21 描述 → 本文件写「完成，PR #21」。
>
> 进度：
> - 11:12 merge main → **b00b81c**（POST /merges，无冲突；di/EngineBindings.kt 两行桥接 bridgeSource / enginePidResolver 保留）。
> - 11:20 本地已写（未推，正在本地 kotlinc 编译）：AppSessionOps 全部改走 PiBridge / PiEngine（构造参数改窄接口 + forEngine 工厂）；
>   PiEngine 新增 clearQueue()（只 clear_queue 不 abort）、stopProcess()（/quit：结束进程、可按需再起）、cwd；
>   AppFileSearch（core/workspace FileSearch，根 = 会话 cwd）→ CalmSurface 新参数 fileSearch → @ 面板候选；
>   测试 AppSessionOpsTest、RealHostSessionOpsTest、AppFileSearchTest、PiEngineTest 两例。
> - 11:27 本地 kotlinc：PiEngineTest 46/46（含新增 clearQueueWithdrawsQueuedTextWithoutAborting、stopProcessEndsPiWithoutRecoveryAndNextSendRestartsWithSameSession）、PiEngineBridgeTest 18/18、AppSessionOpsTest 10/10、AppFileSearchTest 3/3。推送代码，等 CI。
>   RealHostSessionOpsTest（依赖 Android 的 SessionBindings）只能靠 CI。

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

---
## 审查第 1 轮修复（fix1）— 11:57 CST 已开始
直接在 r3/app 上修（普通 merge main，不 rebase、不 force）。审查：PR #21 issuecomment-6622088667。
计划（每条加测试）：
1. Important 1：“＋ › 图片”接系统图片选择器 → imageLoader → 随消息发送，两形态共用附件状态。
2. Important 2：“网络代理”并入 设置›模型 页（查看/修改/关闭），删旧代理页与 NetworkAndCostScreen。
3. Important 3：返回键依次关 ⌘/面板/会话列表，都关着退到后台；终端同理。
4. Important 4：OAuth 轮询不覆盖输入中的表单；NeedCode 显示授权链接；PKCE UI 形态测试。
5. Nit 1–4：删除确认关编辑表单；模型行长按›设为默认；运行环境修复/导出 HTML/关于 入口；终端未合入时回落 UI。
下一步：读审查评论与代码。

### 12:10 CST 进度
- 已改（推送中）：Important 4（CalmPanelLogic 只轮询 OAuth 文字面板；同 id 表单刷新保留已填值 keepTyped；NeedCode 表单字段说明带授权链接 + 「打开授权链接」按钮；RealModelPanels 记住 OpenUrl 链接——feature/models 最小改动）；Nit 1（确认框由 apply 统一关，Done 连同父面板一起关）；Nit 2（面板行长按 › 键帽菜单，如「设为默认」，不切当前选择）。
- 下一步：Important 1（attach.image → 系统相册）、Important 3（BackHandler）、Important 2（代理并入 设置›模型）、Nit 3/4，然后补测试。

### 12:08 CST 进度（上一节标题“12:10”实为 11:59）
- 推送：Important 1（CalmSurface.onAttachImage → 系统照片选择器 → ImageAttach.add 进 host.draft.images）、Important 2（设置 › 模型页末尾 ModelsPageExtras：网络代理查看/修改/关闭、修复运行环境、关于；删除 NetworkPage、Routes.NETWORK、hub 行、NetworkAndCostScreen）、Important 3（CalmBack 顺序 + CalmSurface.backHandler 注入；FormHost.onBackground → moveTaskToBack）、Nit 3（运行环境修复、关于入口；导出在会话菜单 ⋯）、Nit 4（FormPersistence.TERM_WIRED=false 时 TERM 回落 CALM）。
- JVM 测试：CalmAppFix1Test、ImageAttachTest、ProxyLogicTest、CalmPkceLoginTest、FormPreferenceTest 更新。
- 下一步：等 CI 编译；补仪器测试 AppCoverageTest（返回键、＋›图片、长按设为默认）。

### 12:24 CST — 完成，PR #21
- 更正：上两节标题“12:10”“12:08”实为 11:59、12:04。
- 代码 5c0ef5a：Android debug build（push 41771064968 / PR 41771070096）、真 pi 桥接（41771064901 / 41771069764）全绿；模拟器：UiCoverageTest 5、AppCoverageTest 3（新）、SettingsScreenUiTest 9，失败 0。
- PR #21 描述已补“审查第 1 轮修复”逐条表、越界改动、CI 链接；未改 .github。
- 未完成 / 交接 PR 2：TERM_WIRED 改 true 并接线 TermSurface（返回键、附件同样接入）；删 SettingsHub 等旧路由；相机 / 剪贴板图片入口（本轮只接系统照片选择器）。

### 12:27 CST — merge main@3726c1f（PR #18 r3/term 已合入）
- 普通 merge（两父提交，不 rebase、不 force）。冲突只有 `RealModelPanels.kt` 的 oauthPanel：以 main 为准（Text 带 `flow = FlowState.*`），保留本轮 NeedCode 授权链接。
- CalmPanelLogic.isOAuthFinal 同时认 main 新增的 `FlowState.DONE/FAILED`。
- 下一步：等 CI；之后可能再 merge PR #23（r3/session-overlay）。

## merge main@584a056（2026-11-04 12:35 CST 开始）
- 已开始：把 main@584a056（PR#23 r3/session-overlay）普通 merge 进 r3/app@7f9bceb，不 rebase、不 force。
- 计划：①merge 并列冲突文件；②core/session 路由/栈逻辑以 main 为准，app/calm 适配新接口，保留 PR#21 全部审查修复；③本地 assembleDebug testDebugUnitTest；④推送盯 CI（含模拟器）；⑤更新 PR 描述。
- 12:39 CST：前一节由另一个（已取消的）合并任务写入，其推送取消了 7f9bceb 的模拟器 run；e3f7c9e 与 7f9bceb 代码相同。r3/app 现只由本任务处理。
- 冲突：只有 `feature/chat/.../calm/CalmSheet.kt`（三方合并无文本冲突）。core/session、core/contract 全部取 main。
- 适配：FormView 在面板事件模式下不重复画 main 新加的 Form.actions 键帽（由 PR #21 的附加按钮发出，带密文字段）；CalmPanelLogic 压栈改用 `OverlayStack.update` 原子插到 ExtDialog 之下（守 main 的「扩展对话框固定栈顶」）。测试：CalmPanelLogicTest.pushedPanelsStayBelowPinnedExtDialog。
- 下一步：推送 merge 提交，盯 CI（含模拟器）。

## PR #21 审查第 2 轮修复（2026-11-04 12:54 CST 已开始，基于 afa66ed）
计划：I1 OAuth NeedCode 表单期间照常轮询，keepTyped 保留输入，DONE/FAILED 切已登录/失败（扩展 CalmPkceLoginTest）；Nit1 确认框防连点、不残留栈；Nit2 返回键按画面层级自上而下关；Nit3 菜单文案改「图片」（相机/剪贴板留 PR 2）；Nit4 oauthUrls 结束/重试时清理、代理页重启入口或文案。每条加测试，CI 全绿含模拟器。

### 13:10 CST 进度（第 2 轮修复）
- 已读完第 2 轮审查（comment 6622891610）；main 无新提交（r3/app 领先 26，落后 0），不需要 merge。
- 方案已定：I1 needsOAuthPolling 对所有未到终点的 `oauth:` 面板（Text/Form/SelectList）轮询，keepTyped 兼顾 SelectList 的 query/selectedKey；Nit1 确认框按 id 定位（不依赖栈顶）+ PanelSendGate 防连点；Nit2 面板改画在抽屉/展开编辑器之上，CalmBack 按画面层级（面板→编辑器→会话列表→搜索，ExtDialog 在顶不拦截）；Nit3 菜单改「图片」；Nit4 oauthUrls 在新流程/重试/取消/结束时清理，代理保存后给「立即重启 pi」（engine.restart）。
- 下一步：写代码与测试，本地 JDK17 跑 JVM 单测，一次性推送。
