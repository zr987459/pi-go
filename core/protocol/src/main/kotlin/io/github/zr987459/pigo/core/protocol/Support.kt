package io.github.zr987459.pigo.core.protocol

/**
 * 日志/诊断脱敏（DESIGN.md §2.2 AppLog、§3.2 stderr）。写入前统一调用。
 * 覆盖：`sk-…` 形态的 Key、`Bearer …`、`XXX_API_KEY=…` / `PIGO_KEY_XXX=…` 环境变量赋值、调用方已知的 Key 原文。
 */
class Redactor(knownSecrets: () -> Collection<String> = { emptyList() }) {
    private val known = knownSecrets

    fun redact(text: String): String {
        var t = text
        for (s in known()) {
            if (s.length >= MIN_SECRET_LEN) t = t.replace(s, MASK)
        }
        t = BEARER.replace(t) { "${it.groupValues[1]} $MASK" }
        t = ENV_ASSIGN.replace(t) { "${it.groupValues[1]}=$MASK" }
        t = JSON_KEY.replace(t) { "${it.groupValues[1]}$MASK\"" }
        t = SK.replace(t) { "sk-$MASK" }
        return t
    }

    companion object {
        const val MASK = "***"
        private const val MIN_SECRET_LEN = 6
        private val BEARER = Regex("(?i)\\b(Bearer)\\s+[A-Za-z0-9._~+/=\\-]{6,}")
        private val ENV_ASSIGN = Regex("\\b([A-Z][A-Z0-9_]*(?:API_KEY|_TOKEN|_SECRET)|PIGO_KEY_[A-Z0-9_]+)=\\S+")
        private val JSON_KEY = Regex("(\"(?:apiKey|api_key|authorization)\"\\s*:\\s*\")[^\"]{6,}\"", RegexOption.IGNORE_CASE)
        private val SK = Regex("\\bsk-[A-Za-z0-9_\\-]{6,}")
    }
}

/** 固定容量的行环形缓冲（stderr 尾部 500 行，DESIGN.md §3.2）。线程安全。 */
class LineRing(val capacity: Int = 500) {
    private val lines = ArrayDeque<String>(capacity)

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun add(line: String) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(line)
    }

    @Synchronized
    fun tail(n: Int = capacity): List<String> = lines.toList().takeLast(n.coerceAtLeast(0))

    @Synchronized
    fun clear() = lines.clear()

    val size: Int
        @Synchronized get() = lines.size
}

/** 默认命令超时（DESIGN.md §3.4）：abort 20 s，get_messages 30 s，其余 15 s；启动握手 20 s。 */
object RpcTimeouts {
    const val BOOT_MS = 20_000L
    const val ABORT_MS = 20_000L
    const val GET_MESSAGES_MS = 30_000L
    const val DEFAULT_MS = 15_000L

    fun forCommand(type: String): Long = when (type) {
        "abort" -> ABORT_MS
        "get_messages" -> GET_MESSAGES_MS
        else -> DEFAULT_MS
    }
}

/** 客户端命令 id：`c-<自增>`（DESIGN.md §3.4）。线程安全。 */
class IdGenerator(private val prefix: String = "c-") {
    private val next = java.util.concurrent.atomic.AtomicLong(1)
    fun next(): String = prefix + next.getAndIncrement()
}
