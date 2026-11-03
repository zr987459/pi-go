# r3/core 状态

状态：进行中

## 范围
DESIGN-r3.md §8.3 第 2 步（r3/core 实现）：§5.7 三件事实测、pigo-bridge.ts（§5.6）+ EngineConfig `-e` 加载与退路、
RootfsWorkspace 收缩（@ 模糊搜索、读文件、branchName()、只列目录）、删 GitParse（§8.2）、ProcessTree 读 PID 接口。
SessionHostImpl 本体按 §8.1 归 r3/session（core/session），本线只提供它需要的引擎侧能力。

## 已完成
- 07:12 从 r3/core-base@9de6c2a 建分支
- pigo-bridge.ts 写好；§5.7 三件事已用真 pi 1.5.0 实测，结论见 docs/protocol/r3-measurements.md（桥接四项全部可行；trust 需写 trust.json 或 -a；--no-session 用临时会话近似）

## 下一步
- Kotlin：EngineConfig -e/-a、PigoBridge 安装与协议解析、PiEngine 桥接调用+退路、过滤 pigo: 命令、ProjectTrustFile、debugEnginePid
- RealPiBridgeIntegrationTest + CI 步骤（npm i pi 后跑）
- workspace：@ 搜索、branchName、listDirs；删 GitParse

## 接手须知
- 本地工作副本：/workspace/work/r3core/src（tarball 解包，非 git）；真 pi 1.5.0：/workspace/work/r3core/pi/node_modules/.bin/pi
- 实测脚本：/workspace/work/r3core/measure/（node，mock OpenAI）
- 推送：/workspace/work/r3core/push.py（Git Data API，force=false）
