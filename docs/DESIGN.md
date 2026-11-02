# pi-go 设计文档（定稿 v1.0）

> pi-go 是原生 Android 客户端「pi 编程助手」：在手机上的 PRoot Ubuntu 容器里运行 pi coding agent，通过 `pi --mode rpc` 的 stdin/stdout JSONL 通信，界面全部用 Kotlin + Jetpack Compose 原生实现，不再内嵌 PiMet 的 Pi-Web 网页。
>
> - 定稿日期：2026-11-03（CST）
> - 底稿：`docs/proposals/B.md`（投票胜出），按 `docs/votes/V2.md`、`docs/votes/V3.md` 吸收 A、C 的优点并改正事实性错误
> - 核实基线：
>   - pi：npm `@earendil-works/pi-coding-agent@1.5.0` 包内 `docs/{rpc,rpc-commands,rpc-extension-ui,json,message-types,session-format,providers,models-configuration,cli,environment-variables,security,termux}.md`、`dist/modes/rpc/rpc-types.d.ts`、`CHANGELOG.md`、`package.json`；npm registry 两个包名的元数据
>   - PiMet：`zr987459/pimet@a80222c2dda4a4c8a936258b96597bc7ea7d4102`（只读）
>   - DSH-Folk：`IPF-Sinon/DSH-Folk`（GPL-3.0，仅用于许可证比对）
> - 章节号是协作约定：REVIEW.md 引用的「工作流文件范围」在 §6，跨工作流接口在 `:core:contract`（§2）。

---

## 0. 决策记录

### 0.1 三份提案各自的主张

| 方案 | 一句话主张 |
|---|---|
| A | Kotlin + Compose，`pi --mode rpc` 管道直连；把 PiMet 的 PRoot、解压、前台服务原样搬过来，并补上下载 SHA256 校验、`.part`/staging 原子安装、进程树停止、会话回收目录和新手默认值。 |
| **B** | Kotlin + Compose，`pi --mode rpc` 管道直连；先做许可证尽调，重写 PiMet 中派生自 GPL-3.0 DSH-Folk 的代码并弃用其 rootfs，协议严格按 pi 1.5.0 文档归约，跨 cwd 会话用重启进程处理，按模块边界拆 5 条工作流。 |
| C | Kotlin + Compose，`pi --mode rpc` 管道直连；Key 用 Keystore 加密、经环境变量注入不落容器磁盘，pi 装到带版本号的 prefix 便于回滚，`app/di/*Bindings.kt` 按工作流拆分；MVP 范围最大（含图片、双栏、diff）。 |

### 0.2 投票结果

| 投票 | A | B | C | 投给 |
|---|---|---|---|---|
| V1 | — | — | — | **未产出** |
| V2 | 7.5 | **8.5** | 7.0 | B |
| V3 | 7.3 | **8.6** | 6.9 | B |

结论：**B 以 2:0 胜出**，本文以 B 为底稿。

### 0.3 吸收的优点

| # | 来源 | 吸收内容 | 投票依据 | 落在本文 |
|---|---|---|---|---|
| 1 | A | Ubuntu Base 用同目录 `SHA256SUMS`、Node 用 `SHASUMS256.txt` 校验；本文进一步把期望哈希钉进 App 的 `pins.json`，镜像清单只作交叉核对 | V2 §四.4；V3 §3「从 A」1 | §4.3 |
| 2 | A | 每个文件下载到 `<name>.part`，**换镜像时删掉 `.part` 重下**，不把不同来源拼进同一文件 | V2 §四.4；V3 §3「从 A」1 | §4.3 |
| 3 | A | 解压到 `staging/` 后**原子重命名** | V2 §四.4；V3 §3「从 A」1 | §4.3 |
| 4 | A | 可用空间预检，阈值取 A 的 3 GB（B 原为 2.5 GB） | V2 §四.4；V3 §3「从 A」1 | §4.3 |
| 5 | A | 删除会话移入回收目录 `~/.pi/agent/sessions-trash/`；删除当前会话前先 `new_session` | V2 §四.4；V3 §3「从 A」2 | §3.8、§5 L4 |
| 6 | A | 启动环境 `PI_OFFLINE=1`、`PI_SKIP_VERSION_CHECK=1`、`PI_TELEMETRY=0`；「刷新模型列表」时临时不带 `PI_OFFLINE` | V2 §四.6；V3 §3「从 A」3 | §3.2 |
| 7 | A | 停止进程**只杀自己启动的进程树**，禁止 `pkill node` | V3 §3「从 A」5 | §3.2、§4.5 |
| 8 | A | 生成中发送**默认「排队」**（followUp），「插话」放在长按/次要按钮；停止键在输入框为空时出现；`clear_queue` 拿回的文本**不覆盖用户正在写的新草稿** | V2 §四.5；V3 §3「从 A」7 | §3.7 |
| 9 | A | 启动失败按 stderr 归类成中文一句话提示 | V2 §四.4 | §3.9 |
| 10 | A | 扩展对话框兜底：未实现的 method 立即回 `{"cancelled":true}`，绝不让 pi 挂起 | V3 §3「从 A」4 | §3.6 |
| 11 | A | 新手细节：Key 粘贴自动去空格和 `Bearer ` 前缀；返回键行为（聊天页退后台不结束服务，向导中返回要确认）写进验收 | V3 §3「从 A」6 | §5 C5、N4 |
| 12 | C | 装配层按工作流拆文件：`app/di/RuntimeBindings.kt`、`EngineBindings.kt`、`StoreBindings.kt`、`ChatBindings.kt` 各归一条工作流；`libs.versions.toml`/`settings.gradle.kts` 只归 scaffold；加 `mvp/integration` 只负责从 Fake 切到真实实现 | V2 §四.1；V3 §3「从 C」2 | §2.3、§6 |
| 13 | C | **Key 不落容器磁盘**：内置 Provider 用环境变量注入；自定义端点在 `models.json` 写 `"apiKey":"$PIGO_KEY_<ID>"`；宿主侧 Android Keystore（AES-GCM）加密保存。替换 B 的「明文写 auth.json」，从二期提前到 MVP | V2 §四.2；V3 §3「从 C」1 | §3.10、§5 C1–C4 |
| 14 | C | pi 装到带版本号的 prefix，`/usr/local/bin/pi` 软链到 `current`；升级先冒烟（`--version` + RPC 握手 + `get_available_models`）再切软链，保留最近 2 个版本 | V2 §四.3；V3 §3「从 C」3 | §4.3、§4.6 |
| 15 | C | 安装验证增加 `pi --mode rpc --no-session` + `get_state` 握手 | V3 §3「从 C」4 | §4.3 |
| 16 | C | 验收设备至少一台国产 ROM 真机；写明 Android 12+ 幻影进程限制和电池白名单/自启动引导。C 的图片、双栏、diff、「立即发送」一律推到二期 | V2 §四.7；V3 §3「从 C」5 | §5、§7 |
| 17 | 投票新增 | Provider 预设拆成「按量 API」与「编程套餐」，并提供「自定义 OpenAI 兼容」预填 baseUrl，套餐类文案写明「需要套餐 Key」 | V2 §三.1.2、§四.8；V3 §4.2 #8–9 | §3.10 |
| 18 | B 自修 | `jniLibs` 只归 W1（`core/runtime/src/main/jniLibs/`），scaffold 不放二进制 | V3 §3「B 自身需修」 | §6 |
| 19 | B 自修 | `--max-old-space-size` 改为按设备内存取 768 或 1024，并在崩溃诊断中识别 heap OOM | V2 §二 B 缺点；V3 §3「B 自身需修」 | §3.2、§3.9 |
| 20 | B 自修 | 冷启动「中断检测」改用 App 自记的运行标记 | V2 §四.9；V3 §4.2 #10 | §3.9 |

### 0.4 改正的事实性错误

