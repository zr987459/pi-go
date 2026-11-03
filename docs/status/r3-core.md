# r3/core 状态

状态：进行中，PR 已开

## 范围
DESIGN-r3.md §8.3 第 2 步（r3/core 实现）：§5.7 三件事实测、pigo-bridge.ts（§5.6）+ EngineConfig `-e` 加载与退路、
RootfsWorkspace 收缩（@ 模糊搜索、读文件、branchName()、只列目录）、删 GitParse（§8.2）、ProcessTree 读 PID 接口。
SessionHostImpl 本体按 §8.1 归 r3/session（core/session），本线只提供它需要的引擎侧能力。

## 已完成
- 07:12 建分支；§5.7 实测完成（docs/protocol/r3-measurements.md）
- 桥接 + PiEngine PiBridge + 退路 + trust + PID；单测与 realpi 集成测试（CI realpi.yml 5/5 通过 @7d3501e）
- 已普通 merge r3/core-base@75ec933（#15 审查修复）
- workspace：WorkspaceSearch + FileSearch + branchName + listDirectories；删 GitParse/GitParseTest/GitDiffRealTest

## 下一步
- 等 android.yml 单测全绿，失败则读日志修
- PR 描述补 CI 结果

## 接手须知
- 本地工作副本：/workspace/work/r3core/src（tarball 解包，非 git）；真 pi 1.5.0：/workspace/work/r3core/pi/node_modules/.bin/pi
- 实测脚本：/workspace/work/r3core/measure/（node，mock OpenAI）
- 推送：/workspace/work/r3core/push.py（Git Data API，force=false）
