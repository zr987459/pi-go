@file:Suppress("unused")

package io.github.zr987459.pigo.feature.usage.logic

/* r3：SessionTreeRows 已移到 core/session（DESIGN-r3 §8.2 第 3 条）。转发桥接，引用方改指后删除。 */
@Deprecated("r3: 已移到 io.github.zr987459.pigo.core.session.text.TreeRow；r3/session 删除桥接")
typealias TreeRow = io.github.zr987459.pigo.core.session.text.TreeRow

@Deprecated("r3: 已移到 io.github.zr987459.pigo.core.session.text.SessionTreeRows；r3/session 删除桥接")
typealias SessionTreeRows = io.github.zr987459.pigo.core.session.text.SessionTreeRows