| # | 出处 | 原说法 | 事实 | 本文处理 |
|---|---|---|---|---|
| 1 | A、C（及旧 `THIRD_PARTY_NOTICES.md` §1） | `TarGzipExtractor.java`、`SafeFiles.java`「原样搬」 | PiMet `TarGzipExtractor.java`（453 行）与 GPL-3.0 的 DSH-Folk 同名文件有 447 行相同（本稿复核：相似度 0.917）；原样搬会让 pi-go 成为 GPL-3.0 衍生作品 | 重写（§4.2）；`SafeFiles` 未找到对应来源，但同样不搬，按需重写 |
| 2 | C | 快速路线读 PiMet `runtime-latest` rootfs | 该 rootfs 由 `build-slim-rootfs.yml` 第 19、46 行从 DSH-Folk release 下载后裁剪再分发 | 弃用（§4.1） |
| 3 | C | `libproroot*.so` 列入「原样搬」又写「核实前不打包」 | 自相矛盾；PiMet Java 代码不引用它们 | 不带（§4.1） |
| 4 | B、C | 下载后不校验 | REVIEW.md 要求 SHA256 校验；PiMet 本身没有任何 `MessageDigest`/sha256 | 钉哈希校验（§4.3） |
| 5 | B、C | 删除会话 = 直接删文件 | REVIEW.md 要求进回收目录；pi 自己的 `/resume` 删除也优先用 `trash` | 回收目录（§3.8） |
| 6 | B | 冷启动若最后一条 assistant `stopReason=="pending"` 则显示「中断」 | `message-types.md`：pi **不会**把 `pending` 消息写进会话 JSONL，此判定永不触发 | 改用运行标记（§3.9） |
| 7 | B | 「四种对外接口」 | 实际列了五种（TUI、print、json、rpc、SDK） | 笔误已改（§1.3） |
| 8 | B | 「每条命令都带 `id`」但停止流程示例不带 | 自相矛盾（`id` 在协议中可选） | 本文所有示例都带 id（§3.4） |
| 9 | B | R10「必要时加 `--no-approve`」 | `-na/--no-approve` 的作用是**忽略**需要信任的项目本地文件（`.pi/` 扩展、技能、设置不加载），不是「自动同意」 | MVP 不加；如加须告知用户（§7 R10） |
| 10 | B | 只说 `new_session` 返回 `data.cancelled` | `switch_session` 同样返回 `data.cancelled`（可被扩展拦截） | 两者都检查（§3.4） |
| 11 | A、B、C | 国内 Provider 预设名「通义千问 / 智谱 / Kimi」「Qwen（China）/ ZAI（China）」 | pi 1.5.0 内置的是套餐端点：`zai-coding-cn`（GLM Coding Plan，`ZAI_CODING_CN_API_KEY`，baseUrl `open.bigmodel.cn/api/coding/paas/v4`）、`qwen-token-plan-cn`（Token Plan，`QWEN_TOKEN_PLAN_CN_API_KEY`）、`kimi-coding`（Kimi For Coding，`KIMI_API_KEY`）；普通智谱开放平台 / 百炼 DashScope Key 填进去会失败 | 预设拆类（§3.10） |
| 12 | A | 「不加 `--ignore-scripts`，否则需要 postinstall 的依赖会装残」 | 无依据：1.5.0 依赖里没有需要 install 脚本的原生模块（图像处理是 wasm 的 `photon-node`）；pi 官方 `docs/termux.md` 的安装命令就是 `npm install -g --ignore-scripts @earendil-works/pi-coding-agent` | 保留 `--ignore-scripts`，升级时复查（§4.6） |
| 13 | A | 「1.5.0 起 `message_update` 去掉累计快照、只发 delta」 | 该变更在 **0.84.0（2026-08-06）** | 结论不变，版本归属改正（§3.5） |
| 14 | A | 「1.5.0 每写一条记录都等 stdout 背压」 | 1.5.0 CHANGELOG 无此条；`rpc.md` 只写「Pi honors stdout backpressure」 | 行为要求不变（持续读 stdout），不写版本归属（§3.3） |
| 15 | A | `partialResult`「bash 的本身是累计的，要替换」当作通用规则 | `json.md`：替换还是追加取决于各工具的结果约定；对内置 bash 替换是对的 | 只对内置工具按约定处理（§3.5） |
| 16 | A | `tool_execution_start{…, parentToolCallId?}` | 字段存在（工具嵌套调用时出现），但 `json.md` 事件表未列出 | 按可选、未文档化字段处理（§3.5） |
| 17 | A | `ProviderConfigStoreImpl` 放在纯 JVM 的 `:core:rpc`，却要用 AndroidX Security | 纯 JVM 模块无法依赖 Android Keystore；且 `security-crypto` 的 EncryptedSharedPreferences 已被 Google 弃用 | `SecretStore` 放 Android 模块 `:core:store`，直接用 Keystore + AES-GCM（§2.2） |
| 18 | C | Shizuku API 许可证 Apache-2.0 | `RikkaApps/Shizuku-API` 为 **MIT** | 已改（THIRD_PARTY_NOTICES） |
| 19 | C | 启动参数 `-e /root/.pi-go/ext/*.ts` | `ProcessBuilder` 不经 shell，通配符不展开 | 逐个 `-e <path>` 或放进 `~/.pi/agent/extensions/` 自动发现（§3.2） |
| 20 | C | `ChatRepository` 接口写在 `:feature:chat`、实现在 core；`AgentHost` 用 `:core:data` 但依赖图里二者平级 | 违反「core 不依赖 feature」，依赖方向不自洽 | 所有跨模块接口统一放 `:core:contract`（§2） |
| 21 | C | 「排队消息立即发送」靠 `clear_queue` 再全部重发模拟 | 容易丢或重（REVIEW.md 必报项） | 不进 MVP（§5 不做清单） |

### 0.5 本稿核实中新增的发现

- **PiMet 的 PRoot 二进制与 DSH-Folk 逐字节相同**：PiMet `app/src/main/jniLibs/arm64-v8a/` 下的 `libproot.so`、`libprootloader.so`、`libprootloader32.so`、`libtalloc.so`、`libandroidshmem.so` 与 DSH-Folk `app/libs/arm64-v8a/` 同名文件 SHA256 完全一致（例：`libproot.so` 均为 `cbde86eb…f721`，内嵌版本串 `v5.1.107.91`）。这些二进制本身的许可证来自上游（proot GPL-2.0-or-later、talloc LGPL-3.0-or-later、libandroid-shmem BSD-3-Clause），并不因此变成 GPL-3.0，但**我们拿不到它们确切的对应源码与构建记录**，无法履行 GPL-2.0 的源码提供义务。结论：**pi-go 不从 PiMet 拿任何 `.so`**，改从 Termux 官方包获取并钉版本和哈希（§4.1、§4.4）。
- PiMet `buildProotProcess` 默认把整个 `/storage/emulated/0` 挂进容器（`/sdcard`），pi 的工具可读写手机全部共享存储。REVIEW.md 把「容器内可读到不该给 pi 的宿主数据」列为必报。MVP **默认不挂载共享存储**，只挂 App 专属外部目录到 `/root/shared`（§4.4）。
- `@mariozechner/pi-coding-agent`（仓库 `badlogic/pi-mono`）在 npm 上停在 0.73.1，并标注 deprecated：「please use @earendil-works/pi-coding-agent instead going forward」。现行包是 `@earendil-works/pi-coding-agent`，仓库 `github.com/earendil-works/pi`（`packages/coding-agent`），latest = **1.5.0**（2026-11-02 发布，MIT，`engines.node >=22.19.0`，bin `pi → dist/bundle/cli.js`），npmmirror 已同步 1.5.0。

---

## 1. 技术栈及理由

### 1.1 选择

| 层 | 选择 | 理由 |
|---|---|---|
| 语言 / UI | **Kotlin + Jetpack Compose（Material 3）** | PiMet 是 100% Java + View（77 个 `.java`、0 个 `.kt`）；Kotlin 与 Java 同在 JVM，二期要复用的终端、App 桥、无障碍、桌宠等 Java 类可以直接放进模块。Flutter 每个复用类都要经 MethodChannel 包装，终端 View 还要走 PlatformView，复用率最低，而 iOS 又跑不了 PRoot，跨平台收益接近 0 |
| 引擎通信 | **`pi --mode rpc`**，经 PRoot 子进程的 stdin/stdout 管道，JSONL | pi 官方为「其他语言写的自定义界面」设计的接口；插话/排队/中断/会话切换/扩展 UI 都已有；管道只对 App 可见，无需端口和鉴权 |
| 引擎版本 | **`@earendil-works/pi-coding-agent@1.5.0`**（钉死，含 npm integrity） | 见 §0.5 |
| 容器 | PRoot（Termux 官方构建）+ Ubuntu Base 24.04.5 + Node.js 24.22.2（LTS） | 沿用 PiMet 已验证的运行方式；二进制来源换成可追溯的 Termux 官方包 |
| 依赖注入 | 手写 `AppGraph` + 按工作流拆分的 `*Bindings.kt`，不用 Hilt | 少一层 kapt/ksp，构建快；文件级归属便于并行 |
| JSON | kotlinx.serialization（`JsonObject` 宽松解析） | 未知字段和未知事件不崩 |
| 解压 | Apache Commons Compress（Apache-2.0） | 替代 GPL 派生的 `TarGzipExtractor` |
| 下载 | OkHttp 4.12（Apache-2.0） | 断点续传、HEAD 测速 |
| Markdown | `com.mikepenz:multiplatform-markdown-renderer-m3`（Apache-2.0） | Compose 原生渲染 |
| 存储 | DataStore（偏好）、Android Keystore AES-GCM（Key） | `security-crypto` 已弃用，不用 |

**取舍**：放弃 Flutter 的跨 ROM 渲染一致性和现成聊天组件，换来对 PiMet Java 资产的直接复用、单一 Gradle 工具链和更小的 APK（Compose 约 +4–6 MB，Flutter 引擎约 +8–10 MB/ABI）。

### 1.2 构建基线（受 `.github/workflows/android.yml` 约束）

- CI：`ubuntu-latest`、**JDK 17**（temurin）、`gradle/actions/setup-gradle@v4`、`./gradlew --no-daemon --stacktrace assembleDebug`，45 分钟超时；仓库里有 `gradlew` 才会构建；push 到 main 时把 debug APK 发布到 `nightly` 预发布。因此：
  - 工程必须用仓库内的 Gradle wrapper，并在 JDK 17 下 `assembleDebug` 通过；
  - 只产出 debug APK（CI 不做签名）；
  - 单测不在现有 CI 里跑。要跑需改 `.github/`，属 REVIEW.md 必须单独指出的改动，由仓库所有者决定；MVP 阶段各工作流在 PR 描述里贴本地 `testDebugUnitTest` 结果。
- 版本：Gradle 8.11.1、AGP 8.7.3、Kotlin 2.1.x（Compose 编译器插件 `org.jetbrains.kotlin.plugin.compose`，具体补丁版本由 scaffold 写进 `libs.versions.toml`），Java/Kotlin target 17。
- `compileSdk 35`、`minSdk 26`、**`targetSdk 28`**（与 PiMet、Termux 相同，避开 Android 10+ 对 App 私有目录 exec 的限制；不上架 Play，长期风险见 §7 R1）。
- ABI：`arm64-v8a`（真机）、`x86_64`（模拟器）；`packaging.jniLibs.useLegacyPackaging = true`（让 `lib*.so` 解压到可执行的 `nativeLibraryDir`）。
- 仓库：google、mavenCentral、阿里云 Maven 镜像；jitpack 只在二期引入 Termux 终端库时加。
- applicationId / 根包：`io.github.zr987459.pigo`。

### 1.3 pi 的五种对外接口（为什么是 RPC）

交互 TUI、`--print`、`--mode json`（跑完即退出）、`--mode rpc`（长连接 JSONL）、Node 进程内 SDK。

| 方案 | 结论 |
|---|---|
| RPC | **选用**：一个常驻 node 进程，全部交互语义齐全 |
| JSON 模式 | 不选：每轮冷启动（PRoot 下数秒），无法插话/排队，中断只能 kill |
| pi-web HTTP（`@agegr/pi-web`） | 不选：Next.js 内部路由非对外契约，常驻 300 MB+，正是要去掉的部分 |
| SDK + 自写 Node 桥 | 不进 MVP；二期「多会话并发」时再评估 |

