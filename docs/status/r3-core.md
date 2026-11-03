# r3/core 状态

状态：**完成，PR #16**（https://github.com/zr987459/pi-go/pull/16，base `r3/core-base`，#15 合入后改 base 为 main）

## 范围
DESIGN-r3.md §8.3 第 2 步（r3/core 实现）：§5.7 三件事实测、pigo-bridge.ts（§5.6）+ EngineConfig `-e` 加载与退路、
RootfsWorkspace 收缩（@ 模糊搜索、读文件、branchName()、只列目录）、删 GitParse（§8.2）、ProcessTree 读 PID 接口。
SessionHostImpl 本体按 §8.1 归 r3/session（core/session），本线只提供它需要的引擎侧能力。

## 已完成
- §5.7 三件事真 pi 1.5.0 实测：docs/protocol/r3-measurements.md（脚本 docs/protocol/r3-measure/*.mjs）
- pigo-bridge.ts；contract/bridge/PiBridge.kt、contract/fake/FakePiBridge.kt、contract/workspace/WorkspaceSearch.kt（只新增）
- PiEngine 实现 PiBridge（tree-nav/label/reload/switch + 退路）、-e/-a、过滤 pigo: 命令、debugEnginePid；ProjectTrustFile；ProcessTree.enginePid
- RootfsWorkspace：@ 搜索（FileSearch）、branchName、listDirectories、readFile；删 GitParse.kt/GitParseTest.kt/GitDiffRealTest.kt，git* 留 @Deprecated 存根
- 单测：PiEngineBridgeTest、PigoBridgeTest、ProjectTrustFileTest、ProcessTreeEnginePidTest、AtFileSearchTest、WorkspaceShrinkTest；
  RealPiBridgeIntegrationTest 在 CI（.github/workflows/realpi.yml）真跑 5/5 通过
- 已普通 merge r3/core-base@75ec933（#15 审查第 1 轮修复）
- 修了 PiEngineTest.promptTimeoutIsConfirmedLaterInsteadOfDuplicated 的偶发竞态（CI 上 expected SENT but was FAILED）

## 未完成（写在 PR）
- TurnStats 收缩：调用方在 feature/usage（r3/app 删除后再做，§8.2 先断引用）
- `include(":feature:usage")` 等残留：§8.3-5 单独小 PR
- r3/core-base 若再有审查修复：普通 merge 进 r3/core（POST /merges）

## 接手须知
- 推送：/workspace/work/r3core/push.py（Git Data API，force=false）；本地副本 /workspace/work/r3core/src（非 git）
