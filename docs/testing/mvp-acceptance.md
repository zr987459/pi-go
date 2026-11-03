# pi-go MVP 验收记录（mvp/integration）

> 依据：`docs/DESIGN.md` §5 全表、§6 integration 行；`docs/piweb-study.md` §3.2 / §4.2。
> 记录时间：2026-11-03（CST）。分支 `mvp/integration`，基线 `main@635dfdc` + 普通 merge `mvp/engine`（PR #5，尚未合入 main）。
> **结论：MVP 代码已全部接为真实实现，CI 编译通过并产出 debug APK；§5 所列真机项目尚未在规定设备上走过，不打 `v0.1.0`，由仓库所有者真机验收后决定。**

## 0. 状态图例

| 标记 | 含义 |
|---|---|
| ✅ CI/单测已验证 | 有 JVM 单测或 CI 构建作证据（链接到测试文件/CI 运行） |
| 📱 需真机验证 | 逻辑已实现并接线，但验收标准要求在真机/国内网络/模拟器上观察，本环境（无 Android 设备、aarch64 Linux 沙箱）做不到 |
| ⚠️ 部分 | 一部分已验证，其余见说明 |
| ❌ 未实现 | 本次未做，见「遗留问题」 |

证据链接前缀：`B = https://github.com/zr987459/pi-go/blob/mvp/integration/`（下文写作 `B/路径`）。

## 1. 装配与构建

| 项 | 状态 | 证据 |
|---|---|---|
| `BindingMode` 四组全部为 REAL | ✅ | `B/app/src/main/java/io/github/zr987459/pigo/di/BindingMode.kt`；仪器测试 `AppSmokeTest.allBindingsAreReal` |
| 导航接真实页面（安装向导 / 聊天 / 会话 / 设置），不再走 `*Placeholder` | ✅（编译）📱（运行） | `B/app/src/main/java/io/github/zr987459/pigo/nav/PiGoNavHost.kt`；`B/app/src/androidTest/java/io/github/zr987459/pigo/AppSmokeTest.kt` |
| 端到端冒烟（androidTest，CI 只编译） | ✅ 编译 / 📱 运行 | `AppSmokeTest`：启动页按运行环境状态选择、四个顶层页可达、「去设置模型」定位到「模型与 Key」。真机命令：`./gradlew :app:connectedDebugAndroidTest` |
| N6 `assembleDebug`（JDK 17）+ debug APK | ✅ | 见 PR 中最新 CI 运行（Android debug build） |

## 2. §5 全表

### 5.1 首启安装（W1 runtime）

| 编号 | 状态 | 证据 / 说明 |
|---|---|---|
| F1 预检三项、< 3 GB 禁止开始 | ⚠️ 单测✅ / 界面📱 | `B/feature/onboarding/src/test/.../OnboardingTest.kt`、`B/core/runtime/src/test/.../MiscRuntimeTest.kt` |
| F2 国内镜像、≤ 15 分钟 | 📱 | 需无代理国内网络真机计时；镜像表 `core/runtime/src/main/assets/mirrors.json` |
| F3 切后台/锁屏 5 分钟不中断，通知显示步骤 | 📱 | |
| F4 断网恢复后续装、不重下已校验文件 | ⚠️ 单测✅ / 真机📱 | `B/core/runtime/src/test/.../DownloaderTest.kt`（同 URL 续传、换镜像删 `.part`） |
| F5 改坏 `.part`/哈希不符 → 换镜像、删除坏文件 | ⚠️ 单测✅ / 真机📱 | `DownloaderTest`、`DomesticInstallerTest` |
| F6 关于页版本 + `get_state` 握手 | 📱 | 设置页「运行环境与关于」已接 `RuntimeManager.status`（PiGoNavHost `SettingsPage`）；握手由 `EngineBindings.warmUp` 在运行环境 READY 后触发 |

### 5.2 模型与 Key（W4 store/settings）