---

## 2. 模块划分与接口

### 2.1 Gradle 模块

```
pi-go/
  settings.gradle.kts / build.gradle.kts / gradle.properties / gradle/ / gradlew*   ← scaffold
  gradle/libs.versions.toml                     ← scaffold（唯一改版本的地方）
  app/                       :app              Application、MainActivity、导航、AppGraph、di/*Bindings.kt
  core/contract/             :core:contract    纯 Kotlin/JVM。全部跨工作流接口、数据类、RPC 记录类型、Fake 实现
  core/ui/                   :core:ui          Compose 主题、通用组件（scaffold 提供，MVP 期间冻结）
  core/protocol/             :core:protocol    纯 Kotlin/JVM。JsonlFramer、记录编解码、ChatReducer
  core/runtime/              :core:runtime     Android。PRoot 启动、安装器、下载、GuestFixups、jniLibs
  core/engine/               :core:engine      Android。PiProcess、RpcClient 实现、EngineService（前台服务）
  core/store/                :core:store       Android。会话索引与回收、SecretStore（Keystore）、Provider 配置写入
  feature/onboarding/        :feature:onboarding  首启安装向导
  feature/chat/              :feature:chat        聊天、工具卡片、插话/排队/停止
  feature/sessions/          :feature:sessions    会话抽屉/列表
  feature/settings/          :feature:settings    模型与 Key、运行环境、关于
```

二期模块（scaffold 只在 `settings.gradle.kts` 里注释列出，不建目录）：`:feature:terminal`、`:core:bridge`、`:feature:pet`、`:feature:web`。

依赖方向（只能向下）：

```
app → feature:* → core:ui, core:contract
app → core:engine, core:runtime, core:store, core:protocol      （只在 di/*Bindings.kt 里引用实现）
core:engine → core:protocol → core:contract
core:engine → core:contract（RuntimeManager 接口，不依赖 core:runtime 实现）
core:runtime, core:store → core:contract
feature 之间互不依赖；feature 不依赖任何 core 实现模块
```

这样 C 被指出的两个问题都不会出现：接口不放 feature 里；`engine` 需要的「启动环境」通过 `:core:contract` 的 `LaunchEnvProvider` 接口拿，由 `:core:store` 实现，二者不互相依赖。

### 2.2 `:core:contract` 中的接口（scaffold 落地，带 Fake）

```kotlin
// ---- 通用 ----
sealed interface Outcome<out T> {
  data class Ok<T>(val value: T) : Outcome<T>
  data class Err(val code: ErrCode, val message: String, val cause: Throwable? = null) : Outcome<Nothing>
}
enum class ErrCode { NETWORK, DISK_FULL, CHECKSUM, INSTALL_FAILED, ENGINE_START, ENGINE_EXIT,
                     RPC_REJECTED, RPC_TIMEOUT, PROVIDER, NO_MODEL, OOM, CANCELLED, UNKNOWN }
interface AppLog { fun i(tag: String, msg: String); fun w(tag: String, msg: String, t: Throwable? = null)
                   fun tail(n: Int): List<LogLine> }               // 写入前统一脱敏（sk-…、Bearer …、已知 Key）

// ---- 运行环境 ----
enum class Phase { NOT_INSTALLED, INSTALLING, READY, BROKEN }
data class RuntimeStatus(val phase: Phase, val ubuntu: String?, val node: String?, val pi: String?, val error: String?)
enum class InstallStep { PREFLIGHT, PROBE, ROOTFS, NODE, FIXUPS, APT, PI, VERIFY }
data class InstallProgress(val step: InstallStep, val percent: Int, val bytesPerSec: Long?, val message: String)
data class GuestSpec(
  val argv: List<String>,                     // 例：["pi","--mode","rpc","--session","/root/.pi/agent/sessions/…jsonl"]
  val cwd: String = "/root/pi-cwd",
  val env: Map<String, String> = emptyMap(),  // 经 /usr/bin/env 传入容器，不写文件
)
interface GuestProcess {                      // 包装 java.lang.Process，带「只杀自己进程树」的停止
  val stdin: java.io.OutputStream; val stdout: java.io.InputStream; val stderr: java.io.InputStream
  val prootPid: Int
  suspend fun awaitExit(): Int
  suspend fun stop(graceMs: Long = 5_000)     // 关 stdin → 等 → SIGTERM 进程树 → SIGKILL 进程树
}
interface RuntimeManager {
  val status: kotlinx.coroutines.flow.StateFlow<RuntimeStatus>
  fun install(): kotlinx.coroutines.flow.Flow<InstallProgress>           // 冷流；取消即中止；可重入
  suspend fun repair(): Outcome<Unit>                                     // 只重跑缺失/损坏的步骤
  suspend fun upgradePi(version: String): Outcome<String>                 // 版本化 prefix + 冒烟 + 切软链，失败回滚
  fun start(spec: GuestSpec): GuestProcess
  suspend fun exec(cmd: List<String>, env: Map<String, String> = emptyMap(), timeoutMs: Long = 60_000): ExecResult
  fun hostFile(guestPath: String): java.io.File                           // /root/x → files/rootfs/root/x
}

// ---- 引擎 ----
enum class Disposition { STARTED, QUEUED, HANDLED }
enum class SendMode { FOLLOW_UP, STEER }      // 生成中发送的两种方式，默认 FOLLOW_UP
data class QueueSnapshot(val steering: List<String>, val followUp: List<String>)
sealed interface EngineStatus {
  data object Stopped : EngineStatus; data object Starting : EngineStatus
  data class Idle(val state: PiState) : EngineStatus; data class Running(val state: PiState) : EngineStatus
  data class Crashed(val exitCode: Int?, val kind: ErrCode, val hint: String, val stderrTail: List<String>, val retryInMs: Long?) : EngineStatus
}
interface EngineClient {
  val status: kotlinx.coroutines.flow.StateFlow<EngineStatus>
  val chat: kotlinx.coroutines.flow.StateFlow<ChatState>        // 已归约的界面状态，Activity 重建不丢
  val uiRequests: kotlinx.coroutines.flow.SharedFlow<ExtUiRequest>
  suspend fun send(text: String, mode: SendMode = SendMode.FOLLOW_UP): Outcome<Disposition>  // 空闲时忽略 mode
  suspend fun stop(): Outcome<QueueSnapshot>                    // clear_queue → abort；返回撤回的文本
  suspend fun newSession(cwd: String? = null): Outcome<Unit>
  suspend fun openSession(guestPath: String, cwd: String): Outcome<Unit>   // 同 cwd 用 switch_session，否则重启
  suspend fun rename(name: String): Outcome<Unit>
  suspend fun models(): Outcome<List<ModelInfo>>
  suspend fun setModel(provider: String, modelId: String): Outcome<ModelInfo>
  suspend fun thinkingLevels(): Outcome<List<String>>
  suspend fun setThinking(level: String): Outcome<Unit>
  suspend fun stats(): Outcome<SessionStats>
  suspend fun answerUi(id: String, value: String? = null, confirmed: Boolean? = null, cancelled: Boolean = false)
  suspend fun restart(reason: String): Outcome<Unit>            // 重新读取 LaunchEnvProvider
}

// ---- 存储与配置 ----
data class SessionSummary(val guestPath: String, val id: String, val cwd: String, val name: String?,
                          val preview: String, val createdAt: java.time.Instant, val updatedAt: java.time.Instant, val sizeBytes: Long)
interface SessionRepository {
  fun observe(): kotlinx.coroutines.flow.Flow<List<SessionSummary>>
  suspend fun refresh()
  suspend fun moveToTrash(guestPath: String): Outcome<String>    // 返回回收目录中的新路径
  suspend fun restore(trashPath: String): Outcome<String>
  fun observeTrash(): kotlinx.coroutines.flow.Flow<List<SessionSummary>>
}
enum class PresetKind { PAY_AS_YOU_GO, CODING_PLAN, CUSTOM_OPENAI }
data class ProviderPreset(val provider: String, val label: String, val kind: PresetKind,
                          val envVar: String?, val defaultBaseUrl: String?, val note: String)
interface SecretStore {                                          // Android Keystore + AES-GCM，只在宿主
  suspend fun put(alias: String, plain: String); suspend fun get(alias: String): String?
  suspend fun remove(alias: String); suspend fun last4(alias: String): String?
}
interface ProviderConfigStore {
  fun presets(): List<ProviderPreset>
  suspend fun setBuiltInKey(provider: String, rawKey: String)    // 去空格/去 "Bearer " 后存 SecretStore
  suspend fun upsertCustom(id: String, baseUrl: String, rawKey: String, modelIds: List<String>) // 写 models.json，apiKey="$PIGO_KEY_<ID>"
  suspend fun setDefault(provider: String, modelId: String, thinking: String?)              // 合并写 settings.json
  suspend fun configured(): List<String>
}
interface LaunchEnvProvider { suspend fun env(): Map<String, String> }   // 由 :core:store 实现，engine 启动时取
```

### 2.3 装配层 `:app`

- `AppGraph.kt`（scaffold）：持有单例，从四个 Bindings 取实现。
- `di/RuntimeBindings.kt`（W1）、`di/EngineBindings.kt`（W2）、`di/ChatBindings.kt`（W3）、`di/StoreBindings.kt`（W4）：各自提供 `fake()` 与 `real()` 两个工厂。
- `di/BindingMode.kt`（scaffold 创建、全部为 `FAKE`；之后**只归 `mvp/integration`**）：决定每组用 Fake 还是 Real。各工作流在自己分支上可本地把自己那组切到 Real 调试，但不提交该文件。
- `EngineService`（前台服务）在 `:core:engine` 中声明与实现（库 manifest 合并），不占 `app/`。

---

## 3. 与 pi 的通信协议

### 3.1 引擎与版本

