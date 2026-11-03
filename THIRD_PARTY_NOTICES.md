# 第三方组件与代码来源（THIRD_PARTY_NOTICES）

本文件记录 pi-go 使用或计划使用的第三方代码、二进制和资源，以及从其他项目复用代码的来源。依据与结论见 `docs/DESIGN.md` §4.1。引入新组件时，必须在同一个提交里更新本文件（MVP 并行期间由 `mvp/scaffold` 维护者合入，见 DESIGN.md §6）。

状态说明：**已引入** = 仓库里已有；**计划引入** = DESIGN.md 已确定，将由对应工作流引入；**不使用** = 明确排除。

pi-go 自身许可证：**Apache-2.0**（见根目录 `LICENSE`，Copyright 2026 zr987459）。随 APK 分发的独立可执行程序（如 proot）按其各自许可证分发，见 §2。

## 1. PiMet 代码

- 来源：`zr987459/pimet`，提交 `a80222c2dda4a4c8a936258b96597bc7ea7d4102`（v1.9.0-beta.30）
- 作者：zr987459（pi-go 仓库所有者本人）；PiMet 仓库没有 LICENSE 文件
- **可复用**：PiMet 作者原创的代码，由所有者按 pi-go 选定的许可证复用。MVP 只**改写**（Kotlin 重写，非原样拷贝）以下逻辑：
  - `proot/ProotManager.java`：`buildProotProcess` 参数集、镜像下载与测速、国内源安装流程（`buildDomesticRootfs`、`writeDomesticAptConfig`、`downloadWithResume` 等）→ `core/runtime`
  - `PiMetService.java` → `core/engine/EngineService.kt`
  - `PiMetConfig.java`（pi 配置写入部分）→ `core/store`
  - 状态：计划引入（mvp/runtime、mvp/engine、mvp/sessions-settings 引入时在此补全「原路径 → 新路径」）
- **不使用（派生自 GPL-3.0 的 IPF-Sinon/DSH-Folk，重写或弃用）**：
  - `proot/TarGzipExtractor.java`：与 DSH-Folk `app/src/main/java/me/bmax/apatch/dsh/TarGzipExtractor.java` 447/453 行相同 → 基于 Apache Commons Compress 净室重写
  - `ProotManager` 中注明参考 DSH-Folk / DSHA 的部分（`ensureRuntimeFiles`、DNS 修复）及 `ensureContainerGroups` → 按公开文档重新编写
  - `.github/workflows/build-slim-rootfs.yml` 及其 `runtime-latest` rootfs（DSH-Folk release 裁剪再分发）→ 弃用
- **不使用**：`util/SafeFiles.java`（未发现第三方来源，但 MVP 不需要，按需重写）
- 提醒：PiMet 本身原样包含上述 GPL-3.0 代码，继续分发 PiMet 时应按 GPL-3.0 开源或移除该代码。

## 2. 随 APK 分发的原生二进制

**不使用 PiMet 的 jniLibs**：PiMet 的 `libproot.so`、`libprootloader.so`、`libprootloader32.so`、`libtalloc.so`、`libandroidshmem.so` 与 DSH-Folk `app/libs/` 中同名文件逐字节相同，没有对应源码与构建记录。pi-go 改从 Termux 官方包获取：

| 组件 | 文件 | 许可证 | 上游 / 源码 | 版本（Termux 打包） | 状态 |
|---|---|---|---|---|---|
| PRoot（Termux 分支） | `libproot.so`、`libprootloader.so`、`libprootloader32.so` | GPL-2.0-or-later | https://github.com/termux/proot ；打包脚本 https://github.com/termux/termux-packages/tree/master/packages/proot | 5.1.107.102（以引入时为准） | 计划引入（mvp/runtime） |
| talloc | `libtalloc.so` | LGPL-3.0-or-later | https://talloc.samba.org/ ；https://github.com/termux/termux-packages/tree/master/packages/libtalloc | 2.5.0 | 计划引入（mvp/runtime） |
| libandroid-shmem | `libandroidshmem.so` | BSD-3-Clause | https://github.com/termux/libandroid-shmem | 0.7 | 计划引入（mvp/runtime） |

GPL/LGPL 义务：mvp/runtime 引入时在 `core/runtime/NOTICE-runtime.md` 记录每个 `.deb` 的确切版本与 SHA256，并同步到本表；每个发布 APK 的 GitHub Release 同时附上对应源码包。