| 编号 | 状态 | 证据 / 说明 |
|---|---|---|
| C1 三组预设，自定义预填百炼/智谱 baseUrl | ⚠️ 单测✅ / 界面📱 | `B/core/store/src/test/.../ProviderConfigStoreTest.kt`；设置页分组渲染 |
| C2 容器内 grep 不到 Key、`auth.json` 未改动 | ✅ argv / 📱 真机 | 审查 R1 修复后：`ProotLauncher` 把容器内变量（含 Key）放进 proot 宿主进程的环境，容器入口改为 `/usr/bin/env -u <proot 宿主变量名>…`，argv 里只有变量名、没有任何值，`/proc/<pid>/cmdline` 读不到 Key；引擎启动与「测试连接」走同一路径。测试 `B/core/runtime/src/test/kotlin/io/github/zr987459/pigo/core/runtime/MiscRuntimeTest.kt`（`secretsNeverAppearInArgvOnlyInProcessEnv`，直接断言 `buildArgv` 输出）。注意：pi 进程自己的环境变量里仍有 Key（设计如此，`/proc/<pid>/environ` 同用户可读），真机仍需按 C2 在容器内 grep 一遍 |
| C3 `models.json` 的 `$PIGO_KEY_<ID>`、权限 600、保留其他条目 | ✅ | `ProviderConfigStoreTest` |
| C4 重启后 `get_available_models` 列出、`set_model` 写 settings.json、真实 DeepSeek 对话 | 📱 | 设置页已传入 `engine`（保存后自动 `restart`、默认模型从 `models()` 选择） |
| C5 `"  Bearer sk-xxxx  "` 清洗、只显示末 4 位、日志/诊断无完整 Key | ✅ | `SettingsLogicTest.keyCleanAndMask`、`ProviderConfigStoreTest`；新增 `ProviderProbeTest` 断言获取模型/测试连接的错误文案不含 Key |

### 5.3 原生聊天流式对话（W3 chat-ui + W2 engine）

| 编号 | 状态 | 证据 / 说明 |
|---|---|---|
| S1 逐字增长 | ⚠️ 单测✅ / 真机📱 | `B/core/protocol/src/test/.../ChatReducerFixtureTest.kt`；`B/feature/chat/src/test/.../StreamingJankTest.kt` |
| S2 Markdown、代码块可复制 | 📱 | |
| S3 thinking 可折叠「思考中…/已思考 N 秒」 | ⚠️ 单测✅ | `ChatRowsTest` |
| S4 顶栏模型、思考级别、上下文占用 | 📱 | 顶栏读引擎 `ChatState.model/thinkingLevel/contextPercent` |
| S5 旋转/切后台继续增长 | 📱 | 控制器与引擎同寿（`ChatControllers`、`ChatBindings`） |
| S6 发送中→已发送/已排队、失败标红「重试」不自动重发、错误 Key「Key 错误」 | ✅ | `ChatControllerTest`、`LogicMiscTest`；本次新增失败气泡/错误卡「去设置模型」（见 §3 第 2 条） |

### 5.4 工具调用卡片折叠

| 编号 | 状态 | 证据 / 说明 |
|---|---|---|
| T1 bash/read 卡片、转圈计时、耗时 | ⚠️ 单测✅ / 真机📱 | `ChatReducerFixtureTest`（recorded 夹具）、`ChatRowsTest` |
| T2 失败卡片红色、默认展开 | ✅ | `ChatRowsTest` |
| T3 回合折叠摘要 | ✅ | `ChatRowsTest` |
| T4 重新打开会话卡片一致（`get_messages` 重建） | ⚠️ 单测✅ / 真机📱 | `B/core/engine/src/test/.../PiEngineTest.kt` |

### 5.5 中断 / 插话 / 排队

| 编号 | 状态 | 证据 / 说明 |
|---|---|---|
| Q1 生成中发送默认排队、只发一次 | ✅ | `ChatControllerTest`、`PiEngineTest` |
| Q2 长按「插话」 | ⚠️ 单测✅ / 真机📱 | `ComposerLogicTest`、`PiEngineTest` |
| Q3 停止时排队文本回输入框 | ✅ | `ChatControllerTest`、`StopKeepsDraftTest` |
| Q4 有草稿时撤回成「已撤回 n 条」芯片 | ✅ | `ChatControllerTest`、`StopKeepsDraftTest` |
| Q5 abort 不应答 20 s 后只杀自己进程树、回到同一会话 | ⚠️ 单测✅ / 真机📱 | `PiEngineTest`（ScriptedPi）、`B/core/runtime/src/test/.../GuestProcessStopTest.kt` |

### 5.6 会话列表

