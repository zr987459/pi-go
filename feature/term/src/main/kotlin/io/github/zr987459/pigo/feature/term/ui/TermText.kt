package io.github.zr987459.pigo.feature.term.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.zr987459.pigo.feature.term.format.Span
import io.github.zr987459.pigo.feature.term.format.TermLine

/** 等宽字体样式：关连字（`calt 0`），一字一格（DESIGN-r3 §3.1）。 */
fun monoStyle(size: TextUnit, tokens: PiTokens): TextStyle = TextStyle(
    fontFamily = JetBrainsMono,
    fontSize = size,
    lineHeight = (size.value * 1.35f).sp,
    fontFeatureSettings = "calt 0, liga 0",
    color = tokens.fg,
)

fun spansToAnnotated(spans: List<Span>, tokens: PiTokens): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        withStyle(
            SpanStyle(
                color = tokens.fgOf(s.token),
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                textDecoration = if (s.token == "mdLink") TextDecoration.Underline else null,
            ),
        ) { append(s.text) }
    }
}

/** 一行终端输出：底色铺满整行（工具块三态、用户消息底色）。 */
@Composable
fun TermLineText(line: TermLine, size: TextUnit, tokens: PiTokens, modifier: Modifier = Modifier) {
    val bg = line.bg?.let { tokens.bgOf(it) }
    Text(
        text = spansToAnnotated(line.spans, tokens),
        style = monoStyle(size, tokens),
        softWrap = false,
        maxLines = 1,
        modifier = modifier.fillMaxWidth().let { if (bg != null) it.background(bg) else it },
    )
}
