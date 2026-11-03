# r3/core 状态

状态：进行中

## 范围
DESIGN-r3.md §8.3 第 2 步（r3/core 实现）：§5.7 三件事实测、pigo-bridge.ts（§5.6）+ EngineConfig `-e` 加载与退路、
RootfsWorkspace 收缩（@ 模糊搜索、读文件、branchName()、只列目录）、删 GitParse（§8.2）、ProcessTree 读 PID 接口。
SessionHostImpl 本体按 §8.1 归 r3/session（core/session），本线只提供它需要的引擎侧能力。

## 已完成
- 07:12 从 r3/core-base@9de6c2a 建分支
- §5.7 三件事真 pi 1.5.0 实测完成：docs/protocol/r3-measurements.md（+ docs/protocol/r3-measure/*.mjs）
- pigo-bridge.ts；contract/bridge/PiBridge.kt + fake/FakePiBridge.kt（只新增）
- PiEngine 实现 PiBridge（tree-nav/label/reload/switch + 退路）、-e/-a、过滤 pigo: 命令、debugEnginePid；ProjectTrustFile；ProcessTree.enginePid；app EngineBindings 一行接线（范围外最小改动）
- 单测：PiEngineBridgeTest、PigoBridgeTest、ProjectTrustFileTest、ProcessTreeEnginePidTest；RealPiBridgeIntegrationTest + .github/workflows/realpi.yml

## 下一步
- 看 CI（android.yml + realpi.yml）结果并修
- workspace：新契约 contract/workspace/WorkspaceSearch.kt；RootfsWorkspace 加 @ 模糊搜索（照 pi-tui autocomplete 打分）、branchName()、listDirectories；删 GitParse.kt/GitParseTest/GitDiffRealTest，git API 改为 @Deprecated 存根
- 开 PR（base r3/core-base）

## 接手须知
- 本地工作副本：/workspace/work/r3core/src（tarball 解包，非 git）；真 pi 1.5.0：/workspace/work/r3core/pi/node_modules/.bin/pi
- 实测脚本：/workspace/work/r3core/measure/（node，mock OpenAI）
- 推送：/workspace/work/r3core/push.py（Git Data API，force=false）
