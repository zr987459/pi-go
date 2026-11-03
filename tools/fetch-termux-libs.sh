#!/usr/bin/env bash
# 从 Termux 官方仓库下载 proot / libtalloc / libandroid-shmem（DESIGN.md §4.1 第 3 条、§4.4），
# 校验钉死的 .deb SHA256，解出可执行文件与共享库，按 Android 打包要求改名为 lib*.so，
# 放进 core/runtime/src/main/jniLibs/<abi>/。
#
# 用法：tools/fetch-termux-libs.sh            # 下载、校验、解包、覆盖 jniLibs
#       tools/fetch-termux-libs.sh --check    # 只核对现有 jniLibs 与 NOTICE-runtime.md 记录的哈希
#
# 依赖：bash、curl、sha256sum、tar（支持 xz/zstd）；dpkg-deb 或 ar 二者之一。
# 不使用 PiMet 或任何第三方再分发的二进制。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI="$ROOT/core/runtime/src/main/jniLibs"
NOTICE="$ROOT/core/runtime/NOTICE-runtime.md"
REPO="${TERMUX_REPO:-https://packages.termux.dev/apt/termux-main}"

# 包名|版本|Termux 架构|Android ABI|.deb SHA256（取自 Termux stable 索引 Packages，2026-11-03）
DEBS=(
  "proot|5.1.107.102|aarch64|arm64-v8a|10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030"
  "libtalloc|2.5.0|aarch64|arm64-v8a|556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7"
  "libandroid-shmem|0.7|aarch64|arm64-v8a|0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"
  "proot|5.1.107.102|x86_64|x86_64|2497064226c15971be8131c7facead94a06c225347796326a383c832dca57758"
  "libtalloc|2.5.0|x86_64|x86_64|b8c6d95f20075dc1f9ec6573575b2444e8d526e48e0d8d6d5cf4e071e6e06530"
  "libandroid-shmem|0.7|x86_64|x86_64|ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797"
)

PREFIX="data/data/com.termux/files/usr"

# libandroid-shmem 0.7 官方构建与 PiMet 中那份逐字节相同，其 SHA256 目前在 checkLicenseHygiene 的禁用名单里，
# 入库会让构建失败。在 scaffold 从名单中移除这两个哈希之前默认不写入 jniLibs（见 NOTICE-runtime.md）。
INCLUDE_SHMEM="${INCLUDE_SHMEM:-0}"

# 包内路径 → jniLibs 文件名
map_files() {
  case "$1" in
    proot) echo "bin/proot:libproot.so libexec/proot/loader:libprootloader.so libexec/proot/loader32:libprootloader32.so" ;;
    libtalloc) echo "lib/libtalloc.so.2:libtalloc.so" ;;
    libandroid-shmem) echo "lib/libandroid-shmem.so:libandroidshmem.so" ;;
  esac
}

pool_path() {
  local name="$1" ver="$2" arch="$3"
  local first="${name:0:1}"
  [[ "$name" == lib* ]] && first="${name:0:4}"
  echo "pool/main/$first/$name/${name}_${ver}_${arch}.deb"
}

extract_deb() {
  local deb="$1" out="$2"
  mkdir -p "$out"
  if command -v dpkg-deb >/dev/null 2>&1; then
    dpkg-deb -x "$deb" "$out"
  else
    local tmp; tmp="$(mktemp -d)"
    (cd "$tmp" && ar x "$deb")
    tar -xf "$tmp"/data.tar.* -C "$out"
    rm -rf "$tmp"
  fi
}

check() {
  local fail=0
  while read -r abi file sha; do
    local f="$JNI/$abi/$file"
    if [[ ! -f "$f" ]]; then echo "缺少 $abi/$file"; fail=1; continue; fi
    local got; got="$(sha256sum "$f" | cut -d' ' -f1)"
    if [[ "$got" != "$sha" ]]; then echo "哈希不符 $abi/$file: $got != $sha"; fail=1; fi
  done < <(grep -E '^\| `(arm64-v8a|x86_64)/lib[^`]+\.so`' "$NOTICE" | sed -E 's/^\| `([^/]+)\/([^`]+)` \|[^|]*\|[^|]*\| `([0-9a-f]{64})`.*/\1 \2 \3/')
  [[ $fail == 0 ]] && echo "jniLibs 与 NOTICE-runtime.md 一致"
  return $fail
}

if [[ "${1:-}" == "--check" ]]; then check; exit $?; fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

for row in "${DEBS[@]}"; do
  IFS='|' read -r name ver arch abi sha <<<"$row"
  if [[ "$name" == "libandroid-shmem" && "$INCLUDE_SHMEM" != "1" ]]; then
    echo "跳过 $name（INCLUDE_SHMEM=1 时才写入，见脚本开头说明）"
    continue
  fi
  url="$REPO/$(pool_path "$name" "$ver" "$arch")"
  deb="$WORK/${name}_${ver}_${arch}.deb"
  echo "下载 $url"
  curl -fsSL --retry 3 -o "$deb" "$url"
  got="$(sha256sum "$deb" | cut -d' ' -f1)"
  if [[ "$got" != "$sha" ]]; then
    echo "校验失败：$deb 的 SHA256 为 $got，期望 $sha" >&2
    exit 1
  fi
  ex="$WORK/x-$name-$arch"
  extract_deb "$deb" "$ex"
  mkdir -p "$JNI/$abi"
  for pair in $(map_files "$name"); do
    src="$ex/$PREFIX/${pair%%:*}"
    dst="$JNI/$abi/${pair##*:}"
    # libtalloc.so.2 等可能是指向带完整版本号文件的软链，取实际内容
    cp -L "$src" "$dst"
    chmod 0755 "$dst"
    echo "  $abi/${pair##*:}  $(sha256sum "$dst" | cut -d' ' -f1)"
  done
done

echo "完成。请把上面的 SHA256 与 NOTICE-runtime.md 核对（或运行 $0 --check）。"
