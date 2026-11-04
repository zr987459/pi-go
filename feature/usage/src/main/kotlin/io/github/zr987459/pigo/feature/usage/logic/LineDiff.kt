@file:Suppress("unused")

package io.github.zr987459.pigo.feature.usage.logic

/*
 * r3：LineDiff 已移到 core/session（DESIGN-r3 §8.2 第 3 条「移动不复制」）。这里只是转发桥接，
 * 等 r3/app 把 feature/usage 的引用改指新位置（或删除 feature/usage）后由 r3/session 删除。
 */
@Deprecated("r3: 已移到 io.github.zr987459.pigo.core.session.text.DiffKind；r3/session 删除桥接")
typealias DiffKind = io.github.zr987459.pigo.core.session.text.DiffKind

@Deprecated("r3: 已移到 io.github.zr987459.pigo.core.session.text.DiffLine；r3/session 删除桥接")
typealias DiffLine = io.github.zr987459.pigo.core.session.text.DiffLine

@Deprecated("r3: 已移到 io.github.zr987459.pigo.core.session.text.LineDiff；r3/session 删除桥接")
typealias LineDiff = io.github.zr987459.pigo.core.session.text.LineDiff