| 编号 | 状态 | 证据 / 说明 |
|---|---|---|
| L1 列出全部会话、倒序 | ✅ | `SessionRepositoryTest`、`SessionFormatTest` |
| L2 点击切换、新建、不同 cwd 自动重启 | ⚠️ 接线✅ / 真机📱 | 本次接线：会话页 `onOpen → engine.openSession(guestPath, cwd)`、`onNewSession → engine.newSession()`，成功后回到聊天页（`PiGoNavHost.SessionsPage`）；引擎侧见 `PiEngineTest` |
| L3 重命名同步 | ❌ | 会话页（sessions-settings）没有重命名入口；引擎有 `rename()`。见遗留问题 R3 |
| L4 删除（含当前会话，二次确认）进回收站、可恢复 | ⚠️ 单测✅ / 真机📱 | 本次接线：`currentGuestPath = ChatState.sessionFile`，删除当前会话前 `onBeforeDeleteCurrent → engine.newSession()`；`SessionRepositoryTest` 覆盖移入/恢复 |
| L5 冷启动回到上次会话、被系统杀时提示 | ✅ 逻辑 / 📱 真机 | 审查 R1 修复：`EngineBindings.warmUpOnce`（appScope，只做一次）在运行环境 READY 后：上一轮被系统中断且会话文件还在 → 交给 `PiEngine.start` 恢复并提示；否则用 `ResumePolicy.coldStartTarget` 取最近更新的会话 `openSession`，没有会话或失败才普通启动。测试 `B/core/store/src/test/kotlin/io/github/zr987459/pigo/core/store/ResumePolicyTest.kt`；端到端需真机冷启动观察 |

### 5.7 非功能

| 编号 | 状态 | 证据 / 说明 |
|---|---|---|
| N1 空闲不持 WakeLock | ⚠️ 单测✅ / 真机📱 | `B/core/engine/src/test/.../WakeLockGuardTest.kt`；`dumpsys power` 需真机 |
| N2 3 次内自动恢复、诊断页、OOM 识别 | ⚠️ 单测✅ | `PiEngineTest`、`DiagnosticsTest` |
| N3 `:core:protocol` 覆盖率 ≥ 80%、夹具回放 | ⚠️ | 夹具回放 `ChatReducerFixtureTest`；覆盖率统计 CI 未跑，未核实 |
| N4 返回键：聊天页退后台、安装向导确认 | 📱 | `ChatScreen(backToBackground = true)`；向导返回确认在 onboarding |
| N5 国产 ROM 电池白名单/自启动引导、幻影进程说明 | ⚠️ 单测✅ / 真机📱 | `SettingsLogicTest.romGuideContent` |
| N6 assembleDebug + APK + checkLicenseHygiene | ✅ | CI；`B/buildSrc/src/test/.../LicenseHygieneTest.kt` |

## 3. 用户追加要求（本分支新增）

