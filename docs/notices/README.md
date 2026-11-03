# docs/notices：各工作流的移植来源登记

`THIRD_PARTY_NOTICES.md` 归 scaffold 维护者和界面分支，功能工作流（W1–W4）不直接改它，避免冲突。每条工作流把移植或拷贝的第三方代码记到自己的文件里，W5（`feat/wiring`）时由 scaffold 维护者统一合入 `THIRD_PARTY_NOTICES.md`。

## 文件名

`docs/notices/<分支名，斜杠换成横杠>.md`，例如 `feat-engine-rpc.md`、`feat-models.md`、`feat-files.md`、`feat-commands.md`。

## 每行格式

```
- <上游仓库>@<提交或版本>，<许可证>，Copyright <年份与作者> —— 移植/改写：<pi-go 文件> ← <上游文件:行号>
```

示例：

```
- agegr/pi-web@025e24e（npm @agegr/pi-web 0.14.2），MIT，Copyright (c) 2026 agegr —— 移植/改写：core/catalog/.../ModelDiscovery.kt ← lib/model-discovery.ts:1-135
- ccch1mneyyy/dsh-Go@<提交>，AGPL-3.0 —— 移植/改写：feature/files/.../FileTree.kt ← app/lib/ui/files/file_tree.dart:10-120
```

## 规则

- 授权依据：所有者 2026-11-03 决定自用、不商用，Pi-Web（MIT）、pi-mono、dsh-Go 的代码可以直接拷贝或移植，但必须在这里记来源（REVIEW.md“许可证”一条）。
- dsh-Go 的名称、标识和吉祥物（Deepy、鲸娘）不在其开源授权内，**不得使用**。
- 二进制和素材（字体、图标、图片）同样要登记，并写明文件的 SHA256。
- 没有移植任何代码的工作流也要建文件，写一行“本分支无移植代码”。