| 项 | 值 |
|---|---|
| npm 包 | **`@earendil-works/pi-coding-agent`**（仓库 `github.com/earendil-works/pi`，目录 `packages/coding-agent`，MIT） |
| 锁定版本 | **1.5.0**，npm integrity `sha512-xNDZ5kqowO6Z03FS2gwAkPEGk1ssxVcYKQ6qZId84mFEvwACvvyc2l0vAMOS+c5zvxLRMLBE9r4y44IGYkMJaQ==` |
| Node 要求 | `engines.node >=22.19.0`；pi-go 装 Node 24.22.2 |
| 不使用 | `@mariozechner/pi-coding-agent`（0.73.1，已 deprecated）、`@agegr/pi-web` |
| 兼容范围 | App 声明 `>=1.5.0 <2.0.0` 才允许一键升级（§4.6） |

`pins.json`（随 APK，scaffold 创建，W1 填哈希）：

```json
{
  "pi":     {"package": "@earendil-works/pi-coding-agent", "version": "1.5.0",
             "integrity": "sha512-xNDZ5kqowO6Z03FS2gwAkPEGk1ssxVcYKQ6qZId84mFEvwACvvyc2l0vAMOS+c5zvxLRMLBE9r4y44IGYkMJaQ==",
             "compat": ">=1.5.0 <2.0.0"},
  "node":   {"version": "24.22.2",
             "sha256": {"arm64": "95c264150aceb7f3437a29fd6984924f5f8d1511ea3cbd6c508b83f6c555dae2",
                        "x64":   "361718c444e49af2ca5a110c83ba6a8901143f23ab285ebbef0341f391f482a9"}},
  "ubuntu": {"version": "24.04.5",
             "sha256": {"arm64": "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
                        "amd64": "e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86"}}
}
```

（Node 哈希取自 nodejs.org 与 npmmirror 的 `SHASUMS256.txt`，两处一致；Ubuntu 哈希取自 `cdimage.ubuntu.com` 官方 `SHA256SUMS`，与阿里云镜像清单一致。2026-11-03 核对。）

### 3.2 进程模型与启动

```
EngineService（前台服务）
  └─ PiProcess
       GuestProcess = RuntimeManager.start(GuestSpec(
         argv = ["pi","--mode","rpc"] (+ ["--session", <guestPath>] 打开指定会话时),
         cwd  = 会话 cwd（默认 /root/pi-cwd）,
         env  = LaunchEnvProvider.env() + {
           PI_CODING_AGENT_DIR=/root/.pi/agent,
           PI_OFFLINE=1, PI_SKIP_VERSION_CHECK=1, PI_TELEMETRY=0,
           NODE_OPTIONS=--max-old-space-size=<768 | 1024>   // 设备总内存 < 6 GB 取 768，否则 1024
         }))
       stdout → 专用读线程（字节块）→ JsonlFramer → JSON → Channel<Record>
       stderr → 专用读线程排空 → 环形缓冲 500 行（脱敏）→ AppLog；不当协议解析
       stdin  ← 单写协程（Mutex），每条 = JSON + "\n"，写后 flush
```

- `LaunchEnvProvider.env()` 返回内置 Provider 的 Key 环境变量（如 `DEEPSEEK_API_KEY`）和自定义端点的 `PIGO_KEY_<ID>`，由宿主 Keystore 解密后只存在于进程环境，不写容器文件（§3.10）。
- `PI_OFFLINE=1` 会关闭模型目录自动刷新。设置页「刷新模型列表」用 `RuntimeManager.exec(["pi","update","--models"])` 单独跑一次，这次**不带** `PI_OFFLINE`，完成后 `restart()`。
- 扩展：MVP 不加载额外扩展；将来需要时逐个 `-e <path>`，或放进 `~/.pi/agent/extensions/` 让 pi 自动发现，**不写通配符**。
- 同一时刻只有 1 个 pi 进程。同 cwd 内用 `switch_session`/`new_session`；切到另一 cwd 的会话或「在其他目录新建」时停止进程再以新 cwd 重启（cwd 决定会话目录与 `AGENTS.md`）。
- 就绪：启动后立即发 `{"id":"boot-1","type":"get_state"}`，`success:true` 即 `Idle`；20 s 未回 → `Crashed`（附 stderr 归类结果）。
- 停止：关闭 stdin（官方的有序关闭）→ 5 s 未退出 → 对**本进程树**发 SIGTERM → 3 s → SIGKILL（§4.5）。永远不用 `pkill`/`killall`/`pidof`。
- WakeLock 只在 `agent_start` 到 `agent_settled` 之间持有，空闲释放。

### 3.3 分帧

- 只按 LF（0x0A）切分，去掉行尾可选的 `\r`；**不用**会把 U+2028/U+2029 当换行的读取器（`rpc.md` 明确要求）。
- 持续读 stdout：pi 遵守 stdout 背压，客户端停读会让 pi 卡住。stdin 写入也要尊重背压（单写协程、阻塞写在 IO 调度器上）。
- stdout 只有协议记录；诊断和日志都在 stderr。

### 3.4 命令（客户端 → pi）

所有命令都带客户端生成的唯一 `id`（`c-<自增>`）；`RpcClient` 维护 `id → CompletableDeferred`，**按 id 关联而不是按顺序**（命令是异步处理的）。默认超时：`abort` 20 s，`get_messages` 30 s，其余 15 s。

| 界面动作 | 发送 | 响应要点 |
|---|---|---|
| 空闲时发送 | `{"id":"c-1","type":"prompt","message":"列出当前目录"}` | `data.disposition`：`started`/`queued`/`handled`；`handled` 表示被扩展命令消费，**不要**等 `agent_settled` |
| 生成中发送（默认排队） | `{"id":"c-2","type":"prompt","message":"做完再跑一遍测试","streamingBehavior":"followUp"}` | 生成中不带 `streamingBehavior` 会返回错误 |
| 生成中插话（长按） | `{"id":"c-3","type":"prompt","message":"先别改测试","streamingBehavior":"steer"}` | 当前回合工具执行完、下次调模型前送达 |
| 停止 | `{"id":"c-4","type":"clear_queue"}` 然后 `{"id":"c-5","type":"abort"}` | `clear_queue` 返回 `data.steering[]`、`data.followUp[]`；`abort` 等会话空闲后才响应，**不清空队列**，所以必须先 `clear_queue` |
| 新会话 | `{"id":"c-6","type":"new_session"}` | `data.cancelled==true` 表示被扩展拦截 |
| 打开会话（同 cwd） | `{"id":"c-7","type":"switch_session","sessionPath":"/root/.pi/agent/sessions/--root-pi-cwd--/2026-11-03T06-40-00-000Z_<uuid>.jsonl"}` → `get_messages` → `get_state` | 同样检查 `data.cancelled` |
| 重命名 | `{"id":"c-8","type":"set_session_name","name":"修登录 bug"}` | 随后有 `session_info_changed` 事件 |
| 模型 | `{"id":"c-9","type":"get_available_models"}` / `{"id":"c-10","type":"set_model","provider":"deepseek","modelId":"<id>"}` | 返回完整 Model 对象 |
| 思考级别 | `get_available_thinking_levels` / `{"id":"c-11","type":"set_thinking_level","level":"high"}` | 取值 `off/minimal/low/medium/high/xhigh/max`，以模型实际支持为准 |
| 顶栏统计 | `{"id":"c-12","type":"get_session_stats"}` | `contextUsage.percent` |
| 放弃重试 | `{"id":"c-13","type":"abort_retry"}` | — |
| 扩展对话框应答 | `{"type":"extension_ui_response","id":"<请求 id>","value":"Allow"}` / `"confirmed":true` / `"cancelled":true` | 用请求自己的 id；**没有** response |

说明：`steer` / `follow_up` 专用命令与 `prompt + streamingBehavior` 语义相同，但不允许扩展命令（`/xxx`）。pi-go 生成中统一走 `prompt + streamingBehavior`，一条代码路径同时覆盖普通文本和扩展命令。三者都接受 `images`，MVP 不用。

错误：失败命令回 `{"id":"…","type":"response","command":"set_model","success":false,"error":"…"}`；格式错误回**不带 id** 的 `{"type":"response","command":"parse","success":false,…}`，客户端记日志并作为 bug 上报。

### 3.5 事件（pi → 客户端）与归约

stdout 三类记录：`type:"response"`、会话事件、`type:"extension_ui_request"`。解析用 `JsonObject` + `ignoreUnknownKeys`；未知 `type` → `EngineEvent.Unknown(raw)` 只记日志。字段名全部取自 `json.md` / `message-types.md`。

| 事件 | 字段 | 界面效果 |
|---|---|---|
| `agent_start` | — | `running=true`；持 WakeLock；写运行标记 |
| `turn_start` / `turn_end` | `message`, `toolResults` | 回合分组边界（折叠单位） |
| `message_start` | `message` | user：确认乐观气泡；assistant：新建条目 |
| `message_update` | `assistantMessageEvent`, `usage` | 按 `contentIndex` 维护块：`text_start/delta/end`、`thinking_start/delta/end`、`toolcall_start(id,toolName)/delta/end(toolCall)`；`*_end` 用权威 `content`/`toolCall` 覆盖拼接结果；`start`/`done`/`error` 只更新状态 |
| `message_end` | `message` | 权威消息整体替换；`stopReason`：`error`→错误卡（`errorMessage`）、`aborted`→「已停止」、`length`→「输出被截断」 |
| `tool_execution_start` | `toolCallId`, `toolName`, `args`（可能有未文档化的 `parentToolCallId`，按可选处理） | 工具卡 RUNNING + 计时 |
| `tool_execution_update` | `toolCallId`, `partialResult` | 内置 `bash`/`read`/`edit`/`write` 的 partial 按其约定整体替换；其他工具按「替换」显示并保留最后一次 |
| `tool_execution_end` | `toolCallId`, `result`, `isError`, `durationMs` | OK/ERROR + 耗时，结果默认折叠，出错默认展开 |
| `queue_update` | `steering[]`, `followUp[]`（每次都是**完整**队列） | 输入框上方「待插话/待排队」芯片，整体替换 |
| `auto_retry_start` / `auto_retry_end` | `attempt, maxAttempts, delayMs, errorMessage` / `success, attempt, finalError` | 顶部黄条「第 n/3 次重试」+「放弃重试」 |
| `compaction_start` / `compaction_end` | `reason` / `result, aborted, willRetry, errorMessage` | 时间线分隔条「已压缩上下文」 |
| `session_info_changed` | `name` | 标题与列表更新 |
| `thinking_level_changed` | `level` | 思考级别芯片 |
| `agent_end` | `messages`, `willRetry` | **不**结束 running |
| `agent_settled` | `aborted` | `running=false`；释放 WakeLock；清运行标记；刷新会话列表 |
| `extension_error` | `extensionPath, event, error` | 非阻塞提示 + 日志 |

