package com.kai.agent.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.kai.agent.text.ReplyMarkdown

@Composable
internal fun MarkdownReply(markdown: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge, textAlign: TextAlign = TextAlign.Start) {
    val presentation = remember(markdown) { ReplyMarkdown.present(markdown) }
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val annotated = remember(presentation, colors, style, uriHandler) {
        AnnotatedString.Builder(presentation.text).apply {
            presentation.spans.forEach { span ->
                if (span.kind == ReplyMarkdown.Kind.LINK) {
                    addLink(LinkAnnotation.Url(span.value, TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline))) {
                        ReplyMarkdown.safeLink(span.value)?.let { url -> runCatching { uriHandler.openUri(url) } }
                    }, span.start, span.end)
                } else addStyle(when (span.kind) {
                    ReplyMarkdown.Kind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    ReplyMarkdown.Kind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    ReplyMarkdown.Kind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    ReplyMarkdown.Kind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceContainerHigh)
                    ReplyMarkdown.Kind.HEADING -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = when (span.value) { "1" -> 25.sp; "2" -> 22.sp; else -> 19.sp })
                    else -> SpanStyle()
                }, span.start, span.end)
            }
        }.toAnnotatedString()
    }
    Text(annotated, modifier = modifier, style = style, textAlign = textAlign)
}
