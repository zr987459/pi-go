# r3/core 移植来源登记

格式与规则见 `docs/notices/README.md`。由 r3/app（或 scaffold 维护者）统一合入 `THIRD_PARTY_NOTICES.md`。

授权依据：pi 1.5.0 的 npm 包 `package.json` 的 `license` 字段为 MIT。包内没有单独的 LICENSE 文件，版权方以上游仓库 earendil-works/pi（`github.com/earendil-works/pi`）的 LICENSE 为准。所有者 2026-11-03 决定自用、不商用（REVIEW.md「许可证」）。以下各处都是**按行为对照用 Kotlin 改写**，没有拷贝 pi 的源码文件，也没有打包 pi 的运行时。

- earendil-works/pi（npm @earendil-works/pi-tui 1.5.0），MIT —— 移植/改写：`core/workspace/.../FileSearch.kt` ← `dist/autocomplete.js` 的 `CombinedAutocompleteProvider.getFuzzyFileSuggestions` 与 `scoreEntry`。移植内容：候选集（第一层 + 递归命中上限 100）、按文件名或相对路径过滤、smart-case，打分常数（完全相同 100、前缀 80、文件名包含 50、路径包含 30、目录 +10），以及排序规则（分数 → 层数 → 长度 → 字典序）。
- earendil-works/pi（npm @earendil-works/pi-coding-agent 1.5.0），MIT —— 移植/改写：`core/workspace/.../RootfsWorkspace.kt` 的 `branchNameBlocking()`（`branchName()` 用它）← `dist/core/footer-data-provider.js` 的 `findGitPaths` 与 `resolveGitBranchSync`。移植内容：从 cwd 往上找 `.git`（目录，或 worktree 的 `gitdir:` 文件）；读 HEAD，`ref: refs/heads/<名>` 取名字，其他情况为 `detached`；reftable 仓库用 `git symbolic-ref` 兜底。
- earendil-works/pi（npm @earendil-works/pi-coding-agent 1.5.0），MIT —— 移植/改写：`core/engine/.../ProjectTrustFile.kt` ← `dist/core/trust-manager.js` 的 `ProjectTrustStore`。移植内容：`<agentDir>/trust.json` 的格式（键是目录绝对路径，值为 `true`/`false`/`null`，按键排序，2 空格缩进，末尾换行）、向上查最近祖先记录的规则，以及与 pi 共用的锁协议（`trust.json.lock`，即 proper-lockfile 的 mkdir 锁，10 秒视为过期）。
- 本分支自写、调用 pi 公开扩展 API 的 `core/engine/src/main/assets/pi/pigo-bridge.ts`：用到 `registerCommand`、`on("session_start")`、`ctx.navigateTree`、`ctx.switchSession`、`ctx.reload`、`pi.setLabel`、`ctx.ui.notify`、`ctx.sessionManager.*`。没有拷贝 pi 源码，接口形状参照 pi 1.5.0 的 `docs/extensions.md` 与 `dist/core/extensions/types.d.ts`。
- 本分支没有新增二进制或素材（字体、图标、图片）。
