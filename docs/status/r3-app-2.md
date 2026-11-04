# r3/app-2 状态

**状态：已开始**（2026-11-04 14:07 CST，基于 main@ccee240）

## 计划
1. 接终端形态：TERM_WIRED=true，Crossfade(form) 切 TermSurface，共用 SessionHost；返回键、＋附件接 TermSurface；/model 等选择面板选完自动关闭；设置›模型「立即重启 pi」比较当前生效代理 vs 已保存代理。
2. 删除旧模块（按模块分提交）：feature/usage 剩余文件；feature/files 文件浏览器/预览/Git/Highlight/FilesHost/FilesController/Share Target/旧 ExportSheet；WiredPages 旧页面、PiGoDrawer、回收站与 SettingsHub 等旧路由。
3. 测试：AppCoverageTest、FormSwitchStreamingTest；模拟器名单补 SettingsScreenUiTest 等。
4. 合并 docs/notices/r3-*.md 进 THIRD_PARTY_NOTICES.md；写 docs/testing/r3-device-checklist.md。

## 进度
- 14:07 建分支、推本文件。下一步：拉源码、读 DESIGN-r3 ⑥段与 app/**。
- 14:1x WIP（更正：本节及下两节标题 14:42/14:50/14:56 是估错的，实际约 14:10、14:12、14:14 CST） 推送（未本地编译）：①终端接线（TERM_WIRED=true；FormHost term = TermSurface，共用 graph.sessionHost；TermEnv: fileSearch、panelSource=PanelSourceTracker 包住交给 PiActionsImpl 的 RealModelPanels、onPanelEvent、onAttachImage → 系统照片选择器；返回键 TermBack 先关面板，扩展对话框不拦截）；②PickClose：/model 选中并切换成功后关闭选择面板（两形态共用，经 ModelPanelEvents.after）；③设置›模型「立即重启 pi」改为比较 effectiveProxy（引擎 Starting 时记下）vs 已保存（ProxyLogic.showRestart + 单测/仪器测试）；④删 WiredPages、PiGoDrawer、SettingsHub/Providers/Tree/Usage/Files/Sessions 旧路由、PendingModel、AppSmokeTest/WiringSmokeTest（测的是已删导航）；⑤feature/usage 只剩空 build.gradle.kts（settings include 归 r3/core）；⑥feature/files 删 FilesHost、browser/、export/、ui/（Back.kt 移到 output/）。
- 范围外最小改动：feature/term TermSurface.kt 加 TermEnv.onAttachImage（attach.image 交装配层）；app/build.gradle.kts 加 :feature:term、去 :feature:usage。
- 下一步：清理 feature/files 测试（FilesLogicTest 去浏览器/Git 部分，删 FilesControllerOrderTest、FilesDeviceTest 中浏览器用例）；FormHost 删 TermPlaceholder 并改 AppCoverageTest/FormHostTest 用 TermSurface；本地 assembleDebug testDebugUnitTest；然后 FormSwitchStreamingTest、AppCoverageTest 终端入口、模拟器名单、NOTICE 合并、真机清单。
- 14:50 推送：feature/files 测试清理（删 FilesControllerOrderTest、FilesLogicTest 的 FilesControllerTest、FilesDeviceTest 的浏览器用例）；FormHost 删 TermPlaceholder，FormHostTest/AppCoverageTest 终端层改用 TermSurface（断言 term:surface、form:toggle 用 unmerged tree）。当时仍未本地编译。
- 已知注意：删除没有按模块分提交（33a3795 一次删了 app 旧导航 + feature/usage + feature/files 浏览器），PR 描述需说明；每步编译证据以 CI 为准。
- 下一步（接手者）：①等 CI / 本地 `assembleDebug testDebugUnitTest :app:compileDebugAndroidTestKotlin` 修编译错误（重点 PiGoNavHost.kt 的 term 块与 import、AppGraph.panelSource、ModelPanelEvents 构造、feature/files 测试）；②写 app/src/androidTest FormSwitchStreamingTest（§7.2 第 3 条 A 档）与 AppCoverageTest 终端入口；③.github/workflows/android.yml 模拟器名单加 FormSwitchStreamingTest（单独标明 .github 改动）；④合并 docs/notices/r3-*.md 进 THIRD_PARTY_NOTICES.md；⑤docs/testing/r3-device-checklist.md；⑥开 PR。
- 14:56 推送：THIRD_PARTY_NOTICES.md 新增 §9（合并 r3-calm/core/session/term：pi 主题 JSON、默认键位表、wcwidth、拼音表、各移植条目、夹具、字体图标、删除说明）；docs/testing/r3-device-checklist.md（A–G 共约 35 条，逐条可勾）。CI @c6643c7 运行中，本地首次 gradle 编译仍在跑。
- 14:22 推送：本地 `:app:compileDebugKotlin :app:testDebugUnitTest :feature:files:testDebugUnitTest :feature:chat:testDebugUnitTest :app:compileDebugAndroidTestKotlin :feature:chat:compileDebugAndroidTestKotlin :feature:files:compileDebugAndroidTestKotlin` 全过（app 42、chat 134 单测，失败 0）。新增：FormSwitchStreamingTest（A 档）、PickCloseTest、ProxyLogicTest.restartEntryFollowsAppliedVsSaved；调度补充（PR#17 calm 遗留）：UiCoverageTest.calmSlowFramesWithin1000Deltas（相对基线阈值）与 calmLightAndDarkScreenshots；删指向旧 ChatScreen 的 StreamingJankTest、StopKeepsDraftTest；CalmComposer 空输入退格退出 ! 模式（bashBackspace + CalmBashBackspaceTest）。模拟器名单（.github/workflows/android.yml:154）已含 UiCoverageTest、TermCoverageTest、AppCoverageTest、FormSwitchStreamingTest、SettingsScreenUiTest，不改 .github。
- 下一步：盯 CI（含模拟器）→ 开 PR → 补 PR 描述（coverage-map 统计、越界改动、删除清单）。
- 14:25 已开 PR #26（草稿描述），等 CI。
- 14:45 CI 79ffb91（push run 41784967642）：build 绿、真 pi 桥接绿；模拟器 UiCoverageTest（7，含新增 calm 慢帧 / 截图）、TermCoverageTest（16）、AppCoverageTest（6）全过；FormSwitchStreamingTest 失败 `ComposeNotIdleException`（20ms 一段流式下 waitForIdle 超时，测试本身问题）。已改为 120 段 × 100ms 并推送；SettingsScreenUiTest 因前一项失败未跑到。
- 下一步（接手者）：看新 SHA 的 Android debug build（PR #26）模拟器 job；绿则在 PR 描述「CI 证据」补 run 链接，status 改「完成，PR #26」；红则看 emu 日志修。
- 15:10 CI 3c6aa27（41787406103 / 41787412596）：模拟器只剩 FormSwitchStreamingTest `expected CALM but was TERM`（UiCoverageTest 7、TermCoverageTest 16、AppCoverageTest 6 全过）。根因：测试问题——终端 ⇄UI（form:toggle）是可横向滚动键条第 14 个键，窄屏在视口外，performClick 点在被裁剪区域落空；切换逻辑本身由 FormHostTest/AppCoverageTest 与第一次点击（UI→终端）证明正常。修：点前 performScrollTo（同 TermCoverageTest.tap），FormHostTest 同改。产品观察（r3/term 范围，不在本 PR 改）：窄屏上 ⇄UI 需横滑才看到，建议调度者评估是否把 ⇄UI 前移。
