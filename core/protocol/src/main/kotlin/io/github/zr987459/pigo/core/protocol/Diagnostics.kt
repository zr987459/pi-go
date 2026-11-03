package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.ErrCode

/** 诊断提示后用户可去的地方（界面映射成按钮）。 */
enum class FixAction { NONE, MODEL_SETTINGS, STORAGE_SETTINGS, RETRY, REPAIR_RUNTIME, DIAGNOSTICS }

data class Diagnosis(val kind: ErrCode, val hint: String, val action: FixAction)

/**
 * stderr 归类（DESIGN.md §3.9 表）。输入为（已脱敏的）stderr 尾部与退出码，输出一句中文提示。
 * 关键字按优先级匹配：OOM 往往伴随其他报错，最先判断；proot 自身报错先于 Node 报错。
 * 关键字以桌面 pi 1.5.0 / Node 实际输出校准，真机日志校准见 PR「需真机验证」。
 */
object StderrClassifier {
    private data class Rule(val kind: ErrCode, val hint: String, val action: FixAction, val patterns: List<Regex>)

    private fun r(vararg p: String) = p.map { Regex(it, RegexOption.IGNORE_CASE) }

    private val RULES = listOf(
        Rule(
            ErrCode.OOM, "内存不足，已自动重启；可在设置里减少并发任务", FixAction.NONE,
            r("JavaScript heap out of memory", "Reached heap limit", "Allocation failed - process out of memory", "\\bENOMEM\\b"),
        ),
        Rule(ErrCode.DISK_FULL, "手机存储空间不足", FixAction.STORAGE_SETTINGS, r("\\bENOSPC\\b", "No space left on device")),
        Rule(
            ErrCode.ENGINE_START, "容器无法启动（系统限制）", FixAction.DIAGNOSTICS,
            r("proot error", "proot warning: .*ptrace", "\\bptrace\\b", "proot info: .*(fail|cannot)", "seccomp"),
        ),
        Rule(
            ErrCode.ENGINE_START, "pi 安装不完整", FixAction.REPAIR_RUNTIME,
            r(
                "Cannot find module", "ERR_MODULE_NOT_FOUND", "pi: not found", "pi: command not found", "node: not found",
                "No such file or directory.*(\\bpi\\b|node)", "(\\bpi|node)'?: No such file or directory", "exec format error",
            ),
        ),
        Rule(
            ErrCode.NETWORK, "网络或 DNS 不可用", FixAction.RETRY,
            r("\\bENOTFOUND\\b", "\\bEAI_AGAIN\\b", "\\bECONNRESET\\b", "\\bECONNREFUSED\\b", "\\bETIMEDOUT\\b", "\\bENETUNREACH\\b"),
        ),
        Rule(
            ErrCode.NO_MODEL, "还没有配置可用的模型", FixAction.MODEL_SETTINGS,
            r("No API key found", "No models? available", "no model selected", "No model configured", "API key .*not (set|configured|found)"),
        ),
    )

    /**
     * @param stderr stderr 尾部（旧→新）
     * @param exitCode 进程退出码；仍在运行时为 null
     * @param beforeReady 是否在首条 `get_state` 回应前就退出（启动失败 vs 运行中崩溃）
     */
    fun classify(stderr: List<String>, exitCode: Int? = null, beforeReady: Boolean = true): Diagnosis {
        for (rule in RULES) {
            if (stderr.any { line -> rule.patterns.any { it.containsMatchIn(line) } }) {
                return Diagnosis(rule.kind, rule.hint, rule.action)
            }
        }
        // 137 = 128+SIGKILL：通常是系统低内存杀进程（lmkd）或我们自己的强杀。
        if (exitCode == 137 && !beforeReady) return Diagnosis(ErrCode.OOM, "引擎被系统结束（可能是内存不足），已自动重启", FixAction.NONE)
        return if (beforeReady) {
            Diagnosis(ErrCode.ENGINE_START, "pi 启动失败", FixAction.DIAGNOSTICS)
        } else {
            Diagnosis(ErrCode.ENGINE_EXIT, "pi 意外退出" + (exitCode?.let { "（退出码 $it）" } ?: ""), FixAction.DIAGNOSTICS)
        }
    }
}

data class ProviderHint(val kind: ErrCode, val text: String, val action: FixAction)

/**
 * 模型/网络失败（`message_end.stopReason=="error"` 的 `errorMessage`、`auto_retry_end.finalError`、`prompt` 的 `error`）
 * → 中文提示（DESIGN.md §3.9）。原文由界面另行展开显示。
 */
object ProviderErrors {
    private fun status(msg: String, vararg codes: Int) = codes.any { Regex("(^|\\D)$it(\\D|$)").containsMatchIn(msg) }

    private fun has(msg: String, vararg words: String) = words.any { msg.contains(it, ignoreCase = true) }

    fun hint(errorMessage: String?): ProviderHint {
        val m = errorMessage.orEmpty()
        return when {
            has(m, "No API key", "no model selected", "No models available") ->
                ProviderHint(ErrCode.NO_MODEL, "还没有配置可用的模型或 Key", FixAction.MODEL_SETTINGS)
            status(m, 401, 403) || has(m, "invalid api key", "incorrect api key", "unauthorized", "authentication", "invalid_api_key", "forbidden") ->
                ProviderHint(ErrCode.PROVIDER, "Key 错误：服务商拒绝了这个 API Key（401/403），请到设置检查", FixAction.MODEL_SETTINGS)
            status(m, 402, 429) || has(m, "insufficient", "quota", "rate limit", "rate_limit", "余额", "balance") ->
                ProviderHint(ErrCode.PROVIDER, "额度不足或请求太频繁（402/429），请检查账户余额或稍后再试", FixAction.RETRY)
            status(m, 404) || has(m, "model not found", "does not exist", "no such model", "unknown model") ->
                ProviderHint(ErrCode.NO_MODEL, "模型不存在（404），请到设置重新选择模型", FixAction.MODEL_SETTINGS)
            has(
                m, "ENOTFOUND", "EAI_AGAIN", "ECONNREFUSED", "ECONNRESET", "ETIMEDOUT", "ENETUNREACH", "fetch failed",
                "network", "socket hang up", "Connection error", "timed out", "timeout",
            ) -> ProviderHint(ErrCode.NETWORK, "无网络或连不上模型服务，请检查网络后重试", FixAction.RETRY)
            status(m, 500, 502, 503, 504, 529) || has(m, "overloaded", "server error", "unavailable") ->
                ProviderHint(ErrCode.PROVIDER, "模型服务暂时不可用，请稍后重试", FixAction.RETRY)
            else -> ProviderHint(ErrCode.PROVIDER, "模型调用失败", FixAction.RETRY)
        }
    }
}
