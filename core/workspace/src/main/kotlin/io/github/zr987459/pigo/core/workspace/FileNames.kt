package io.github.zr987459.pigo.core.workspace

import java.io.File

/** 导入文件的命名：清理非法字符、重名自动加序号。 */
object FileNames {
    private const val MAX_NAME_BYTES = 200

    /** 清理来自 SAF / 分享的显示名：去掉路径分隔符、控制字符，空名用 [fallback]，过长时保留扩展名截断。 */
    fun sanitize(name: String?, fallback: String = "file"): String {
        var n = (name ?: "").substringAfterLast('/').substringAfterLast('\\')
        n = n.filter { it >= ' ' && it != '\u007f' && it != ':' && it != '*' && it != '?' && it != '"' && it != '<' && it != '>' && it != '|' }.trim()
        n = n.trimStart('.').ifEmpty { "" }
        if (n.isEmpty()) n = fallback
        if (n.toByteArray().size > MAX_NAME_BYTES) {
            val ext = n.substringAfterLast('.', "").take(16)
            val base = n.removeSuffix(if (ext.isEmpty()) "" else ".$ext")
            var cut = base
            while ((cut + if (ext.isEmpty()) "" else ".$ext").toByteArray().size > MAX_NAME_BYTES) cut = cut.dropLast(1)
            n = cut + if (ext.isEmpty()) "" else ".$ext"
        }
        return n
    }

    /** `a.txt` 已存在 → `a (1).txt`、`a (2).txt`…；`.tar.gz` 这类双扩展名按最后一个点切。 */
    fun unique(dir: File, name: String): File {
        val first = File(dir, name)
        if (!GuestPaths.existsNoFollow(first)) return first
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val f = File(dir, "$base ($i)$ext")
            if (!GuestPaths.existsNoFollow(f)) return f
            i++
        }
    }
}
