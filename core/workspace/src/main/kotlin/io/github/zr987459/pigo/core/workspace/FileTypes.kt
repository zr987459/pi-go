package io.github.zr987459.pigo.core.workspace

/**
 * 按扩展名判断文件类型与代码语言。MIME 表参考 Pi-Web `lib/file-types.ts`（MIT，见 docs/notices/feat-files.md）。
 */
object FileTypes {
    const val IMAGE_PREVIEW_MAX_BYTES = 10L * 1024 * 1024

    private val IMAGE = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
        "webp" to "image/webp", "bmp" to "image/bmp", "ico" to "image/x-icon", "heic" to "image/heic", "heif" to "image/heif",
        "avif" to "image/avif",
    )
    private val EXTERNAL = mapOf(
        "pdf" to "application/pdf",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "doc" to "application/msword", "xls" to "application/vnd.ms-excel",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg",
        "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm", "mov" to "video/quicktime",
        "zip" to "application/zip", "apk" to "application/vnd.android.package-archive",
        "svg" to "image/svg+xml",
    )
    private val TEXT = mapOf(
        "txt" to "text/plain", "md" to "text/markdown", "markdown" to "text/markdown", "json" to "application/json",
        "jsonl" to "application/x-ndjson", "html" to "text/html", "htm" to "text/html", "css" to "text/css", "csv" to "text/csv",
        "xml" to "text/xml", "yaml" to "text/yaml", "yml" to "text/yaml", "log" to "text/plain",
    )
    private val LANG = mapOf(
        "kt" to "kotlin", "kts" to "kotlin", "java" to "java", "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript",
        "jsx" to "javascript", "ts" to "typescript", "tsx" to "typescript", "py" to "python", "rb" to "ruby", "go" to "go",
        "rs" to "rust", "c" to "c", "h" to "c", "cc" to "cpp", "cpp" to "cpp", "hpp" to "cpp", "cs" to "csharp", "swift" to "swift",
        "php" to "php", "sh" to "bash", "bash" to "bash", "zsh" to "bash", "fish" to "bash", "ps1" to "powershell",
        "sql" to "sql", "json" to "json", "jsonl" to "json", "yaml" to "yaml", "yml" to "yaml", "toml" to "toml", "ini" to "ini",
        "xml" to "xml", "html" to "html", "htm" to "html", "css" to "css", "scss" to "scss", "md" to "markdown",
        "markdown" to "markdown", "gradle" to "groovy", "groovy" to "groovy", "lua" to "lua", "dart" to "dart", "vue" to "vue",
        "dockerfile" to "dockerfile", "makefile" to "makefile", "diff" to "diff", "patch" to "diff",
    )

    fun ext(path: String): String {
        val name = path.substringAfterLast('/')
        val lower = name.lowercase()
        if (lower == "dockerfile" || lower == "makefile") return lower
        return if ('.' in name) lower.substringAfterLast('.') else ""
    }

    fun mime(path: String): String? = ext(path).let { IMAGE[it] ?: EXTERNAL[it] ?: TEXT[it] }
    fun isImage(path: String) = ext(path) in IMAGE && ext(path) != "svg"
    fun isExternal(path: String) = ext(path) in EXTERNAL
    fun isMarkdown(path: String) = ext(path) == "md" || ext(path) == "markdown"
    fun language(path: String): String? = LANG[ext(path)]
}
