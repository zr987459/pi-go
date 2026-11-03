package io.github.zr987459.pigo.core.session

/**
 * `@文件` 引用拼进提示的格式（DESIGN-r3 §2.12、§7.3 AtFileExpansionSnapshotTest）。
 *
 * 依据 pi 1.5.0 交互模式：`@` 补全由 `@earendil-works/pi-tui@1.5.0` `dist/autocomplete.js` 的
 * `buildCompletionValue` 写进编辑器——`@路径`，目录带结尾 `/`；路径含空白或 CJK 标点（`autocompleteSeparatorRegex`）
 * 时加引号 `@"路径"`；文件补全后跟一个空格、目录不跟。交互模式提交时**不展开文件内容**（展开成
 * `<file name="…">` 只发生在 CLI 参数 `pi @file`，见 `dist/cli/file-processor.js`），文字原样作为 prompt 发给模型。
 */
object AtFileExpansion {
    private val separator = Regex("[\\s\\u3000-\\u303F\\uFF01-\\uFF0F\\uFF1A-\\uFF20\\uFF3B-\\uFF40\\uFF5B-\\uFF65]")

    /** 一个引用在编辑器里的写法（不含结尾空格）。[path] 以 `/` 结尾表示目录。 */
    fun token(path: String): String = if (separator.containsMatchIn(path)) "@\"$path\"" else "@$path"

    /** 草稿文字 + 引用 → 发给 pi 的 prompt：引用按 pi 补全的写法放在文字前，各自后跟空格（目录除外，但与文字之间总有分隔）。 */
    fun compose(text: String, fileRefs: List<String>): String {
        if (fileRefs.isEmpty()) return text
        val sb = StringBuilder()
        for (ref in fileRefs) {
            if (sb.isNotEmpty() && !sb.endsWith(" ") && !sb.endsWith("/")) sb.append(' ')
            if (sb.isNotEmpty() && sb.endsWith("/")) sb.append(' ')
            sb.append(token(ref))
            if (!ref.endsWith("/")) sb.append(' ')
        }
        if (text.isEmpty()) return sb.toString().trimEnd()
        if (!sb.endsWith(" ")) sb.append(' ')
        return sb.append(text).toString()
    }
}
