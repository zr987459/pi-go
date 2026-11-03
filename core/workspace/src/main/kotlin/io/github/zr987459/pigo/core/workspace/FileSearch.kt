package io.github.zr987459.pigo.core.workspace

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * `@文件` 模糊搜索（DESIGN-r3 §1.2、清单 §10）。行为照 pi 1.5.0 交互界面用的
 * `@earendil-works/pi-tui` `CombinedAutocompleteProvider.getFuzzyFileSuggestions`：
 *
 * 1. 候选 = 搜索根第一层（`fd --max-depth 1`）+ 递归结果（`fd`，最多 [MAX_WALK_RESULTS] 条命中），去重；
 *    `fd` 参数为 `--type f --type d --follow --hidden --exclude .git`，默认遵守 `.gitignore`；
 * 2. 过滤：查询不含 `/` 时在**文件名**里找子串，含 `/` 时在**相对路径**里找（`--full-path`）；
 *    大小写按 fd 的 smart-case（查询全小写 = 不区分大小写）；
 * 3. 打分（`scoreEntry`）：文件名完全相同 100、以查询开头 80、文件名含查询 50、路径含查询 30，目录 +10；
 * 4. 排序：分数降序 → 路径层数升序 → 路径长度升序 → 字典序；取前 [limit] 条（pi 取 20）。
 *
 * 与 fd 的差异（容器里没有 fd，Android 上也不跑进程，改为宿主侧直接遍历 rootfs）：
 * - `.gitignore` 只支持常用写法：`name`、`*.ext`、`dir/`、`/anchored`、`a/**`、`#` 注释；**不支持 `!` 取反**；
 *   只读搜索根及其子目录里的 `.gitignore`（fd 还会读上层仓库根与全局 excludes）。
 * - 遍历上限 [MAX_VISITED] 个条目，防止在 `/root` 这类大目录里卡住。
 */
object FileSearch {
    const val MAX_WALK_RESULTS = 100
    const val MAX_VISITED = 50_000

    data class Entry(val path: String, val isDir: Boolean)

    data class Scored(val path: String, val isDir: Boolean, val score: Int)

    /**
     * @param base 搜索根的宿主目录
     * @param inAllowed 符号链接目标（宿主绝对路径）是否允许跟随（不越出 rootfs 与允许范围）
     */
    fun search(base: File, query: String, limit: Int, inAllowed: (File) -> Boolean = { true }): List<Scored> {
        val q = query.replace('\\', '/')
        val first = walk(base, q, MAX_WALK_RESULTS, maxDepth = 1, inAllowed)
        val rec = walk(base, q, MAX_WALK_RESULTS, maxDepth = Int.MAX_VALUE, inAllowed)
        val seen = HashSet<String>()
        val entries = (first + rec).filter { seen.add(it.path) }
        return entries
            .map { Scored(it.path, it.isDir, if (q.isEmpty()) 1 else score(it.path, q, it.isDir)) }
            .filter { it.score > 0 }
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenBy { it.path.split('/').count { s -> s.isNotEmpty() } }
                    .thenBy { it.path.length }
                    .thenBy { it.path },
            )
            .take(limit.coerceAtLeast(0))
    }

    /** pi-tui `scoreEntry`。 */
    fun score(path: String, query: String, isDir: Boolean): Int {
        val name = path.substringAfterLast('/').lowercase()
        val lq = query.lowercase()
        var s = when {
            name == lq -> 100
            name.startsWith(lq) -> 80
            name.contains(lq) -> 50
            path.lowercase().contains(lq) -> 30
            else -> 0
        }
        if (isDir && s > 0) s += 10
        return s
    }

    /** fd 的匹配：smart-case 子串；含 `/` 时匹配相对路径，否则匹配文件名。 */
    fun fdMatches(path: String, query: String): Boolean {
        if (query.isEmpty()) return true
        val trimmed = if (query.contains('/')) query.trim('/') else query
        if (trimmed.isEmpty()) return true
        val target = if (query.contains('/')) (path + (if (query.endsWith('/')) "/" else "")) else path.substringAfterLast('/')
        val needle = if (query.contains('/') && query.endsWith('/')) "$trimmed/" else trimmed
        val ignoreCase = needle == needle.lowercase()
        return target.contains(needle, ignoreCase)
    }

    /** pi 的插入格式：`@路径`，目录带 `/`，含空格时 `@"路径"`。 */
    fun completion(path: String, isDir: Boolean): String {
        val p = if (isDir) "$path/" else path
        return if (p.contains(' ')) "@\"$p\"" else "@$p"
    }

    private fun walk(base: File, query: String, max: Int, maxDepth: Int, inAllowed: (File) -> Boolean): List<Entry> {
        val out = ArrayList<Entry>()
        if (!base.isDirectory) return out
        var visited = 0
        val visitedDirs = HashSet<String>()
        // 栈元素：目录、相对路径、深度、生效的忽略规则
        data class Frame(val dir: File, val rel: String, val depth: Int, val rules: List<IgnoreRule>)
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(base, "", 0, IgnoreRule.parse(File(base, ".gitignore"), "")))
        runCatching { visitedDirs += base.canonicalPath }
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            val names = f.dir.list()?.sorted() ?: continue
            for (n in names) {
                if (++visited > MAX_VISITED) return out
                if (n == ".git") continue
                val child = File(f.dir, n)
                val rel = if (f.rel.isEmpty()) n else "${f.rel}/$n"
                val link = Files.isSymbolicLink(child.toPath())
                val isDir = if (link) {
                    val canon = runCatching { child.canonicalFile }.getOrNull() ?: continue
                    if (!inAllowed(canon)) continue
                    canon.isDirectory
                } else {
                    Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS)
                }
                if (IgnoreRule.ignored(f.rules, rel, isDir)) continue
                if (fdMatches(rel, query)) {
                    out += Entry(rel, isDir)
                    if (out.size >= max) return out
                }
                if (isDir && f.depth + 1 < maxDepth) {
                    val key = runCatching { child.canonicalPath }.getOrNull() ?: continue
                    if (!visitedDirs.add(key)) continue // 符号链接成环
                    stack.addLast(Frame(child, rel, f.depth + 1, f.rules + IgnoreRule.parse(File(child, ".gitignore"), rel)))
                }
            }
        }
        return out
    }
}

