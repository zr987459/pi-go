package io.github.zr987459.pigo.feature.settings

/**
 * 设置页结构（DESIGN-r3 §6.1）：设置只剩「模型」一组。界面按这个结构渲染，测试据此断言根节点只有一组。
 * pi 的其他设置不在这里，在 ⌘ › pi 设置（§6.5），页底只给一行指引 [FOOTER_HINT]。
 */
object ModelsSettings {

    data class Group(val key: String, val title: String, val sections: List<Section>)

    data class Section(val key: String, val title: String)

    const val ROOT_GROUP = "models"
    const val FOOTER_HINT = "pi 的其他设置在 ⌘ › pi 设置"

    /** 根节点：唯一的「模型」组。 */
    fun root(providerCount: Int = 42): List<Group> = listOf(
        Group(
            key = ROOT_GROUP,
            title = "模型",
            sections = listOf(
                Section("default", "默认模型"),
                Section("scoped", "常用模型"),
                Section("connected", "已连接"),
                Section("providers", "全部服务商（$providerCount）"),
                Section("custom", "自定义"),
                Section("advanced", "高级"),
            ),
        ),
    )

    /** 「高级」里的三项（思考预算 `thinkingBudgets`、每模型思考档 `modelThinkingLevels`、刷新模型目录）。 */
    val ADVANCED_ITEMS = listOf("思考预算", "每模型思考档", "刷新模型目录")
}
