package io.github.zr987459.pigo.feature.settings

/**
 * 自定义端点对话框里「获取模型列表」的界面状态（纯数据，JVM 单测覆盖）。
 *
 * 审查第 2 轮 Nit：地址或 Key 一改，上一次获取到的模型列表就不再可信（可能属于旧地址），
 * [inputChanged] 会连同提示一起清空，旧模型不能再被点选加入；已经加进「模型 id」输入框的内容由用户自己决定，不动。
 */
data class DiscoveryUi(
    val discovered: List<String> = emptyList(),
    val message: String? = null,
    val running: Boolean = false,
) {
    /** 地址 / Key 变化：作废旧结果与提示，按钮恢复可点。 */
    fun inputChanged(): DiscoveryUi = DiscoveryUi()

    fun started(): DiscoveryUi = copy(running = true, message = null)

    fun succeeded(models: List<String>): DiscoveryUi =
        copy(discovered = models, message = "找到 ${models.size} 个模型，点选加入（也可以手动填写）")

    fun failed(reason: String): DiscoveryUi = copy(
        discovered = emptyList(),
        message = "获取模型列表失败：${reason.ifBlank { "未知错误" }}。可以在下面手动填写模型 id。",
    )

    fun finished(): DiscoveryUi = copy(running = false)
}
