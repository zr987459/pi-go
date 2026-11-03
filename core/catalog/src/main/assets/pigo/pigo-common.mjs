// pi-go 容器内脚本共用：定位 pi 自带的 @earendil-works 包（不依赖全局 node_modules 解析）。
import { existsSync, realpathSync } from "node:fs";
import { dirname, join } from "node:path";
import { pathToFileURL } from "node:url";

/** pi-coding-agent 包目录：优先 PIGO_PI_ROOT，其次 /opt/pi-go/pi/current 下的全局安装位置。 */
export function piRoot() {
  const candidates = [
    process.env.PIGO_PI_ROOT,
    "/opt/pi-go/pi/current/lib/node_modules/@earendil-works/pi-coding-agent",
  ].filter(Boolean);
  for (const c of candidates) {
    if (existsSync(join(c, "package.json"))) return realpathSync(c);
  }
  throw new Error("找不到 pi 安装目录（设置 PIGO_PI_ROOT）");
}

/** 自下而上查找依赖包目录（npm 可能嵌套也可能提升）。 */
export function findPackage(name, from = piRoot()) {
  let dir = from;
  for (;;) {
    const p = join(dir, "node_modules", name);
    if (existsSync(join(p, "package.json"))) return p;
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  throw new Error(`找不到依赖包 ${name}`);
}

export async function importFrom(pkgDir, rel) {
  return import(pathToFileURL(join(pkgDir, rel)).href);
}

export async function piVersion() {
  const { readFileSync } = await import("node:fs");
  return JSON.parse(readFileSync(join(piRoot(), "package.json"), "utf8")).version;
}
