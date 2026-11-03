package io.github.zr987459.pigo.contract

/** 跨模块的结果类型（DESIGN.md §2.2）。 */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val code: ErrCode, val message: String, val cause: Throwable? = null) : Outcome<Nothing>
}

enum class ErrCode {
    NETWORK, DISK_FULL, CHECKSUM, INSTALL_FAILED, ENGINE_START, ENGINE_EXIT,
    RPC_REJECTED, RPC_TIMEOUT, PROVIDER, NO_MODEL, OOM, CANCELLED, UNKNOWN,
}

enum class LogLevel { INFO, WARN }

data class LogLine(
    val timeMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/** 应用日志。实现必须在写入前统一脱敏（`sk-…`、`Bearer …`、已知 Key）。 */
interface AppLog {
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun tail(n: Int): List<LogLine>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(transform(value))
    is Outcome.Err -> this
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Ok)?.value