| # | 要求 | 状态 | 证据 |
|---|---|---|---|
| 1 | 聊天页从会话层拿当前会话、从设置层拿模型配置；切会话/改设置后聊天页更新；自定义端点预填经 nav 传到设置页 | ✅ 接线 / 📱 运行 | `PiGoNavHost`：会话页用 `engine.chat.sessionFile` 与 `openSession/newSession`；设置页传 `secrets/engine/runtimeStatus/onRepairRuntime/loadCustom = StoreBindings.customPrefill(...)`；聊天页 `ChatPage` 在 `chat.model` 变化时重读 `providerConfig.configured()`，未配置时顶部显示「还没有配置模型 · 去设置模型」 |
| 2 | 出错时「去设置模型」直达设置页对应位置 | ✅ | 错误卡（`ErrorInfo.suggestsSettings` 新增「连不上模型服务」）、失败气泡（`LocalSend.needsSettings`：`NO_MODEL`/`PROVIDER`/可识别的鉴权错误）、未配置横条 → `settings?focus=models`，设置页滚动到「模型与 Key」（`SettingsFocus`、`settingsItemIndex`）。测试：`ChatControllerTest.noModelSendFailureOffersSettings`、`PhaseLabelTest.settingsShortcutForModelProblems`、`SettingsLogicTest.focusIndexPointsAtSections`、`AppSmokeTest.noModelBannerJumpsToModelSettings` |
| 3a | 停止时收起等待中的确认框/提问 | ✅ | `ChatController.stop()` 先本地关闭全部阻塞类对话框、取消通知，并对每个 id 发一次 `answerUi(cancelled=true)`，再 `clear_queue → abort`。测试 `ChatControllerTest.stopClosesPendingBlockingDialogsAndCancelsThemOnce` |
| 3b | 自定义端点填好地址和 Key 后自动获取模型列表，失败清楚提示、允许手填 | ✅ | 审查 R1：`LatestOnly` 闸门只接受最新一次请求的结果，取消异常原样抛出不当失败显示，`discovering` 在 finally 复位、输入变化时先复位（`LatestOnlyTest`）。`core/store/ModelDiscovery`（宿主侧 `GET {baseUrl}/models`，OpenAI/Anthropic/Gemini 格式）；设置页输入停顿 0.8 s 自动获取 + 「获取模型列表」按钮，结果点选加入，失败显示「获取模型列表失败：… 可以手动填写」。测试 `ProviderProbeTest`（5 项）、`SettingsLogicTest.discoveredModelToggleKeepsManualIds` |
| 3c | 设置页「测试连接」显示成功/失败原因 | ✅ 逻辑 / 📱 真 Key | 自定义端点：宿主侧 `POST chat/completions`（16 token、20 s）；内置 Provider：容器内 `pi --print --no-session --provider <id>`（45 s，Key 只经 proot 进程环境，argv 不含 Key，见 C2）；Key 对话框、自定义端点对话框、默认模型卡片各一个按钮。测试 `ProviderProbeTest.testConnectionOkAndBadKey`、`builtInProviderIsTestedWithPiPrintAndKeyOnlyInEnv` |
| 3d | 自动重试提示条在重试成功或结束后及时消失 | ✅ | 审查 R1：`RetryAnchor` 每次 attempt 变化重新记锚点，第 1 次重试流出部分文字后失败、第 2 次重试等待期间黄条仍显示（`PhaseLabelTest.secondRetryStaysVisibleAfterFirstRetryStreamedPartialText`）。界面层 `RetryVisibility`：重试那一轮的新助手消息一出内容就隐藏，回合结束不残留（归约器在 `:core:protocol`，本分支不改）。测试 `PhaseLabelTest.retryBarHidesOnceRetryStartsProducing` |
| 3e | 发送到首字之间的阶段提示 | ✅ | `PhaseLabel`：正在启动引擎… / 正在连接模型… / 等待回复… / 正在思考… / 正在运行 <工具>：<最后一行输出> / 正在压缩上下文（n 条排队）；显示在输入框上方状态区。测试 `PhaseLabelTest`（4 项）、`ChatControllerTest.phaseHintAppearsBetweenSendAndFirstToken` |
| 4 | 尽量补单测，CI 绿 | ✅ | 见 PR 描述的本地测试结果 |
| 5 | 进度标记 | ✅ | `B/docs/status/integration.md` |

## 4. piweb-study 「落点 = integration」与 §4.2 检查清单

| 条目 | 状态 | 说明 |
|---|---|---|
| G3 内置 Provider 用 `pi --print` 测试 | ✅ 逻辑 / 📱 | `ProviderTester.testBuiltIn`，经 `StoreBindings.testConnection` 接到设置页 |
| G7 通知点击跳转、Android 13 通知权限申请 | ❌ | 回合完成通知通道属 engine（`EngineService`，本分支禁改 `core/engine/**`），engine 尚未加；运行时 `POST_NOTIFICATIONS` 申请需改 `MainActivity`（scaffold 文件）。见 R1 |
| 真机：工具执行中弹 confirm 时点停止，3 s 内停下、对话框消失 | 📱 | 界面侧已实现（3a）；引擎侧 G1（`PiEngine.stop` 先发 cancelled）由 engine 处理，界面先发不会重复造成问题（pi 对已应答 id 的重复应答忽略，需真机确认） |
| 真机：阶段提示 | 📱 | 3e 已实现 |
| 真机：重试那一轮开始流式时黄条已消失 | 📱 | 3d 已实现 |
| 真机：provider 错误回合过程默认展开（G6） | 📱 | chat-ui 已在 PR #4 处理，本分支未改 |
| 真机：后台完成/出错通知，点击回到会话，Android 13+ 申请权限 | ❌ | 见 R1 |
| 设置页：获取模型列表（百炼）、测试连接（错 Key / 对 Key / 内置 DeepSeek） | ✅ 逻辑 / 📱 真 Key | 3b、3c |
| 大会话（>200 KB、中途改名）列表名称正确（G8） | ❌ | 属 sessions-settings 的 `SessionFileParser`，本次未改。见 R4 |
| 移植自 Pi-Web 的文件在 `THIRD_PARTY_NOTICES.md` 有来源 | ✅ | `B/THIRD_PARTY_NOTICES.md` §7 |