性能：读线程只投递，归约协程每 33 ms 批量 reduce 后发布 `StateFlow`；`LazyColumn` 用稳定 key（`toolCallId` 或 `messageIndex/contentIndex`）。

（`message_update` 只发 delta、去掉累计快照是 pi **0.84.0** 起的行为，1.5.0 沿用。）

### 3.6 扩展 UI 子协议

- `select`/`confirm`/`input`/`editor` 会阻塞 pi，直到收到同 id 的 `extension_ui_response`：弹原生对话框；用户关闭 = `{"cancelled":true}`；**未实现的 method 立即回 `cancelled`**。带 `timeout` 的对话框显示倒计时（pi 侧到时自动按默认值处理，客户端无需计时）；`editor` 没有 timeout，必须有关闭路径。App 在后台时用通知提醒。
- `notify`→Snackbar；`setStatus`/`setWidget`→输入框上方状态条；`setTitle`→标题；`set_editor_text`→写入输入框（若用户正在输入则询问是否替换）。这些不需要应答。

### 3.7 中断、插话、排队（新手默认值）

- 空闲：发送键 = `prompt`。
- 生成中：
  - 输入框有文字时，发送键默认 **「排队」**（`streamingBehavior:"followUp"`，本轮彻底结束后送达）；**长按**发送键选「插话」（`"steer"`，当前工具执行完后送达）。排队不会改变正在进行的这一轮，最不容易出乎新手意料。
  - 输入框为空时，发送键变成**停止键**。
- 停止：`clear_queue` → 取回 `steering[]`、`followUp[]` 文本 → `abort`。取回的文本**只在输入框为空时**放回输入框；若用户已经在写新草稿，则以可点击的「已撤回 n 条」芯片显示，由用户决定是否插入，**不覆盖草稿**。`abort` 应答前显示「正在停止…」；20 s 未应答 → 强杀进程树 → 以 `--session <当前文件>` 重启并 `get_messages` 重建，最后一条标「已强制停止」。
- 队列以 `queue_update` 为唯一真相，客户端不自建队列；乐观气泡在收到对应 id 的 response 后转为「已排队/已发送」，失败标红并给「重试」，不会自动重发（防止重复）。
- 队列模式：pi 默认 `steeringMode`、`followUpMode` 均为 `one-at-a-time`（`rpc-commands.md`）；界面显示以 `get_state` 返回值为准，MVP 不提供修改入口。

### 3.8 会话管理

- 位置：`~/.pi/agent/sessions/--<path>--/<timestamp>_<session-id>.jsonl`，`<path>` 去掉开头分隔符并把 `/ \ :` 换成 `-`（`session-format.md`）。宿主路径 = `files/rootfs/root/.pi/agent/sessions/…`。
- **列表**（RPC 没有列会话命令，宿主直接读文件）：读首行 header（`type:"session"`、`id`、`timestamp`、`cwd`）、自尾向前找最后一个 `session_info` 的 `name`、第一条 user 消息前 80 字作预览、`lastModified` 作更新时间；以 `(path,size,mtime)` 缓存；大文件只读头 64 KB + 尾 64 KB；坏行跳过。
- **打开**：同 cwd → `switch_session`；不同 cwd → 以 `--session <path>` 和该 cwd 重启。随后 `get_messages` → `ChatReducer.fromMessages()`：assistant 的 `toolCall` 与后续 `role:"toolResult"` 按 `toolCallId` 合并成一张卡；`bashExecution`/`custom`/`branchSummary`/`compactionSummary` 渲染为提示行；**未知 role 渲染为可展开的原始 JSON**。
- **新建**：`new_session`；「在其他目录新建」→ 以新 cwd 重启。
- **删除**：移到 `~/.pi/agent/sessions-trash/<原子目录>/<文件名>`（同一文件系统内 rename，可恢复）。删除当前会话时先 `new_session` 再移动。回收站在设置页可查看、恢复、清空（清空需二次确认）；MVP 不自动清空。
- **重命名**：当前会话用 `set_session_name`；MVP 只支持当前会话改名。

### 3.9 错误、诊断与恢复

| 情形 | 识别 | 处理 |
|---|---|---|
| 命令被拒 | `success:false` | 提示 `error`；`prompt` 失败时文本退回输入框（不覆盖草稿，同 §3.7） |
| `parse` 错误 | `command:"parse"`、无 id | 记日志，诊断页可见 |
| 模型/网络失败 | `message_end.stopReason=="error"`；`auto_retry_end.success==false` | 错误卡：原因 +「重试」（重发上一条用户文本）+「去设置模型」 |
| 启动失败 | 回应首条 `get_state` 前退出 | 按 stderr 归类成中文一句话（下表） |
| 引擎崩溃 | stdout EOF / 进程退出 | 退避 1 s→3 s→10 s 自动重启最多 3 次，并 `switch_session` 回到上次文件；仍失败显示诊断页（stderr 尾部、复制诊断〔已脱敏〕、「修复运行环境」） |
| App 被系统杀 | 冷启动时 DataStore 里仍有运行标记（`agent_start` 写入，`agent_settled` 清除） | 回到该会话，顶部提示「上一轮被系统中断」；兜底：最后一条是 user 消息且其后没有 assistant 回复，同样提示 |
| 命令超时 | 15/20/30 s | `RPC_TIMEOUT`；先 `get_state` 探活，探活也超时则走强杀重启 |

stderr 归类（关键字由 W2 以真机日志校准，匹配不到时显示「pi 启动失败」+ 查看日志）：

| 关键字（示例） | 中文提示 | 跳转 |
|---|---|---|
| 没有可用模型 / 未配置 API Key 相关报错 | 还没有配置可用的模型 | 模型设置 |
| `JavaScript heap out of memory` / `FATAL ERROR: Reached heap limit` | 内存不足，已自动重启；可在设置里减少并发任务 | — |
| `ENOSPC` | 手机存储空间不足 | 系统存储设置 |
| `ENOTFOUND` / `EAI_AGAIN` / `ECONNRESET` | 网络或 DNS 不可用 | 重试 |
| `Cannot find module` / `pi: not found` | pi 安装不完整 | 修复运行环境 |
| proot 自身报错（`proot error` / `ptrace`） | 容器无法启动（系统限制） | 诊断页 |

### 3.10 模型与 Key

**Provider 预设**（id 和环境变量名取自 pi 1.5.0 `providers.md` 与内置目录）：

| 类别 | 显示名 | pi provider id | Key 环境变量 | 说明 |
|---|---|---|---|---|
| 按量 API | DeepSeek | `deepseek` | `DEEPSEEK_API_KEY` | 普通开放平台 Key |
| 按量 API | Moonshot（中国站） | `moonshotai-cn` | `MOONSHOT_API_KEY` | baseUrl `api.moonshot.cn/v1` |
| 按量 API | MiniMax（中国） | `minimax-cn` | `MINIMAX_CN_API_KEY` | — |
| 按量 API | OpenRouter / OpenAI / Anthropic | `openrouter` / `openai` / `anthropic` | `OPENROUTER_API_KEY` / `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` | 国内网络可能需要代理 |
| 编程套餐 | 智谱 GLM Coding Plan（中国） | `zai-coding-cn` | `ZAI_CODING_CN_API_KEY` | **需要套餐 Key**；普通智谱开放平台 Key 请用下方自定义 |
| 编程套餐 | 通义 Token Plan（中国） | `qwen-token-plan-cn` | `QWEN_TOKEN_PLAN_CN_API_KEY` | **需要套餐 Key**；普通百炼 Key 请用下方自定义 |
| 编程套餐 | Kimi For Coding | `kimi-coding` | `KIMI_API_KEY` | **需要套餐 Key**；普通 Moonshot Key 用上方 Moonshot |
| 自定义 OpenAI 兼容 | 阿里云百炼（按量） | 用户自定 id（默认 `bailian`） | `PIGO_KEY_BAILIAN` | 预填 baseUrl `https://dashscope.aliyuncs.com/compatible-mode/v1`，模型 id 由用户填 |
| 自定义 OpenAI 兼容 | 智谱开放平台（按量） | 默认 `zhipu` | `PIGO_KEY_ZHIPU` | 预填 baseUrl `https://open.bigmodel.cn/api/paas/v4` |
| 自定义 OpenAI 兼容 | 其他 | 用户自定 | `PIGO_KEY_<ID>` | baseUrl、模型 id 由用户填 |

自定义端点 baseUrl 为常用公开地址，W4 在落地时须对照服务商当前文档再核一次。

**写入方式（Key 不落容器磁盘）**：

- Key：输入时去掉首尾空白和开头的 `Bearer `，存 `SecretStore`（Keystore AES-GCM，App 私有目录），界面只显示末 4 位。
- 内置 Provider：不写 `auth.json`；启动 pi 时把 `DEEPSEEK_API_KEY=…` 等放进进程环境。
- 自定义端点：合并写 `/root/.pi/agent/models.json`（权限 600）：

```json
{"providers": {"bailian": {
  "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
  "api": "openai-completions",
  "apiKey": "$PIGO_KEY_BAILIAN",
  "models": [{"id": "<用户填写的模型 id>"}]
}}}
```

