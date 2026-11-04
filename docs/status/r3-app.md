# r3/app 状态

- 2026-11-04 08:11 CST 已开始（基于 main@de09f66）。
- 2026-11-04 08:25 CST 第 1 步进行中。

## 计划
1. §8.3 第 2 步：只被 app 引用的删除准备（标记、拆引用，暂不删模块）。
2. 接线骨架：按契约 SessionHost 编写，开发期用 FakeSessionHost；Crossfade 两形态切换；形态偏好持久化；终端未合入时显示占位。
3. core/session/models/calm 合入 main 后普通 merge main，切真实实现，CI 绿后开 PR 1（接线 UI 形态）。
4. term 合入后：接线终端形态、删除旧模块（feature/usage、文件浏览器、Git 页、Share Target 等）、AppCoverageTest、FormSwitchStreamingTest、合并 THIRD_PARTY_NOTICES、docs/testing 真机清单（必要时用 r3/app-2）。

## 已完成
- a19fcf2：删除系统分享入口 Share Target（X10）：share/*.kt、ShareSecurityTest、清单 Activity；清理逻辑并入 FileShare.cleanStale + FileShareCleanTest。
  （feature/files、feature/usage 只被 app 引用，已核对：`grep -rln "feature\.files\|feature\.usage"` 只命中 app/ 下 3 个文件。）
- 本提交：feature/files（浏览器、预览、Git、ExportSheet）与 feature/usage（用量、每轮统计、压缩卡、旧树页、旧 DiffView）入口类加 `@Deprecated("r3: r3/app 删除…")`（§8.2 第 4 条）。未碰 r3/session 正在移动的 LineDiff / SessionTreeRows / CompactionSummary / Ansi。

## 依赖 PR 状态（08:25 CST）
- #16 r3/core、#20 r3/session、#19 r3/models、#17 r3/calm、#18 r3/term：均未合入 main。

## 下一步（精确）
- 写接线骨架：app/.../di/SessionBindings.kt（FAKE→FakeSessionHost，REAL 待 r3/session）、app/.../form/FormPreference.kt（SharedPreferences 持久化 UiForm）、app/.../form/FormHost.kt（Crossfade 120ms，CALM=现有聊天页，TERM=占位「终端形态尚未合入」）、抽屉加「终端形态」入口；单测 FormPreferenceTest；推送看 CI。
