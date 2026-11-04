# r3/session 移植与来源

- `core/session/.../AtFileExpansion.kt`：`@` 引用写法依据 `@earendil-works/pi-tui@1.5.0`（MIT）`dist/autocomplete.js` 的 `buildCompletionValue` / `applyCompletion` 与 `dist/utils.js` 的 `autocompleteSeparatorRegex`；未复制代码，只按其行为实现。
- `core/session/.../PiSettingsImpl.kt`（`PiSettingsSchemaV150`）：键名与默认值取自 `docs/pi-features.md` §8（整理自 `@earendil-works/pi-coding-agent@1.5.0` 文档，MIT）。
- `core/session/.../text/LineDiff.kt`、`SessionTreeRows.kt`：从 `feature/usage` 移入（原 W4 实现，来源见 docs/notices/feat-commands.md 等既有条目）。
- `core/session/.../text/Ansi.kt`：从 `feature/files` 移入；去转义正则参考 Pi-Web `lib/ansi.ts`（MIT，见 docs/notices/feat-files.md）。
- 无新增第三方依赖；未使用 dsh-Go 代码。
