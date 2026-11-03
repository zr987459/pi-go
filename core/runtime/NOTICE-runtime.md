# core/runtime 随 APK 分发的第三方二进制

本目录 `src/main/jniLibs/` 中的文件**全部**取自 Termux 官方 apt 仓库（`https://packages.termux.dev/apt/termux-main`，stable/main），
由 `tools/fetch-termux-libs.sh` 下载、校验钉死的 `.deb` SHA256 后解出并改名（Android 打包只接受 `lib*.so`）。
不使用 PiMet 或任何其他第三方再分发的二进制（DESIGN.md §4.1 第 3 条）。

`core/runtime` 的单测 `JniLibsNoticeTest` 会逐个核对下表的 SHA256 与 jniLibs 中的实际文件；
`tools/fetch-termux-libs.sh --check` 做同样的核对。scaffold 维护者合入时请把本表同步到 `THIRD_PARTY_NOTICES.md`。

proot 作为独立可执行程序随 APK 分发属「聚合」，不影响 pi-go 自身代码的许可证（Apache-2.0）。
按 GPL-2.0 / LGPL-3.0 的要求，每个发布 APK 的 GitHub Release 需附上下列上游源码包（见「对应源码」列）。

## 已入库的文件

| jniLibs 文件 | 包与版本 | 许可证 | 文件 SHA256 | 包内原路径（`/data/data/com.termux/files/usr/` 下） | .deb SHA256 |
|---|---|---|---|---|---|
| `arm64-v8a/libproot.so` | proot 5.1.107.102 (aarch64) | GPL-2.0 | `3621937c1c5a98d0d39ca599af573a6bc15128690606d535e8b05da00cec4f41` | `bin/proot` | `10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030` |
| `arm64-v8a/libprootloader.so` | proot 5.1.107.102 (aarch64) | GPL-2.0 | `1da54355333cca3fb6ee10e3b82a24df24fb230c99241ce83809bf9e240609b5` | `libexec/proot/loader` | `10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030` |
| `arm64-v8a/libprootloader32.so` | proot 5.1.107.102 (aarch64) | GPL-2.0 | `c429996fee730e1a4fe12625b22dab5a5bc2847a4cae274d36fd4a19cd8299a8` | `libexec/proot/loader32` | `10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030` |
| `arm64-v8a/libtalloc.so` | libtalloc 2.5.0 (aarch64) | LGPL-3.0-or-later | `742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309` | `lib/libtalloc.so.2` | `556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7` |
| `x86_64/libproot.so` | proot 5.1.107.102 (x86_64) | GPL-2.0 | `3dd2e5b23aee8dd8588750117f03b1b7971314e7457067af803d78d57306391d` | `bin/proot` | `2497064226c15971be8131c7facead94a06c225347796326a383c832dca57758` |
| `x86_64/libprootloader.so` | proot 5.1.107.102 (x86_64) | GPL-2.0 | `a836b0569c84b1661333d16bacf6b396ff08fea816a5c936a82e08ddeeca8341` | `libexec/proot/loader` | `2497064226c15971be8131c7facead94a06c225347796326a383c832dca57758` |
| `x86_64/libprootloader32.so` | proot 5.1.107.102 (x86_64) | GPL-2.0 | `fd5b394aa0801619e293b76fe6e830a3beb363aed4fa4b9a5a41f1388a0cb9b9` | `libexec/proot/loader32` | `2497064226c15971be8131c7facead94a06c225347796326a383c832dca57758` |
| `x86_64/libtalloc.so` | libtalloc 2.5.0 (x86_64) | LGPL-3.0-or-later | `7fa6722a4320495b1b85832fc7fd02de0cb367b45f116c1921b2830a6936da90` | `lib/libtalloc.so.2` | `b8c6d95f20075dc1f9ec6573575b2444e8d526e48e0d8d6d5cf4e071e6e06530` |
| `arm64-v8a/libandroidshmem.so` | libandroid-shmem 0.7 (aarch64) | BSD-3-Clause | `84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731` | `lib/libandroid-shmem.so` | `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6` |
| `x86_64/libandroidshmem.so` | libandroid-shmem 0.7 (x86_64) | BSD-3-Clause | `092926060298acd3778e6239033d7aef1280dcb59aebe021a3719612e6a3465f` | `lib/libandroid-shmem.so` | `ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797` |

