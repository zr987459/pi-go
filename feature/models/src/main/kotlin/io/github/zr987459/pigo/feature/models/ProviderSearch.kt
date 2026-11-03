package io.github.zr987459.pigo.feature.models

import java.util.Locale

/**
 * 服务商搜索（DESIGN-r3 §6.1）：按 id、显示名、显示名的拼音首字母匹配，例如「qw」→ qwen、「ty」→ 通义、「zp」→ 智谱。
 *
 * 不引入拼音库：只带服务商名里会出现的常用汉字的声母首字母表（[INITIALS]）；表外汉字不参与首字母匹配，
 * 但仍可按原字搜索。匹配规则：查询词（去空格、小写）是 id、显示名或首字母串中**某个词的前缀**，
 * 或是 id / 显示名的子串（至少 2 个字符），避免单字母把无关服务商带出来。
 */
object ProviderSearch {

    data class Row(val id: String, val label: String)

    fun filter(rows: List<Row>, query: String): List<Row> {
        val q = normalize(query)
        if (q.isEmpty()) return rows
        return rows.filter { matches(it, q) }
    }

    fun matches(row: Row, query: String): Boolean {
        val q = normalize(query)
        if (q.isEmpty()) return true
        val id = row.id.lowercase(Locale.ROOT)
        val label = row.label.lowercase(Locale.ROOT)
        if (words(id).any { it.startsWith(q) } || words(label).any { it.startsWith(q) }) return true
        if (id.replace("-", "").startsWith(q) || label.replace(" ", "").startsWith(q)) return true
        if (q.length >= 2 && (id.contains(q) || label.contains(q))) return true
        val ini = initials(row.label)
        return ini.isNotEmpty() && words(ini).any { it.startsWith(q) }
    }

    /** 显示名的拼音首字母；表外汉字跳过，ASCII 单词取首字母，中英文之间以空格分词。例：「通义 Token Plan（中国）」→ "ty tp zg"。 */
    fun initials(label: String): String {
        val out = StringBuilder()
        var inAsciiWord = false
        var lastWasHan = false
        for (ch in label) {
            val ini = INITIALS[ch]
            when {
                ini != null -> {
                    if (!lastWasHan && out.isNotEmpty() && out.last() != ' ') out.append(' ')
                    out.append(ini); lastWasHan = true; inAsciiWord = false
                }
                ch.isLetterOrDigit() && ch.code < 128 -> {
                    if (!inAsciiWord) {
                        if (out.isNotEmpty() && out.last() != ' ' && !lastWasHan) out.append(' ')
                        if (lastWasHan) out.append(' ')
                        out.append(ch.lowercaseChar())
                    }
                    inAsciiWord = true; lastWasHan = false
                }
                else -> { inAsciiWord = false; lastWasHan = false; if (out.isNotEmpty() && out.last() != ' ') out.append(' ') }
            }
        }
        return out.toString().trim()
    }

    private fun normalize(q: String) = q.trim().lowercase(Locale.ROOT).replace(" ", "")

    private fun words(s: String): List<String> = s.split(' ', '-', '_', '.', '/', '（', '）', '(', ')').filter { it.isNotEmpty() }

    /** 服务商名与常见说明里出现的汉字 → 拼音首字母。 */
    private val INITIALS: Map<Char, Char> = buildMap<Char, Char> {
        fun add(chars: String, c: Char) = chars.forEach { ch -> this[ch] = c }
        add("阿安奥", 'a')
        add("百版包北本比边部宝", 'b')
        add("测超程城成从存草", 'c')
        add("丹到道德地点电定动度端豆大", 'd')
        add("而二", 'e')
        add("方分费服福飞", 'f')
        add("个国谷关管歌", 'g')
        add("海航号和红华化火合", 'h')
        add("际加家坚金旧技节晶京界极", 'j')
        add("开科可快酷", 'k')
        add("类灵零流龙蓝联量览", 'l')
        add("马蚂码美米模幂秘魔", 'm')
        add("内能纳", 'n')
        add("欧", 'o')
        add("坡平普盘", 'p')
        add("千启前清器企", 'q')
        add("人日容", 'r')
        add("商深生省时识书数斯思搜索", 's')
        add("特腾天通同", 't')
        add("外网微文问", 'w')
        add("小新星讯象行线", 'x')
        add("亚言阳页一移义亿易应用语元云月约", 'y')
        add("在站智中重州字自资账钟", 'z')
        add("谱", 'p')
    }
}
