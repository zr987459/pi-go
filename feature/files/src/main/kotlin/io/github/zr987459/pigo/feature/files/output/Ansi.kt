@file:Suppress("unused")

package io.github.zr987459.pigo.feature.files.output

/* r3：Ansi / OutputSearch 已移到 core/session（DESIGN-r3 §8.2 第 3 条）。转发桥接，r3/app 改指后由 r3/session 删除。 */
@Deprecated("r3: 已移到 io.github.zr987459.pigo.core.session.text.Ansi；r3/session 删除桥接")
typealias Ansi = io.github.zr987459.pigo.core.session.text.Ansi

@Deprecated("r3: 已移到 io.github.zr987459.pigo.core.session.text.OutputSearch；r3/session 删除桥接")
typealias OutputSearch = io.github.zr987459.pigo.core.session.text.OutputSearch
