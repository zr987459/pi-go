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
- 已引入的映射（原路径 → 新路径）：
  - mvp/engine：`zr987459/pimet@a80222c2dda4a4c8a936258b96597bc7ea7d4102:app/src/main/java/com/xm486/pimet/PiMetService.java` → `core/engine/src/main/kotlin/io/github/zr987459/pigo/core/engine/EngineService.kt`（前台服务 + 常驻通知的结构，Kotlin 改写）
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
| PRoot（Termux 分支） | `libproot.so`、`libprootloader.so`、`libprootloader32.so` | GPL-2.0（Termux 打包元数据 `TERMUX_PKG_LICENSE`） | https://github.com/termux/proot ；打包脚本 https://github.com/termux/termux-packages/tree/master/packages/proot | 5.1.107.102（以引入时为准） | 计划引入（mvp/runtime） |
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
| Coil（coil-compose，含传递依赖 coil-compose-base、coil、coil-base；以及 coil-base 引入的 Okio 3.9.0、accompanist-drawablepainter 0.32.0，均为 Apache-2.0；OkHttp 4.12.0 见上） | 2.7.0 | Apache-2.0 | 图片加载：模型/服务商图标、文件页与输入框的图片预览 | 已声明（feature/files、feature/models 使用；W0 feat/modules 登记） |
| AndroidX Browser（Custom Tabs） | 1.8.0 | Apache-2.0 | OAuth 登录在 Custom Tab 中打开授权页（M3） | 已声明（core/catalog、feature/models 使用；W0 登记） |
| AndroidX ExifInterface | 1.3.7 | Apache-2.0 | 读取图片 EXIF 方向，压缩前纠正旋转（C1 图片输入） | 已声明（core/workspace、feature/files 使用；W0 登记） |
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

- dsh-Go：自 2026-11-03 起不再只是参考，界面代码与资源已移植，见 §8。

## 6. 明确不使用的资源

- PiMet 内置桌宠素材 `assets/pets/doro.codex-pet/`、`assets/pets/elaina-2/`：无许可证说明，且涉及第三方角色形象，不引入。

## 7. 移植 / 改写的第三方代码

- agegr/pi-web @ 025e24e（npm @agegr/pi-web 0.14.2），MIT，Copyright (c) 2026 agegr —— 移植/改写（TypeScript → Kotlin，逻辑对照改写，未打包其运行时）：
  - `feature/chat/.../logic/PhaseLabel.kt`（`PhaseLabel`）← `lib/chat-phase-label.ts:11-43`、`lib/tool-execution-progress.ts:1-29`
  - `feature/chat/.../logic/PhaseLabel.kt`（`RetryVisibility`）← `hooks/useAgentSession.ts:1778-1789`
  - `feature/chat/.../ChatController.kt`（`stop()` 先收起并取消阻塞类对话框）← `lib/rpc-manager.ts:1323-1345`
  - `core/store/.../ModelDiscovery.kt` ← `lib/model-discovery.ts:1-135`、`app/api/models-config/discover/route.ts:13-99`
  - `core/store/.../ProviderTester.kt` ← `app/api/models-config/test/route.ts:11`、`:28-121`

- 以下由 W5（feat/wiring）从 `docs/notices/*.md` 并入（各工作流原文件保留作为出处）：
- feat/commands（W4，`docs/notices/feat-commands.md`）：
  - agegr/pi-web@025e24e（npm @agegr/pi-web 0.14.2），MIT，Copyright (c) 2026 agegr —— 移植/改写：`feature/usage/.../logic/CompactionSummary.kt` ← `lib/compaction-summary.ts:1-34`
  - agegr/pi-web@025e24e，MIT，Copyright (c) 2026 agegr —— 移植/改写：`feature/commands/.../data/AutoTitle.kt`（SessionTitle：提示词、对话摘录截断、结果清洗）← `lib/session-title.ts:13-48`、`:131-210`、`:225-270`
  - agegr/pi-web@025e24e，MIT，Copyright (c) 2026 agegr —— 思路移植（续读点 + 头部/采样哈希校验）：`feature/commands/.../data/SessionScanner.kt` ← `lib/session-list-scanner.ts:44-66`、`:140-260`
  - agegr/pi-web@025e24e，MIT，Copyright (c) 2026 agegr —— 思路移植（只认成功的 write/edit 结果、路径去重保序；耗时格式）：`feature/usage/.../logic/TurnSummary.kt` ← `lib/turn-written-files.ts:83-127`、`lib/turn-duration.ts:56-64`
  - earendil-works/pi @earendil-works/pi-coding-agent 1.5.0，MIT —— 对照接口（未拷代码）：edit 工具参数与 details.patch（`dist/core/tools/edit.d.ts`）、提示模板格式（`docs/prompt-templates.md`）、settings.json 资源排除语法（`docs/settings.md`「Resources」）、CLI 参数（`docs/cli.md`）
- feat/models（W2，`docs/notices/feat-models.md`）：
  - earendil-works/pi（npm `@earendil-works/pi-ai@1.5.0`），MIT —— 数据：`core/store/src/main/resources/pigo/catalog-1.5.0.json` 由 `core/catalog/src/main/assets/pigo/pigo-catalog.mjs` 从 `dist/providers/all.js`（`builtinProviders()`）与 `dist/models.js`（`getSupportedThinkingLevels`）导出（42 个服务商、1592 个模型，SHA256 6c1fe7517ded12b37ea08bc9e4caa775c5220e86c7463ac79d67920da8e6a253）
  - earendil-works/pi（npm `@earendil-works/pi-ai@1.5.0`），MIT —— 移植/改写：`core/catalog/.../Pkce.kt` 的 `parseAuthorizationInput` ← `dist/auth/oauth/anthropic.js:20-46`
  - earendil-works/pi（npm `@earendil-works/pi-ai@1.5.0`），MIT —— 参考（未拷贝代码）：`core/store/.../ProviderSpecs.kt` 的环境变量表 ← `dist/env-api-keys.js:68-120` 与 `pi-coding-agent` `docs/providers.md`
  - earendil-works/pi（npm `@earendil-works/pi-coding-agent@1.5.0`），MIT —— 运行时调用（不复制）：`pigo-auth.mjs` 在容器内 import pi 自带的 `ModelRuntime`、`SettingsManager`、`getAgentDir`；`AuthJsonLock.kt` 遵循其 `dist/core/auth-storage.js` 的 proper-lockfile 锁目录约定
