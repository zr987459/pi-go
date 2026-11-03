package io.github.zr987459.pigo.feature.chat.logic

import androidx.compose.runtime.Immutable

enum class ErrorKind { NO_NETWORK, BAD_KEY, QUOTA, NO_MODEL, TIMEOUT, SERVER, UNKNOWN }

/** 错误卡内容：一句中文 + 给新手的建议 + 可展开的原文。 */
@Immutable
data class ErrorInfo(
    val kind: ErrorKind,
    val title: String,
    val hint: String,
    val raw: String?,
) {
    /** 是否值得提供「去设置模型」入口。 */
    val suggestsSettings: Boolean get() = kind == ErrorKind.BAD_KEY || kind == ErrorKind.NO_MODEL || kind == ErrorKind.QUOTA
}

/**
 * 把模型/网络失败的 `errorMessage` 映射成中文（DESIGN.md §3.9）：
 * 无网络 / Key 错误 401·403 / 额度不足 402·429 / 模型不存在 404。匹配不到时给通用提示，原文可展开。
 */
object ErrorText {

    private val status = Regex("""(?<![\d.])(4\d\d|5\d\d)(?![\d.])""")

    fun classify(errorMessage: String?): ErrorInfo {
        val raw = errorMessage?.takeIf { it.isNotBlank() }
        val msg = raw.orEmpty()
        val lower = msg.lowercase()
        val code = status.find(msg)?.value?.toIntOrNull()

        val kind = when {
            code == 401 || code == 403 ||
                "invalid api key" in lower || "invalid_api_key" in lower || "incorrect api key" in lower ||
                "unauthorized" in lower || "authentication" in lower || "api key" in lower && "invalid" in lower -> ErrorKind.BAD_KEY
            code == 402 || code == 429 || "insufficient" in lower || "quota" in lower ||
                "rate limit" in lower || "rate_limit" in lower || "balance" in lower -> ErrorKind.QUOTA
            code == 404 || "model_not_found" in lower || "model not found" in lower ||
                "does not exist" in lower && "model" in lower -> ErrorKind.NO_MODEL
            "enotfound" in lower || "eai_again" in lower || "econnreset" in lower || "econnrefused" in lower ||
                "enetunreach" in lower || "network" in lower || "fetch failed" in lower || "socket hang up" in lower -> ErrorKind.NO_NETWORK
            "etimedout" in lower || "timeout" in lower || "timed out" in lower -> ErrorKind.TIMEOUT
            code != null && code >= 500 -> ErrorKind.SERVER
            else -> ErrorKind.UNKNOWN
        }
        val (title, hint) = when (kind) {
            ErrorKind.BAD_KEY -> "Key 错误" to "API Key 无效或没有权限，请到「设置」里检查模型和 Key。"
            ErrorKind.QUOTA -> "额度不足或请求太频繁" to "账户余额/套餐额度可能用完了，或请求过于频繁，稍等再试。"
            ErrorKind.NO_MODEL -> "模型不存在" to "当前选择的模型不可用，请到「设置」里换一个模型。"
            ErrorKind.NO_NETWORK -> "无网络" to "连不上模型服务，请检查手机网络后重试。"
            ErrorKind.TIMEOUT -> "请求超时" to "模型服务响应太慢，请稍后重试。"
            ErrorKind.SERVER -> "模型服务出错" to "对方服务器暂时有问题，请稍后重试。"
            ErrorKind.UNKNOWN -> "出错了" to "这一轮没有完成，可以点「重试」再发一次。"
        }
        return ErrorInfo(kind, title, hint, raw)
    }
}