/**
 * `.gitignore` 的一条规则（常用子集，见 [FileSearch] 文档）。[baseRel] 是 `.gitignore` 所在目录相对搜索根的路径。
 */
data class IgnoreRule(val baseRel: String, val pattern: Regex, val dirOnly: Boolean) {
    fun matches(rel: String, isDir: Boolean): Boolean {
        if (dirOnly && !isDir) return false
        val local = when {
            baseRel.isEmpty() -> rel
            rel.startsWith("$baseRel/") -> rel.removePrefix("$baseRel/")
            else -> return false
        }
        return pattern.matches(local)
    }

    companion object {
        fun ignored(rules: List<IgnoreRule>, rel: String, isDir: Boolean): Boolean = rules.any { it.matches(rel, isDir) }

        fun parse(file: File, baseRel: String): List<IgnoreRule> {
            if (!file.isFile || file.length() > 256 * 1024) return emptyList()
            return runCatching { file.readLines() }.getOrDefault(emptyList()).mapNotNull { compile(it, baseRel) }
        }

        fun compile(line: String, baseRel: String): IgnoreRule? {
            var p = line.trimEnd()
            if (p.isEmpty() || p.startsWith("#") || p.startsWith("!")) return null
            if (p.startsWith("\\#")) p = p.substring(1)
            val dirOnly = p.endsWith("/")
            p = p.trimEnd('/')
            if (p.isEmpty()) return null
            // 含中间 `/` 或以 `/` 开头：相对 .gitignore 目录锚定；否则匹配任意层级的名字
            val anchored = p.startsWith("/") || p.contains('/')
            p = p.removePrefix("/")
            val body = globToRegex(p)
            val regex = if (anchored) "$body(/.*)?" else "(.*/)?$body(/.*)?"
            return IgnoreRule(baseRel, Regex(regex), dirOnly)
        }

        private fun globToRegex(glob: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && glob.startsWith("**/", i) -> { sb.append("(.*/)?"); i += 3; continue }
                    c == '*' && glob.startsWith("**", i) -> { sb.append(".*"); i += 2; continue }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    c == '[' -> {
                        val end = glob.indexOf(']', i + 1)
                        if (end > i) { sb.append('[').append(glob.substring(i + 1, end).replace("\\", "\\\\")).append(']'); i = end + 1; continue }
                        sb.append("\\[")
                    }
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            return sb.toString()
        }
    }
}
