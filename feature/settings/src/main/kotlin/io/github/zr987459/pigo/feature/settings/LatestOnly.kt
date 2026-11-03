package io.github.zr987459.pigo.feature.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 「只认最新一次」的请求闸门（自动获取模型列表，审查 R1 #2）。
 *
 * 获取模型列表底层是阻塞的 HttpURLConnection，协程被取消后也要等请求跑完或超时才返回。
 * 旧请求晚到的结果（或它恢复时抛出的取消异常）不能覆盖新请求的结果：
 * - 每次请求先 [begin] 拿一个序号；输入框变化时 [invalidate]，之前发出的请求全部作废；
 * - [run] 里取消异常原样抛出，绝不当成「获取失败」显示；
 * - 只有序号仍是最新、协程仍在活动时才返回结果，否则返回 null（调用方什么都不写）。
 */
class LatestOnly {
    @Volatile
    private var seq = 0

    fun begin(): Int = synchronized(this) { ++seq }

    fun invalidate() {
        synchronized(this) { seq++ }
    }

    fun isLatest(token: Int): Boolean = token == seq

    /**
     * @param onError 普通失败（非取消）转成结果，例如「获取模型列表失败：…」。
     * @return 最新一次请求的结果；已作废或已取消时为 null。
     */
    suspend fun <T> run(token: Int, onError: (Throwable) -> T, block: suspend () -> T): T? {
        val r = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onError(t)
        }
        currentCoroutineContext().ensureActive()
        return if (isLatest(token)) r else null
    }
}