许可证依据：proot 以 Termux 打包元数据 `TERMUX_PKG_LICENSE="GPL-2.0"` 为准；libtalloc 见下文「libtalloc 的许可证标注」；libandroid-shmem 元数据为 `BSD 3-Clause`。

## libandroid-shmem 的来源核对

两份 `libandroidshmem.so` 由 `INCLUDE_SHMEM=1 tools/fetch-termux-libs.sh` 从 Termux 官方仓库下载并解出（2026-11-03 核对）：

| ABI | .deb 地址 | .deb SHA256（与脚本钉死值一致） | 解出的 `lib/libandroid-shmem.so` SHA256 |
|---|---|---|---|
| arm64-v8a | https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb | `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6` | `84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731` |
| x86_64 | https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_x86_64.deb | `ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797` | `092926060298acd3778e6239033d7aef1280dcb59aebe021a3719612e6a3465f` |

许可证：BSD-3-Clause（Termux 打包元数据 `BSD 3-Clause`；包内 `share/doc/libandroid-shmem/copyright`：
Copyright (c) 2013 Sergii Pylypenko、Copyright (c) 2017 Fredrik Fornwall）。BSD-3-Clause 要求在分发的二进制附带版权声明与许可条款，
请随 `THIRD_PARTY_NOTICES.md` 一并保留。

这两个 SHA256 曾在 `checkLicenseHygiene` 的禁用名单（`buildSrc/.../LicenseHygiene.kt` 的 `knownBadSoSha256`）中，
因为 PiMet 再分发的正是这份 Termux 官方构建（逐字节相同）。经上表独立核对，它们就是 Termux 官方 BSD-3-Clause 包的原样产物，
不是 GPL 项目的派生物，已从名单中移除（见该文件中的注释）；名单中 PiMet 其余的 `.so` 哈希保持不变。

## libtalloc 的许可证标注

Termux 的 libtalloc 2.5.0 `.deb` 中 `share/doc/libtalloc/copyright` 是指向 `../../LICENSES/GPL-3.0.txt` 的软链，打包元数据也写 `TERMUX_PKG_LICENSE="GPL-3.0"`，即 Termux 标注为 **GPL-3.0**，
而上游 talloc 源码头声明的是 **LGPL-3.0-or-later**（DESIGN.md §4.1 第 3 条采用此值）。两种标注下的义务都由「随每个发布 APK 的
GitHub Release 附上对应源码包」满足（见下节 talloc-2.5.0.tar.gz），libtalloc 只被独立可执行程序 proot 动态加载，
不与 pi-go 自身的 Kotlin/Java 代码链接。最终在 `THIRD_PARTY_NOTICES.md` 中采用哪一个标注，请仓库所有者确认。

## 对应源码

| 组件 | 上游源码 | Termux 打包脚本 |
|---|---|---|
| proot 5.1.107.102 | https://github.com/termux/proot/archive/v5.1.107.102.zip | https://github.com/termux/termux-packages/tree/master/packages/proot |
| libtalloc 2.5.0 | https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz | https://github.com/termux/termux-packages/tree/master/packages/libtalloc |
| libandroid-shmem 0.7 | https://github.com/termux/libandroid-shmem/archive/v0.7.tar.gz | https://github.com/termux/termux-packages/tree/master/packages/libandroid-shmem |

## 运行时的库名

`libproot.so` 按原名查找依赖库：`libtalloc.so.2`、`libandroid-shmem.so`。`RuntimeFiles` 在首次启动时把
nativeLibDir 中的 `libtalloc.so`、`libandroidshmem.so` 复制为这两个名字放进 `files/lib/`，只通过 proot 自己的
`LD_LIBRARY_PATH` 提供，容器内用 `env -i` 重建环境，不会继承。
