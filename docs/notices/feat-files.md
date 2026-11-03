# feat/files（W3）移植来源

- agegr/pi-web@025e24e74fdbc8f46c4e0cf31c6d6b3fa6cf29d5（npm @agegr/pi-web 0.14.2），MIT，Copyright (c) 2026 agegr —— 改写：core/workspace/.../GitParse.kt（状态分类思路，改为解析 porcelain v2）← lib/git-status.ts:10-47
- agegr/pi-web@025e24e，MIT —— 移植：core/workspace/.../GitParse.kt `addedFilePatch`（未跟踪文件生成「全部新增」diff）← lib/git-changes.ts:158-176
- agegr/pi-web@025e24e，MIT —— 改写：core/workspace/.../FileTypes.kt（扩展名 → MIME 表）← lib/file-types.ts:8-46
- agegr/pi-web@025e24e，MIT —— 改写：feature/files/.../output/Ansi.kt（ANSI 转义匹配正则，调整了分支顺序以正确吃掉 OSC/APC 串）← lib/ansi.ts:1-3

其余代码为本分支原创。未使用 dsh-Go 的代码、名称、标识或吉祥物；未新增二进制或素材。
