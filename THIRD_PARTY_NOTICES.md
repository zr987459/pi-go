# 第三方组件与代码来源（THIRD_PARTY_NOTICES）

本文件记录 pi-go 使用或计划使用的第三方代码、二进制和资源，以及从其他项目复用代码的来源。每个工作流引入新组件时，必须在同一个提交里更新本文件。

状态说明：**已引入** = 仓库里已有；**计划引入** = DESIGN.md 已确定，将由对应工作流引入。

## 1. 从 PiMet 复用的代码

- 来源：`zr987459/pimet`，提交 `a80222c2dda4a4c8a936258b96597bc7ea7d4102`（v1.9.0-beta.30）
- 作者：zr987459（pi-go 仓库所有者本人）
- 许可证：**PiMet 仓库目前没有 LICENSE 文件**。复用经仓库所有者本人同意。建议所有者为 PiMet 补充许可证声明，并为 pi-go 选定许可证。
- 计划复用的文件（mvp/runtime 引入，引入时在此逐个补全「原路径 → 新路径」）：
  - `app/src/main/java/com/xm486/pimet/proot/ProotManager.java`（部分方法）
  - `app/src/main/java/com/xm486/pimet/proot/TarGzipExtractor.java`
  - `app/src/main/java/com/xm486/pimet/util/SafeFiles.java`
  - `app/src/main/java/com/xm486/pimet/PiMetService.java`
  - `app/src/main/java/com/xm486/pimet/PiMetConfig.java`（pi 配置文件读写部分）
- 状态：计划引入

## 2. 随 APK 分发的原生二进制（来自 PiMet 的 jniLibs）

| 组件 | 文件 | 许可证 | 上游 | 状态 |
|---|---|---|---|---|
| PRoot（Termux 分支） | `libproot.so`、`libprootloader.so`、`libprootloader32.so` | GPL-2.0-or-later | https://github.com/termux/proot | 计划引入 |
| talloc | `libtalloc.so` | LGPL-3.0-or-later | https://talloc.samba.org/ （Termux 打包） | 计划引入 |
| libandroid-shmem | `libandroidshmem.so` | BSD-3-Clause（待核实） | https://github.com/termux/libandroid-shmem | 计划引入 |

GPL/LGPL 义务：分发这些二进制时，须同时提供对应源码或有效的书面承诺。mvp/runtime 在引入二进制时，要在此记录确切的上游版本或提交，并在仓库 Release 说明中给出源码获取方式；更好的做法是改为在 CI 中从上游源码可复现地编译。

**不引入**：PiMet 中的 `libproroot*.so`（代码未使用，来源与许可证不明）。

## 3. Gradle 依赖（计划）

| 组件 | 许可证 | 用途 | 状态 |
|---|---|---|---|
| AndroidX、Jetpack Compose、Material 3 | Apache-2.0 | 界面 | 计划引入（mvp/scaffold） |
| kotlinx.coroutines、kotlinx.serialization | Apache-2.0 | 协程、JSON | 计划引入（mvp/scaffold） |
| OkHttp | Apache-2.0 | 下载 | 计划引入（mvp/runtime） |
| Termux terminal-emulator / terminal-view | Apache-2.0 | 浮动终端 | 第二期 |
| Shizuku API | 待核实 | 特权操作 | 第三期 |

## 4. 运行时由用户设备下载、不随 APK 分发的组件

| 组件 | 许可证 | 来源 |
|---|---|---|
| Ubuntu Base 24.04 | 各软件包各自的许可证（GPL、LGPL、MIT 等） | 阿里云 / 清华 / 中科大 / 华为云镜像 |
| Node.js 24 | MIT（含各依赖的许可证） | npmmirror / 华为云 / 清华 / 阿里云镜像 |
| `@earendil-works/pi-coding-agent` | MIT | npm（国内用 npmmirror） |

## 5. 只参考设计、未使用代码的项目

- dsh-Go（https://github.com/ccch1mneyyy/dsh-Go ，AGPL-3.0）：只参考了其公开的设计说明（工具卡片折叠、插话/排队交互、运行时状态机等）。**pi-go 没有拷贝 dsh-Go 的任何代码或资源**，也不使用其名称、标识与吉祥物（Deepy、鲸娘）。如果将来确需拷贝其代码，pi-go 必须改为 AGPL-3.0，并在此列出拷贝来源与署名。

## 6. 明确不使用的资源

- PiMet 内置桌宠素材 `assets/pets/doro.codex-pet/`、`assets/pets/elaina-2/`：无许可证说明，且涉及第三方角色形象，不引入。
