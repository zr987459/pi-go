# 最终设计稿进度

状态：已完成

计划：
1. 通读 proposals A/B/C、votes V2/V3、README、THIRD_PARTY_NOTICES、REVIEW.md，整理"应吸收点"和"事实性错误"清单
2. 核对 pi coding agent 真实接口（npm 包名、锁定版本、RPC/JSON 模式）与 pimet@a80222c 的运行环境与许可证
3. 以方案 B 为基础写最终稿，覆盖 docs/DESIGN.md
4. 视需要同步 THIRD_PARTY_NOTICES.md 与 README.md
5. 自查一致性后推送，本文件标记"已完成"

进度：
- 2026-11-03 06:40 CST 已开始
- 阶段1完成：吸收点/错误清单整理完毕（V2 吸收 9 条、V3 吸收 A 7 条 + C 5 条 + B 自修 3 条；事实性错误 V2 §三、V3 §4 共 14 条，合并去重后进入 DESIGN.md 决策记录）
- 阶段2完成：接口核实。npm `@earendil-works/pi-coding-agent` latest=1.5.0（2026-11-02 发布，MIT，node>=22.19.0，npmmirror 已同步）；`@mariozechner/pi-coding-agent` 停在 0.73.1 且已 deprecated。RPC 文档（rpc.md / rpc-commands.md / json.md / rpc-extension-ui.md / rpc-types.d.ts）逐项核对。PiMet@a80222c：TarGzipExtractor 与 DSH-Folk（GPL-3.0）447/453 行一致已复核；另发现 PiMet 的 jniLibs（libproot/libprootloader/libprootloader32/libtalloc/libandroidshmem）与 DSH-Folk `app/libs/` 逐字节相同（SHA256 一致），来源需改为 Termux 官方包
- 阶段3完成：docs/DESIGN.md 已推送（提交 6799687d9536107599ad2b150e1c4be7cd1b5407），同一提交同步更新 THIRD_PARTY_NOTICES.md 与 README.md
- 阶段4完成：自查一致性（章节号与 REVIEW.md 引用的 §6、`:core:contract` 对齐；工作流文件范围互不重叠；全文无参考项目名称/标识）

- 补充：推送后 main 上出现 V1 投票（c2d34e1，同样投 B），已在 DESIGN.md 决策记录中补入其分数、吸收点与错误更正（提交 1acc0a39c844a8cb7054257b19ca05834d539f5a）

状态：**已完成**（2026-11-03 CST）。最终 DESIGN.md 提交：1acc0a39c844a8cb7054257b19ca05834d539f5a
