# r3/core 状态

状态：进行中

## 范围
DESIGN-r3.md §8.3 第 2 步（r3/core 实现）：§5.7 三件事实测、pigo-bridge.ts（§5.6）+ EngineConfig `-e` 加载与退路、
RootfsWorkspace 收缩（@ 模糊搜索、读文件、branchName()、只列目录）、删 GitParse（§8.2）、ProcessTree 读 PID 接口。
SessionHostImpl 本体按 §8.1 归 r3/session（core/session），本线只提供它需要的引擎侧能力。

## 已完成
- 07:12 从 r3/core-base@9de6c2a 建分支
- 读完设计；本地装好真 pi 1.5.0（npm），确认 RPC 模式 commandContextActions 支持 navigateTree/switchSession/reload

## 下一步
- 写 core/engine/src/main/assets/pi/pigo-bridge.ts，用真 pi 实测 §5.7 三件事

## 接手须知
- 本地工作副本：/workspace/work/r3core/src（tarball 解包，非 git）；真 pi 1.5.0：/workspace/work/r3core/pi/node_modules/.bin/pi
- 实测脚本：/workspace/work/r3core/measure/（node，mock OpenAI）
- 推送：/workspace/work/r3core/push.py（Git Data API，force=false）
