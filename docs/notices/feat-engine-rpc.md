# feat/engine-rpc（W1）移植来源

- agegr/pi-web@025e24e，MIT，Copyright (c) 2026 agegr —— 移植/改写：core/protocol/src/main/kotlin/io/github/zr987459/pigo/core/protocol/CompactionSummary.kt ← lib/compaction-summary.ts:1-34
- agegr/pi-web@025e24e，MIT，Copyright (c) 2026 agegr —— 移植/改写：core/protocol/src/main/kotlin/io/github/zr987459/pigo/core/protocol/TurnStats.kt（TurnFiles）← lib/turn-written-files.ts:82-127
- agegr/pi-web@025e24e，MIT，Copyright (c) 2026 agegr —— 移植/改写：core/protocol/src/main/kotlin/io/github/zr987459/pigo/core/protocol/TurnStats.kt（TurnClock、formatTurnDuration）← lib/turn-duration.ts:27-64、lib/stream-token-rate.ts:1-39
- agegr/pi-web@025e24e，MIT，Copyright (c) 2026 agegr —— 移植/改写：core/protocol/src/main/kotlin/io/github/zr987459/pigo/core/protocol/ChatReducer.kt（带图乐观气泡按「文本 + 图片签名」确认）← lib/prompt-recovery.ts:39-47
- @earendil-works/pi-coding-agent@1.5.0，MIT，Copyright (c) Mario Zechner —— 原样复制（测试资源）：core/protocol/src/test/resources/pi-1.5.0/rpc-types.d.ts ← dist/modes/rpc/rpc-types.d.ts

未使用 dsh-Go 代码。无新增 Gradle 依赖。