## 5. 遗留问题

- **审查 R1 已修**：#1 Key 不进 argv（`core/runtime` ProotLauncher/GuestRunner）、#2 获取模型列表取消竞态、#3 多次重试黄条、#4 L5、#5 打开/新建会话失败给 snackbar；Nit 6–10 全部在范围内处理。

- **R1（需要 engine / scaffold）回合完成通知（G7）**：`EngineService` 尚无 DEFAULT 级「任务通知」通道；通知点击跳转与 Android 13 运行时 `POST_NOTIFICATIONS` 申请需要改 `MainActivity.kt`（scaffold 范围）或由 engine 在服务里申请。需要 engine 处理：完成/出错通知；需要所有者决定：在哪一层申请通知权限。
- **R2（需要 engine）G1 / G4 / G9 / G10 引擎侧**：`PiEngine.stop()` 先对未应答的阻塞类请求发 `cancelled`；`ChatReducer` 在 `AgentStart` 清 `retry`；`stats()` 在 `percent == null` 时置空；过滤 `parentToolCallId`。本分支在界面层做了 G1（收起+cancelled）与 G4（隐藏黄条）的等价处理，引擎改好后界面逻辑仍兼容。
- **R3（sessions-settings）L3 重命名**：会话页没有重命名入口，引擎 `rename()` 已有。需要在 `SessionsScreen` 增加入口（或聊天页顶栏），本次未做。
- **R4（sessions-settings）G8**：会话名只读头尾 64 KB，大会话中途改名可能显示预览。
- **R5「放弃重试」**：契约无 `abortRetry()`，黄条按钮仍走 `stop()`（会撤回排队）。需要 scaffold 在 `core/contract` 加接口。
- **R6 测试连接（内置 Provider）冷启动慢**：容器内首次跑 node + pi 可能接近 45 s 超时，需真机确认是否调大。
- **R7 Pre-existing（非本次引入）**：本地 `:core:store:testDebugUnitTest` 中 `KeystoreSecretStoreJvmTest.cryptoRunsOnInjectedIoDispatcher` 失败——线程名带了协程调试后缀 `fake-io @coroutine#1`，断言用了 `==`。属测试本身对环境敏感，建议改为 `startsWith("fake-io")`。
- **R8 CI 只跑 `assembleDebug`**：单测与 androidTest 不在 CI 中运行，单测结果以本地为准，androidTest 需真机/模拟器。

## 6. 范围外改动说明（详见 PR 描述）

| 文件 | 原因 |
|---|---|
| `app/src/main/java/**/nav/PiGoNavHost.kt`（scaffold） | 导航仍调用各 `*Placeholder`，不改就无法把会话/设置/错误跳转接到真实页面（用户追加要求 1、2） |
| `feature/chat/src/**` | 追加要求 2、3a、3d、3e（用户授权最小改动） |
| `feature/settings/src/**` | 追加要求 2（定位）、3b、3c 的界面 |
| `core/store/src/**`（新增 `ModelDiscovery.kt`、`ProviderTester.kt` 与测试） | 追加要求 3b、3c 的宿主侧实现 |
| `app/src/main/java/**/di/StoreBindings.kt`、`EngineBindings.kt` | 把 3b/3c 回调与引擎预启动接到装配层；R1：冷启动回到上次会话（L5） |
| `core/runtime/src/**`（`ProotLauncher.kt`、`GuestRunner.kt`、`MiscRuntimeTest.kt`） | 审查 R1 #1：环境变量的值（含 Key）不再进 proot 的 argv（用户授权） |
| `core/store/src/**`（新增 `ResumePolicy.kt` 与测试） | 审查 R1 #4：L5 冷启动目标会话的纯逻辑 |
| `THIRD_PARTY_NOTICES.md` | 记录 Pi-Web 移植来源 |