- 默认模型：合并写 `settings.json` 的 `defaultProvider` / `defaultModel` / `defaultThinkingLevel`，不覆盖用户其他键。
- **边界**：环境变量不落盘，避免了 Key 出现在容器文件、备份、`/root/shared` 和误提交里；但 pi 的 bash 工具继承进程环境，模型仍能用 `env` 读到 Key。设置页须明确提示「pi 能读取容器内的文件和环境变量，请使用可随时作废的 Key」。

---

## 4. 运行环境：从 PiMet 迁移

### 4.1 许可证结论（明确）

1. **PiMet 本身**：仓库无 LICENSE 文件，作者即本仓库所有者 zr987459。作者**原创**的部分，所有者可以按自己为 pi-go 选定的许可证复用，无需额外授权。
2. **PiMet 中派生自 GPL-3.0 DSH-Folk 的部分，pi-go 一律不复用，重写或弃用**：
   - `proot/TarGzipExtractor.java`：与 DSH-Folk `me/bmax/apatch/dsh/TarGzipExtractor.java` 447/453 行相同 → **净室重写**：基于 Apache Commons Compress，由未读过该文件的人按 §4.2 的功能规格实现。
   - `ProotManager` 中注释写明「参考 DSH-Folk / DSHA」的部分（第 38、740、909 行：`ensureRuntimeFiles` 的 SONAME 复制、`ResolverConfig` 式 DNS 修复），以及 `ensureContainerGroups`（思路与 DSH-Folk `DshRuntimeEnv.kt` 相近）→ **按 proot/Termux 公开文档重新编写**，不对照 PiMet 或 DSH-Folk 源码。
   - `.github/workflows/build-slim-rootfs.yml` 与其产物 `runtime-latest` rootfs（DSH-Folk release 裁剪再分发）→ **弃用**。
3. **PiMet 的 jniLibs 二进制**：与 DSH-Folk `app/libs/` 逐字节相同、无对应源码记录 → **不使用**。改从 Termux 官方包获取（当前 Termux 打包版本：proot 5.1.107.102〔GPL-2.0〕、libtalloc 2.5.0〔上游 LGPL-3.0-or-later〕、libandroid-shmem 0.7〔BSD-3-Clause〕），钉 `.deb` 的 SHA256，在 `THIRD_PARTY_NOTICES.md` 记录版本与上游源码地址，并在每个发布 APK 的 GitHub Release 附上对应源码包，履行 GPL/LGPL 义务。proot 作为独立可执行程序随 APK 分发属「聚合」，不影响 App 代码的许可证。
4. **不带**：`libproroot*.so`（5 个，PiMet 代码未引用，来源与许可不明）；`assets/pets/doro.codex-pet`、`elaina-2`（第三方角色素材，无授权说明）。
5. **AGPL-3.0 的设计参考项目**（见 `THIRD_PARTY_NOTICES.md` §5）：不复制任何代码或素材，只参考公开的交互设计（工具卡片折叠、插话/排队、运行时状态机）；pi-go 的名称、界面文案、图标与形象中不出现该项目的名称、标识或吉祥物。
6. **对 pi-go 许可证的影响**：做到第 2–5 条后，pi-go 不含任何 GPL/AGPL 代码，**可以自由选择许可证**。仓库目前没有 LICENSE 文件，也没有声明许可证；建议 **Apache-2.0**（与主要依赖一致、带专利授权），由仓库所有者在 `mvp/scaffold` 合入前决定并加入 `LICENSE`。在此之前，pi-go 代码默认保留所有权利。
7. **对 PiMet 的提醒**（不影响 pi-go）：PiMet 现在原样包含 GPL-3.0 代码，若继续分发 PiMet，应按 GPL-3.0 开源或移除该代码。
8. **防回归**：scaffold 提供 Gradle 任务 `checkLicenseHygiene`（挂到 `preBuild`）：源码中出现 `me.bmax.apatch`、`DSH-Folk`、`DSHA`，或 `jniLibs` 中出现 PiMet/DSH-Folk 那组 `.so` 的已知 SHA256 时构建失败。这样无需改 `.github/`。

### 4.2 迁移清单（MVP 涉及部分）

| PiMet 类 / 方法 | pi-go 去向 | 方式 |
|---|---|---|
| `ProotManager.buildProotProcess / getRootfsDir / getTmpDir / getNativeLibDir / isNativeProotAvailable` | `core/runtime/ProotLauncher.kt` | **改写**为 Kotlin 纯函数 `buildArgv(spec)`；参数集沿用（这是 proot 的标准用法）；**去掉** `/storage/emulated/0` 挂载（§4.4） |
| `ensureRuntimeFiles`（SONAME 复制） | `core/runtime/RuntimeFiles.kt` | **重新编写**（见 §4.1-2） |
| `ensureContainerDns / ensureContainerGroups` | `core/runtime/GuestFixups.kt` | **重新编写** |
| `ensureNpmConfig` | `GuestFixups.kt` | 改写：只保留 npmmirror registry，去掉 `@agegr` scope |
| `buildDomesticRootfs / writeDomesticAptConfig / latestNodeV24 / headOk / downloadFromAny` | `core/runtime/install/DomesticInstaller.kt` | 改写为协程；镜像表抽到 `assets/mirrors.json`；版本不再「探测最新」而是读 `pins.json` |
| `downloadWithResume / testUrlRealThroughput / testAndSortCandidateUrls` | `core/runtime/net/Downloader.kt` | 改写：保留断点续传与「6 s 后 <120 KB/s 换线」；加 `.part` 来源绑定与 SHA256 |
| `TarGzipExtractor` | `core/runtime/install/TarExtract.kt` | **净室重写**（Commons Compress）。功能规格：绝对符号链接按 rootfs 根解析、硬链接（失败时复制）、保留可执行位、拒绝越出目标目录的路径（含 `..` 与经符号链接逃逸）、总解压量上限、PAX/GNU 长文件名 |
| `util/SafeFiles` | — | 不搬，按需在 `core/runtime` 内重写 |
| `PiMetService` | `core/engine/EngineService.kt` | 改写：只在运行中持 WakeLock；通知显示空闲/运行中/排队数 |
| `PiMetConfig.syncToContainer` | `core/store/ProviderConfigStoreImpl.kt` | 改写：Key 不再写 `auth.json`（§3.10） |
| `PiWebManager`（含 `pkill -9 -f node`）、`PiWebApi`、`PiWebMonitor`、`PiWebActivity`、`PiWebCompat` | — | **不搬**（pi-web 整体弃用；容器内不装 `@agegr/pi-web`，避免同一 rootfs 里出现两个 pi 版本） |
| `TerminalHub`、`FloatingTerminal`、`AppBridgeManager`、`PiMetAccessibilityService`、`pet/*`、`BackupManager` | 二期 | 作者原创部分可复用；搬入前逐个做与 DSH-Folk 的比对 |
| `MainActivity`、`FloatingApps`、`BrowserAgent` 等 | — | 不搬（界面重做） |

### 4.3 首启安装流程（国内优先）

```
0 PREFLIGHT  ABI（arm64-v8a / x86_64）；可用空间 ≥ 3 GB（StatFs，不足则拒绝并给中文提示）；网络可用；
             nativeLibDir/libproot.so 可执行；打开前台服务通知
1 PROBE      并行 HEAD + 2 s 吞吐采样排序镜像（mirrors.json）：
               Ubuntu Base：阿里云 / 清华 / 中科大 / 华为云（ubuntu-cdimage/ubuntu-base/releases/24.04/release/）
               Node：registry.npmmirror.com/-/binary/node / cdn.npmmirror.com/binaries/node / 华为云 / 清华 / 阿里云
2 ROOTFS     下载 ubuntu-base-24.04.5-base-<arm64|amd64>.tar.gz → 校验 → 解压到 files/staging/rootfs
             → 原子 rename 为 files/rootfs                                                (5→35%)
3 NODE       下载 node-v24.22.2-linux-<arm64|x64>.tar.gz → 校验 → 解压到 rootfs/opt/node/.staging-24.22.2
             → rename 为 rootfs/opt/node/24.22.2 → /opt/node/current 软链 → /usr/local/bin/{node,npm,npx} 软链 (35→60%)
4 FIXUPS     resolv.conf（宿主 DNS + 223.5.5.5 / 119.29.29.29）、/etc/hosts、/etc/group、/root/.npmrc（npmmirror）、
             创建 /root/pi-cwd、/root/.pi/agent、/root/.pi/agent/sessions-trash
5 APT        apt 换阿里云源，装 ca-certificates git curl procps（失败不致命，后台重试）         (60→75%)
6 PI         先核对 npmmirror 上 1.5.0 的 dist.integrity == pins.json；再
             npm install -g --prefix /opt/pi-go/pi/1.5.0 --ignore-scripts --no-audit --no-fund \
                 @earendil-works/pi-coding-agent@1.5.0
             （npm 自身按 integrity 校验 tarball）→ /opt/pi-go/pi/current → 1.5.0；/usr/local/bin/pi → current/bin/pi (75→95%)
7 VERIFY     node -v == v24.22.2；pi --version == 1.5.0；
             pi --mode rpc --no-session + {"id":"v-1","type":"get_state"} 20 s 内 success:true             (95→100%)
8 记录       写 rootfs/etc/pi-go-runtime.json {ubuntu, node, pi, installedAt}
```

**下载完整性**：

- 期望哈希以 `pins.json` 为准；同时下载镜像上的 `SHA256SUMS` / `SHASUMS256.txt` 交叉核对，两者不一致按失败处理。
- 每个文件下载到 `<name>.part`，旁边写 `<name>.part.src` 记录来源 URL。续传只对**同一 URL**用 `Range`；换镜像时**删除 `.part` 重新下载**。
- 校验失败：删除文件，标记该镜像本次不可用，换下一个镜像；全部失败给出「换网络或稍后再试」。
- 未经校验的文件永不解压。

**原子性与幂等**：每步完成写标记文件；失败只重试当前步。staging 目录在步骤开始时先清掉。界面提供「换镜像重试」「查看日志」。可选高级项「我有代理，走官方源」（官方源同样校验 `pins.json` 哈希）。

### 4.4 PRoot 启动参数（宿主侧）

