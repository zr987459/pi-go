@file:Suppress("unused")

package io.github.zr987459.pigo.feature.usage.logic

/*
 * r3：压缩摘要解析已并入 core/protocol 的 CompactionSummary（DESIGN-r3 §8.2 第 3 条，r3/core）。
 * 本文件只剩转发桥接，引用方（feature/usage/ui/CompactionCard.kt，r3/app）改指或删除后由 r3/session 删除。
 */
@Deprecated("r3: 用 io.github.zr987459.pigo.core.protocol.CompactionSummary.Parsed；r3/session 删除桥接")
typealias ParsedCompactionSummary = io.github.zr987459.pigo.core.protocol.CompactionSummary.Parsed

@Deprecated("r3: 用 io.github.zr987459.pigo.core.protocol.CompactionSummary；r3/session 删除桥接")
typealias CompactionSummary = io.github.zr987459.pigo.core.protocol.CompactionSummary
