package io.github.zr987459.pigo.feature.files.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle

/**
 * 轻量代码高亮：注释、字符串、数字、关键字四类，按行处理（跨行的块注释 / 多行字符串按状态延续）。
 * 不追求完整语法，只为在手机上读代码时有基本层次；未知语言不着色。
 */
internal class Highlighter(language: String?, dark: Boolean) {
    private val lang = language
    private val kw: Set<String> = when (language) {
        "kotlin" -> setOf("val", "var", "fun", "class", "object", "interface", "if", "else", "when", "for", "while", "return", "import", "package", "private", "internal", "public", "override", "suspend", "data", "sealed", "enum", "companion", "null", "true", "false", "is", "in", "as", "try", "catch", "finally", "throw", "this", "super", "const", "lateinit", "by", "init")
        "java" -> setOf("class", "interface", "public", "private", "protected", "static", "final", "void", "if", "else", "for", "while", "return", "import", "package", "new", "null", "true", "false", "try", "catch", "finally", "throw", "throws", "extends", "implements", "this", "super", "int", "long", "boolean", "switch", "case")
        "javascript", "typescript", "vue" -> setOf("const", "let", "var", "function", "return", "if", "else", "for", "while", "import", "export", "from", "default", "class", "extends", "new", "null", "undefined", "true", "false", "async", "await", "try", "catch", "finally", "throw", "this", "typeof", "interface", "type", "enum", "of", "in")
        "python" -> setOf("def", "class", "return", "if", "elif", "else", "for", "while", "import", "from", "as", "None", "True", "False", "try", "except", "finally", "raise", "with", "lambda", "yield", "async", "await", "pass", "in", "not", "and", "or", "is", "self")
        "go" -> setOf("func", "package", "import", "return", "if", "else", "for", "range", "var", "const", "type", "struct", "interface", "nil", "true", "false", "go", "defer", "chan", "map", "switch", "case")
        "rust" -> setOf("fn", "let", "mut", "pub", "struct", "enum", "impl", "trait", "use", "mod", "return", "if", "else", "match", "for", "while", "loop", "self", "Self", "true", "false", "None", "Some", "Ok", "Err", "async", "await")
        "bash" -> setOf("if", "then", "else", "fi", "for", "do", "done", "while", "case", "esac", "function", "return", "export", "local", "echo", "in")
        "c", "cpp", "csharp", "swift", "dart", "php" -> setOf("if", "else", "for", "while", "return", "class", "struct", "public", "private", "static", "void", "int", "const", "new", "null", "true", "false", "import", "include", "namespace", "using", "func", "var", "let")
        else -> emptySet()
    }
    private val hash = language in setOf("python", "bash", "yaml", "toml", "ruby", "ini", "makefile", "dockerfile")
    private val slash = language in setOf("kotlin", "java", "javascript", "typescript", "go", "rust", "c", "cpp", "csharp", "swift", "dart", "php", "scss", "css", "groovy", "vue")
    private val enabled = kw.isNotEmpty() || hash || slash || language == "json"
    private var inBlock = false

    private val cComment = if (dark) Color(0xFF7F8C8D) else Color(0xFF6A737D)
    private val cString = if (dark) Color(0xFF98C379) else Color(0xFF22863A)
    private val cNumber = if (dark) Color(0xFFD19A66) else Color(0xFF005CC5)
    private val cKeyword = if (dark) Color(0xFFC678DD) else Color(0xFFD73A49)

    fun line(text: String): AnnotatedString {
        if (!enabled) return AnnotatedString(text)
        return buildAnnotatedString {
            var i = 0
            val n = text.length
            while (i < n) {
                if (inBlock) {
                    val end = text.indexOf("*/", i)
                    val stop = if (end < 0) n else end + 2
                    styled(text.substring(i, stop), SpanStyle(color = cComment, fontStyle = FontStyle.Italic))
                    if (end >= 0) inBlock = false
                    i = stop
                    continue
                }
                val c = text[i]
                when {
                    slash && c == '/' && i + 1 < n && text[i + 1] == '/' || hash && c == '#' -> {
                        styled(text.substring(i), SpanStyle(color = cComment, fontStyle = FontStyle.Italic)); i = n
                    }
                    slash && c == '/' && i + 1 < n && text[i + 1] == '*' -> { inBlock = true }
                    c == '"' || c == '\'' || (c == '`' && lang in setOf("javascript", "typescript", "go")) -> {
                        var j = i + 1
                        while (j < n && text[j] != c) { if (text[j] == '\\') j++; j++ }
                        val stop = minOf(n, j + 1)
                        styled(text.substring(i, stop), SpanStyle(color = cString)); i = stop
                    }
                    c.isDigit() && (i == 0 || !text[i - 1].isLetterOrDigit() && text[i - 1] != '_') -> {
                        var j = i
                        while (j < n && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '_')) j++
                        styled(text.substring(i, j), SpanStyle(color = cNumber)); i = j
                    }
                    c.isLetter() || c == '_' -> {
                        var j = i
                        while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                        val w = text.substring(i, j)
                        if (w in kw) styled(w, SpanStyle(color = cKeyword)) else append(w)
                        i = j
                    }
                    else -> { append(c); i++ }
                }
            }
        }
    }

    private fun AnnotatedString.Builder.styled(s: String, st: SpanStyle) {
        val start = length
        append(s)
        addStyle(st, start, length)
    }
}