```
<nativeLibDir>/libproot.so --link2symlink -L --kill-on-exit -0 -r <files/rootfs>
  -b /dev -b /dev/urandom:/dev/random -b /proc -b /sys -b /proc/self/fd:/dev/fd
  -b <getExternalFilesDir()>:/root/shared
  -w <cwd>
  /usr/bin/env -i HOME=/root PATH=/usr/local/bin:/usr/bin:/bin TERM=dumb LANG=C.UTF-8 <GuestSpec.env…> <argv…>
宿主环境：PROOT_TMP_DIR=<files/tmp>  PROOT_LOADER=<nativeLibDir>/libprootloader.so
         PROOT_LOADER_32=<nativeLibDir>/libprootloader32.so  LD_LIBRARY_PATH=<files/lib>:<nativeLibDir>（只给 proot 自己）
```

- 容器内用 `env -i` 重建环境，宿主的 `LD_LIBRARY_PATH` 等不会漏进容器。
- **默认不挂载 `/storage/emulated/0`**。用户需要让 pi 访问手机文件时，在二期提供「挂载共享存储」开关（显式同意 + 说明风险）。
- jniLibs 来源：Termux 官方 `proot`、`libtalloc`、`libandroid-shmem` 包（arm64 与 x86_64），由 W1 用 `tools/fetch-termux-libs.sh` 下载并校验钉死的 `.deb` SHA256，解出 `proot`→`libproot.so`、loader→`libprootloader.so`/`libprootloader32.so`、`libtalloc.so.2`→`libtalloc.so`、`libandroid-shmem.so`→`libandroidshmem.so`，提交到 `core/runtime/src/main/jniLibs/`。

### 4.5 进程停止（只杀自己的进程树）

1. 关闭 pi 的 stdin，等待 5 s（pi 自行 dispose 后退出，proot 随之退出）。
2. 未退出：从 `GuestProcess.prootPid` 出发，遍历 `/proc/*/stat` 收集**以它为根的后代 PID**（只取本 App uid 的进程），先对整棵树 `android.os.Process.sendSignal(pid, SIGTERM)`，3 s 后仍在则 SIGKILL，最后 `destroyForcibly()`。
3. 禁止 `pkill`、`killall`、`pidof` 以及任何按名字匹配的杀进程方式。
4. `--kill-on-exit` 作为额外保障保留。

### 4.6 升级与修复

- **App 升级不动 rootfs**；启动时比对 `pins.json` 与 `pi-go-runtime.json`，不一致时在设置页提示。
- **pi 升级**：「检查更新」读 npmmirror `dist-tags.latest`；只有落在兼容范围 `>=1.5.0 <2.0.0` 内才可一键升级：装到新 prefix `/opt/pi-go/pi/<X>` → 冒烟（`--version` + RPC `get_state` 握手 + `get_available_models`）→ 停引擎 → 切 `current` 软链 → 重启；任一步失败保持旧软链。保留最近 2 个版本。升级前复查新版本依赖是否出现 install 脚本（`--ignore-scripts` 的前提）。
- **Node 升级**：只在 pi 的 `engines.node` 变化时提示，同样装到新目录、校验、切软链。
- **修复运行环境**：只重跑缺失或损坏的步骤。
- **重置运行环境**：删除 rootfs 但保留 `root/.pi/agent`（会话、`models.json`、`settings.json`、回收站）与 `root/pi-cwd`，先移到 `files/preserve/`，重装后移回；需二次确认。

---

## 5. 第一期 MVP 功能清单

目标：**真机走通「首启安装容器 + pi → 配置模型/API Key → 原生聊天流式对话 → 工具调用卡片折叠 → 中断/排队 → 会话列表」**。

验收设备：至少 2 台 arm64 真机，Android 11 及以上，其中**至少 1 台国产 ROM**（小米/华为/OPPO/vivo 之一）；国内网络、不开代理。另加 x86_64 模拟器跑 UI 测试。

### 5.1 首启安装

- [ ] **F1** 全新安装后进入引导页，显示 ABI、可用空间、网络三项预检结果；可用空间 < 3 GB 时禁止开始并给出中文原因。
- [ ] **F2** 无代理国内网络下完成安装，日志中只出现国内镜像域名；百兆网络下总时长 ≤ 15 分钟。
- [ ] **F3** 安装中切后台或锁屏 5 分钟不中断，前台通知显示步骤与百分比。
- [ ] **F4** 安装中断网后恢复，点「重试」从失败步骤继续，已校验通过的文件不重下（日志可证）。
- [ ] **F5** 把下载中的 `.part` 文件人为改坏或模拟哈希不符：安装报「校验失败，已换镜像」，被改坏的文件被删除，最终装好的 Node/Ubuntu 包哈希等于 `pins.json`。
- [ ] **F6** 完成后「关于运行环境」显示 Ubuntu 24.04.5、Node v24.22.2、pi 1.5.0；`get_state` 握手通过。

### 5.2 模型与 Key

- [ ] **C1** 预设分三组显示：按量 API、编程套餐（标注「需要套餐 Key」）、自定义 OpenAI 兼容（百炼、智谱开放平台预填 baseUrl）。
- [ ] **C2** 填入内置 Provider 的 Key 后，容器内 `grep -r <Key 片段> /root /etc /opt` 无结果，且 `auth.json` 不存在或未被改动。
- [ ] **C3** 自定义端点保存后，`models.json` 中 `apiKey` 为 `$PIGO_KEY_<ID>`，文件权限 600，原有其他条目保留。
- [ ] **C4** 保存后重启引擎，`get_available_models` 列出该 Provider 模型；选中后 `set_model` 成功并写入 `settings.json` 默认模型；用一个真实 DeepSeek Key 完成一次对话。
- [ ] **C5** 粘贴 `"  Bearer sk-xxxx  "` 自动存为 `sk-xxxx`；界面只显示末 4 位；单测断言日志与诊断导出中不出现完整 Key。

### 5.3 原生聊天流式对话

- [ ] **S1** 发送后文本逐字增长，无整段等待。
- [ ] **S2** Markdown（标题、列表、代码块、表格）正确渲染，代码块可复制。
- [ ] **S3** thinking 块显示为可折叠的「思考中…/已思考 N 秒」。
- [ ] **S4** 顶栏显示当前模型、思考级别（可切换）、上下文占用百分比。
- [ ] **S5** 旋转屏幕、切后台再回来，正在流式的消息继续增长，不丢不重。

### 5.4 工具调用卡片折叠

- [ ] **T1** 让 pi「列出当前目录并读 README」：出现 `bash`、`read` 卡片，运行中显示转圈与计时，结束显示耗时。
- [ ] **T2** 工具失败（读不存在的文件）时卡片红色、默认展开、显示错误文本。
- [ ] **T3** 回合结束后，思考与工具折叠成一行摘要（如「思考 12 秒 · 3 个工具 · 1 失败」），可展开；最终回答单独显示。
- [ ] **T4** 重新打开该会话，卡片与实时显示一致（`get_messages` 重建）。

### 5.5 中断 / 插话 / 排队

- [ ] **Q1** 生成中输入文字点发送：默认进入「待排队」芯片，本轮结束后自动作为下一轮发出，且只发一次。
- [ ] **Q2** 生成中长按发送选「插话」：出现「待插话」芯片，当前工具执行完后该消息进入对话，芯片消失。
- [ ] **Q3** 有排队消息、输入框为空时点停止：排队文本回到输入框，20 s 内停止，消息标「已停止」。
- [ ] **Q4** 有排队消息、输入框里正在写新草稿时点停止：草稿不变，撤回的文本以「已撤回 n 条」芯片出现。
- [ ] **Q5** 模拟 `abort` 不应答：20 s 后只结束本 App 启动的进程树（同机另起的 `node` 进程存活），引擎重启并回到同一会话，历史完整。

### 5.6 会话列表

- [ ] **L1** 侧边抽屉列出全部会话：名称或首条消息预览、工作目录、更新时间，按更新时间倒序。
- [ ] **L2** 点击切换会话，历史完整；新建会话可用；打开另一 cwd 的会话时引擎自动以该 cwd 重启。
- [ ] **L3** 重命名当前会话，列表与标题同步。
- [ ] **L4** 删除会话（含当前会话，二次确认）：文件出现在 `sessions-trash/`，列表刷新；在回收站点「恢复」后会话回到列表且可打开。
- [ ] **L5** 冷启动后自动回到上次会话；若上次在运行中被系统杀，顶部提示「上一轮被系统中断」。

### 5.7 非功能

- [ ] **N1** 空闲时不持有 WakeLock（`dumpsys power` 可证）。
- [ ] **N2** 引擎异常退出后 3 次内自动恢复；超过后显示诊断页，可一键复制（已脱敏）；heap OOM 被识别为「内存不足」。
- [ ] **N3** `:core:protocol` 单测覆盖率 ≥ 80%，录制夹具回放全部通过。
- [ ] **N4** 返回键：聊天页按返回 → 退到后台，前台服务与引擎不结束；安装向导中按返回 → 弹确认，取消后安装继续、确认后安全中止且可续装。
- [ ] **N5** 国产 ROM 真机上：首次进入聊天时引导加入电池白名单/允许自启动；Android 12+ 诊断页给出幻影进程限制说明。
- [ ] **N6** `./gradlew assembleDebug` 在 JDK 17 下通过，CI 产出 debug APK；`checkLicenseHygiene` 通过。

**MVP 不做**：图片/附件、平板/横屏双栏、`edit` diff 视图、排队消息「立即发送」、悬浮终端、桌宠、手机操控、WebView 模式、多会话并发、fork/会话树、导出、主题定制、共享存储挂载、队列模式设置。

---

## 6. 并行工作流

规则：