**不使用**：PiMet 中的 `libproroot*.so`（5 个；代码未引用，来源与许可证不明）。

## 3. Gradle 依赖

版本统一写在 `gradle/libs.versions.toml`（mvp/scaffold 维护）。「已声明」= 已写入版本目录并加到对应模块的 `build.gradle.kts`，由括号中的工作流开始在代码中使用。

| 组件 | 版本 | 许可证 | 用途 | 状态 |
|---|---|---|---|---|
| Android Gradle Plugin、Gradle wrapper | 8.7.3、8.11.1 | Apache-2.0 | 构建（不进 APK） | 已引入（mvp/scaffold） |
| Kotlin 标准库、Compose 编译器插件 | 2.1.0 | Apache-2.0 | 语言与编译 | 已引入（mvp/scaffold） |
| AndroidX Core、Activity Compose、Lifecycle、Navigation Compose | 1.15.0、1.9.3、2.8.7、2.8.5 | Apache-2.0 | 应用骨架与导航 | 已引入（mvp/scaffold） |
| Jetpack Compose（BOM）、Material 3、Material Icons Core | BOM 2024.12.01 | Apache-2.0 | 界面 | 已引入（mvp/scaffold） |
| kotlinx.coroutines、kotlinx.serialization | 1.9.0、1.7.3 | Apache-2.0 | 协程、JSON | 已引入（mvp/scaffold） |
| AndroidX DataStore Preferences | 1.1.1 | Apache-2.0 | 偏好存储 | 已声明（mvp/sessions-settings 使用） |
| OkHttp | 4.12.0 | Apache-2.0 | 下载 | 已声明（mvp/runtime 使用） |
| Apache Commons Compress | 1.27.1 | Apache-2.0 | tar.gz 解压（替代 TarGzipExtractor） | 已声明（mvp/runtime 使用） |
| multiplatform-markdown-renderer-m3（mikepenz） | 0.28.0 | Apache-2.0 | Markdown 渲染 | 已声明（mvp/chat-ui 使用） |
| 仅测试（不进 APK）：JUnit 4、kotlinx-coroutines-test、OkHttp MockWebServer、AndroidX Test、Compose UI Test、JankStats（androidx.metrics） | 4.13.2 等 | JUnit 为 EPL-1.0，其余 Apache-2.0 | 单测与仪器测试 | 已声明 |
| Termux terminal-emulator / terminal-view v0.118.0 | — | Apache-2.0 | 浮动终端 | 第二期 |
| Shizuku API / provider 13.1.5 | — | MIT | 特权操作 | 第三期 |

## 4. 运行时由用户设备下载、不随 APK 分发的组件

| 组件 | 版本 | 许可证 | 来源 |
|---|---|---|---|
| Ubuntu Base | 24.04.5 | 各软件包各自的许可证（GPL、LGPL、MIT 等） | 阿里云 / 清华 / 中科大 / 华为云镜像（SHA256 钉在 `pins.json`） |
| Node.js | 24.22.2 | MIT（含各依赖的许可证） | npmmirror / 华为云 / 清华 / 阿里云镜像（SHA256 钉在 `pins.json`） |
| `@earendil-works/pi-coding-agent` 及其 `@earendil-works/*` 依赖 | 1.5.0 | MIT | npm（国内用 npmmirror；integrity 钉在 `pins.json`）；源码 https://github.com/earendil-works/pi |

不使用：`@mariozechner/pi-coding-agent`（已 deprecated）、`@agegr/pi-web`。

## 5. 只参考设计、未使用代码的项目

- dsh-Go（https://github.com/ccch1mneyyy/dsh-Go ，AGPL-3.0）：只参考了其公开的设计说明（工具卡片折叠、插话/排队交互、运行时状态机等）。**pi-go 没有拷贝其任何代码或资源**，pi-go 的名称、界面、图标与形象中也不使用该项目的名称、标识或吉祥物。如果将来确需拷贝其代码，pi-go 必须改为 AGPL-3.0，并在此列出拷贝来源与署名。

## 6. 明确不使用的资源

- PiMet 内置桌宠素材 `assets/pets/doro.codex-pet/`、`assets/pets/elaina-2/`：无许可证说明，且涉及第三方角色形象，不引入。