- feat/modules（W0，`docs/notices/feat-modules.md`）：无移植代码；新增 Gradle 依赖已并入 §3。
- feat/wiring（W5）：无新增移植；`app/.../nav/WiredPages.kt`、`feature/chat/.../ui/ChatWiring.kt` 为原创接线代码。
- feat/files（W3，`docs/notices/feat-files.md`）：
  - agegr/pi-web@025e24e74fdbc8f46c4e0cf31c6d6b3fa6cf29d5（npm @agegr/pi-web 0.14.2），MIT，Copyright (c) 2026 agegr —— 改写：`core/workspace/.../GitParse.kt`（状态分类思路，改为解析 porcelain v2）← `lib/git-status.ts:10-47`
  - agegr/pi-web@025e24e，MIT —— 移植：`core/workspace/.../GitParse.kt` `addedFilePatch`（未跟踪文件生成「全部新增」diff）← `lib/git-changes.ts:158-176`
  - agegr/pi-web@025e24e，MIT —— 改写：`core/workspace/.../FileTypes.kt`（扩展名 → MIME 表）← `lib/file-types.ts:8-46`
  - agegr/pi-web@025e24e，MIT —— 改写：`feature/files/.../output/Ansi.kt`（ANSI 转义匹配正则）← `lib/ansi.ts:1-3`
- feat/engine-rpc（W1）合入 main 后，其 `docs/notices/*.md` 在此追加。

## 8. dsh-Go 界面移植（ui/dsh-style、ui/dsh-panels）

- 来源：https://github.com/ccch1mneyyy/dsh-Go （默认分支，2026-11-03 取），**AGPL-3.0**。所有者自用、不分发，已明确同意直接移植其 UI 代码与资源（2026-11-03）。dsh-Go 的界面是 Flutter（Dart），pi-go 按其组件结构、尺寸、配色、动效参数逐项翻译为 Kotlin/Compose。dsh-Go 的部分主题代码自身注明源自 PiPilot（https://github.com/CikeSeven/pi_pilot ，MIT，Copyright (c) 2026 CikeSeven）。
- 移植的代码（dsh-Go 路径 → pi-go 路径）：
  - `app/lib/ui/theme/palette.dart`、`accent.dart`（黛青 daiqing）、`surfaces.dart` → `core/ui/.../DshPalette.kt`、`DshTokens.kt`（`DshColors`、`GlassStyle`）
  - `app/lib/ui/theme/shapes.dart`、`motion.dart` → `core/ui/.../DshTokens.kt`（`AppShape`、`AppSpace`、`AppMotion`）
  - `app/lib/ui/theme/typography.dart` → `core/ui/.../Type.kt`
  - `app/lib/ui/theme/backdrop.dart`、`glass.dart`、`ui/common/glass_pill.dart`、`status_dot.dart`、`status_chip.dart`、`pressable.dart` → `core/ui/.../Components.kt`
  - `app/lib/ui/theme/dsh_glyphs.dart`、`app_icons.dart` → `core/ui/.../AppIcons.kt`（生成）
- 拷贝的资源：
  - `app/assets/fonts/DshIcons.ttf` → `core/ui/src/main/res/font/dsh_icons.ttf`（字形来自 Phosphor Icons 2.1.1，MIT，Copyright (c) 2023 Phosphor Icons）
  - `app/assets/fonts/JetBrainsMono-{Regular,Medium,Bold}.ttf` → `core/ui/src/main/res/font/jetbrains_mono_*.ttf`（JetBrains Mono，SIL OFL 1.1）
- **未拷贝**：吉祥物（鲸娘、Deepy 小鲸鱼）、品牌标识与鲸鱼尾图形（dsh-Go `ASSETS.md` 声明不在 AGPL 授权内；所有者 2026-11-03 要求先不用）。
- 后续工作流（聊天页、抽屉、设置、引导页）移植的文件在此追加。
  - ui/dsh-panels（抽屉内容、设置、安装引导；只翻译界面结构与尺寸，未拷贝资源）：`app/lib/ui/shell/sessions_drawer.dart`、`shell/drawer/session_tile.dart`、`shell/drawer/drawer_filter_bar.dart`、`shell/session_search_page.dart`、`ui/common/capsule_chip.dart`、`state/drawer_view_state.dart`（按日分组）→ `feature/sessions/.../SessionsScreen.kt`、`DrawerStyle.kt`、`DrawerGrouping.kt`；`app/lib/ui/settings/settings_widgets.dart`、`settings_screen.dart`、`background_guide_page.dart`、`ui/common/app_sheet.dart`、`overview_card.dart`、`shell/page_frame.dart` → `feature/settings/.../ui/DshComponents.kt`、`SettingsScreen.kt`、`BackgroundGuide.kt`；`app/lib/ui/onboarding/onboarding_scaffold.dart`、`local_install.dart`、`runtime_setup_page.dart`、`setup_progress.dart`、`ui/common/progress_halo.dart` → `feature/onboarding/.../OnboardingUi.kt`、`OnboardingScreen.kt`。