- 每条工作流只改下表「文件范围」内的文件；范围互不重叠。
- `:core:contract` 已有签名只能由 scaffold 修改；工作流需要改接口时，单独向 main 提一个只改 `core/contract/**` 的 PR，并在所有分支 rebase。
- `gradle/libs.versions.toml`、`settings.gradle.kts`、根 `build.gradle.kts`、各模块 `build.gradle.kts` 只归 scaffold；scaffold 预先写好 MVP 已知的全部依赖（Compose、coroutines、serialization、OkHttp、Commons Compress、Markdown 渲染、DataStore）。工作流需要新依赖时在 PR 描述中提出，由 scaffold 维护者合入 main。
- `.github/` 不在任何工作流范围内（现有 `android.yml` 已能在 `gradlew` 出现后自动构建）；确需改动由仓库所有者单独处理。
- `THIRD_PARTY_NOTICES.md` 只归 scaffold；W1 引入 Termux 二进制时把版本、哈希、源码地址写在 `core/runtime/NOTICE-runtime.md`，scaffold 维护者合入时同步到总表。
- scaffold 创建的初版文件（四个 `di/*Bindings.kt`、feature 占位页、`BindingMode.kt`）在 scaffold 合入后归属转给表中对应工作流；W1–W4 并行期间各自范围互不重叠，模块的 `build.gradle.kts` 始终归 scaffold，因此工作流范围写到 `src/**` 一级。
- 顺序：`mvp/scaffold` 先合 main → W1–W4 从新 main 切出并行 → 各自合入 → `mvp/integration` 切换为真实实现并做真机冒烟 → 打 `v0.1.0`。

| 分支 | 文件范围 | 依赖 | 验收标准 |
|---|---|---|---|
| **mvp/scaffold**（最先合入 main） | 根 `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradle/**`（含 `libs.versions.toml`、wrapper）、`gradlew`、`gradlew.bat`；所有模块的 `build.gradle.kts`；`core/contract/**`（§2.2 全部接口、数据类、RPC 记录类型、Fake 实现）；`core/ui/**`；`app/**` 中除四个 `di/*Bindings.kt` 以外的文件（Application、MainActivity、导航、主题接入、`AppGraph.kt`、`di/BindingMode.kt` 初版）；四个 `di/*Bindings.kt` 的**初版**（只含 `fake()`）；各 feature 模块的占位页（每个 `feature/*/src/main/` 下一个 `Placeholder.kt`）；`app/src/main/assets/pins.json`；`buildSrc` 或 `build-logic/**` 中的 `checkLicenseHygiene`；`LICENSE`（所有者选定后）；`THIRD_PARTY_NOTICES.md` | 无 | JDK 17 下 `./gradlew assembleDebug testDebugUnitTest` 通过，CI 产出 debug APK；APK 能在四个占位页间导航；Fake 引擎能回放一段内置夹具（流式文本 + 两个工具卡 + 一次排队）；`checkLicenseHygiene` 对植入的 `me.bmax.apatch` 字符串会失败；仓库中无任何 `.so` |
| **mvp/runtime** | `core/runtime/src/**`（含 `src/main/jniLibs/**`、`src/main/assets/mirrors.json`、`src/main/AndroidManifest.xml`、测试）；`core/runtime/NOTICE-runtime.md`；`tools/fetch-termux-libs.sh`；`feature/onboarding/src/**`（替换其占位页）；`app/src/main/java/**/di/RuntimeBindings.kt` | scaffold | §5.1 F1–F6；`RuntimeManager.exec(["node","-v"])` 返回 v24.22.2；`start(GuestSpec(["pi","--version"]))` 输出 1.5.0；`TarExtract` 单测：路径穿越、绝对/相对符号链接、硬链接、可执行位、超量上限；`Downloader` 单测：换镜像删 `.part`、同 URL 续传、哈希不符拒绝；`GuestProcess.stop()` 单测/仪器测试：只结束自身进程树；jniLibs 每个文件的 SHA256 与 `NOTICE-runtime.md` 一致 |
| **mvp/engine** | `core/protocol/src/**`（含 `src/test/resources/fixtures/**`）；`core/engine/src/**`（含 `EngineService` 与其 manifest）；`docs/protocol/fixtures.md`；`app/src/main/java/**/di/EngineBindings.kt` | scaffold；真机联调需 runtime（开发期在桌面 Linux 上用真 pi 1.5.0 跑 JVM 集成测试，不依赖手机） | JVM 单测：Framer（U+2028/U+2029 不切分、CRLF、半包、超长行）、id 乱序关联、超时、不带 id 的 `parse` 错误、Reducer 夹具快照（文本流、思考、工具成功/失败、followUp、steer、clear_queue+abort、重试、压缩、未知事件/role）；桌面真 pi 集成测试：prompt→`agent_settled`、`handled` 不等待、followUp/steer、停止、`switch_session`、进程被杀后自动重启并恢复会话；stderr 归类表单测；§5.5 Q5、§5.7 N1–N3 |
| **mvp/chat-ui** | `feature/chat/src/**`；`app/src/main/java/**/di/ChatBindings.kt` | scaffold（开发期用 Fake 引擎回放夹具） | §5.3 S1–S5、§5.4 T1–T4、§5.5 Q1–Q4 的界面部分；扩展对话框四种 method 与未知 method 自动 `cancelled`；Compose UI 测试：回放 1000 条 delta 时慢帧 < 5%（JankStats）；停止时草稿不被覆盖的 UI 测试 |
| **mvp/sessions-settings** | `core/store/src/**`；`feature/sessions/src/**`；`feature/settings/src/**`；`app/src/main/java/**/di/StoreBindings.kt` | scaffold；联调 `openSession/rename/models` 需 engine | §5.2 C1–C5、§5.6 L1–L5（列表、回收与恢复部分）、§5.7 N5 的引导页；`SessionRepository` 单测：header/`session_info`/预览解析、头尾读取、坏行容错、移入与恢复回收站；`ProviderConfigStore` 单测：`models.json` 合并不丢条目、权限 600、`$PIGO_KEY_<ID>` 写法、Key 清洗与脱敏；`SecretStore` 仪器测试：加密往返 |
| **mvp/integration**（W1–W4 全部合入后） | `app/src/main/java/**/di/BindingMode.kt`；`app/src/androidTest/**`（端到端冒烟）；`docs/testing/mvp-acceptance.md`（真机验收记录） | scaffold、runtime、engine、chat-ui、sessions-settings | `BindingMode` 全部为真实实现；在 §5 规定的设备上逐条走通 §5 全表并记录到验收文档；无回归后打 `v0.1.0` |

---

## 7. 风险与后续路线

### 7.1 风险与对策

| # | 风险 | 影响 | 对策 |
|---|---|---|---|
| R1 | `targetSdk 28` 长期受限（新系统可能警告或限制安装低 targetSdk 应用） | 中 | 当前仍可安装；二期单独分支验证 targetSdk 29+ 下「可执行文件全部放 nativeLibraryDir + loader 映射」方案 |
| R2 | Termux 官方 proot 与 PiMet 所用二进制（v5.1.107.91）行为差异 | 中 | W1 在两台真机上跑通 §5.1 才合入；不行时在 CI 中从 `termux/proot` 指定 tag 源码自建（仍是可追溯来源） |
| R3 | PRoot 下 node/pi 冷启动慢、内存高 | 中 | 单进程常驻，只在切 cwd 时重启；`PI_OFFLINE` 等环境变量减少网络等待；按设备内存设 heap 上限；OOM 识别并提示 |
| R4 | 国内镜像失效或下架旧版本 | 高 | `mirrors.json` 多源；版本钉死但允许官方源回退（同样校验哈希）；二期自建 rootfs 线路 |
| R5 | pi 小版本改协议 | 中 | 钉版本 + 兼容范围 + 夹具回放；未知字段/事件/role 容忍；升级冒烟失败不切软链 |
| R6 | 凭据泄露 | 高 | Key 只在宿主 Keystore 和进程环境；日志/诊断脱敏单测；明确提示 pi 的工具能读环境变量，建议用可作废的 Key；二期评估 `models.json` 的 `!command` 方式按需取 Key |
| R7 | 许可证回归（误搬 GPL 代码或二进制） | 高 | §4.1 清单；`checkLicenseHygiene`；REVIEW.md 许可证项；二期复用 PiMet 原创代码前逐文件比对 |
| R8 | 国产 ROM 杀后台、Android 12+ 幻影进程限制 | 中 | 前台服务；运行中持 WakeLock；单 pi 进程；电池白名单与自启动引导；诊断页给出 `settings_enable_monitor_phantom_procs` 的说明；被杀后以运行标记提示并可续聊 |
| R9 | 扩展对话框超时被默认处理 | 低 | 显示倒计时；后台时发通知 |
| R10 | 项目信任（project trust）在 RPC 下弹确认 | 低 | MVP 工作区 `/root/pi-cwd` 无 `.pi/` 资源；若出现则按扩展 UI 正常展示。不默认加 `-na/--no-approve`（它会让项目内 `.pi/` 扩展、技能、设置不加载），确需加时在界面说明 |
| R11 | 重写解压器的边角情况（PAX、硬链接、绝对符号链接） | 中 | 已计入 W1 工作量与单测清单；用真实 Ubuntu Base 与 Node 包做回归 |

### 7.2 后续路线

- **v0.2**：图片附件（`prompt`/`steer`/`follow_up` 的 `images`）、平板/横屏双栏、`edit` diff 视图、主题、会话搜索、用量页（`get_session_stats`）、手动压缩（`compact`）、队列模式设置、可选「挂载共享存储」。
- **v0.3（复用 PiMet 原创能力，逐文件比对后迁入）**：`:feature:terminal`（TerminalHub/FloatingTerminal + Termux terminal-view，Apache-2.0）、`:core:bridge`（App 桥 + 无障碍手机操控，扩展改名 `pigo-*.ts`）、自有素材的桌宠。
- **v0.4**：会话树与分叉（`get_tree`/`fork`/`clone`）、导出 HTML、扩展/技能管理（`get_commands`）、排队消息「立即发送」（需 pi 侧原子支持或可靠的去重方案）。
- **v0.5**：自建 rootfs CI（钉哈希、可复现构建、附源码）、proot 从源码自建、多会话并发（评估 SDK 桥或多 RPC 进程）、远程模式（RPC over SSH 连接电脑上的 pi）、targetSdk 升级。
