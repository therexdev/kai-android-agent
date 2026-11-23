package com.kai.agent.text

import java.net.URI
import java.util.Locale
import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.*

/** Native text presentation only: no HTML renderer, remote images or executable link schemes. */
object ReplyMarkdown {
    enum class Kind { BOLD, ITALIC, CODE, STRIKE, HEADING, LINK }
    data class Span(val start: Int, val end: Int, val kind: Kind, val value: String = "")
    data class Presentation(val text: String, val spans: List<Span>)
    internal data class Spoken(val text: String, val starts: IntArray, val ends: IntArray)
    private val parsers = ThreadLocal.withInitial {
        Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()
    }
    private val autoLinks = Regex("https?://[^\\s<>()]+|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)

    fun safeLink(value: String): String? {
        if (value.length !in 1..8192 || value.any { it.isWhitespace() || it.isISOControl() } || Regex("%(?:0[ad]|00)", RegexOption.IGNORE_CASE).containsMatchIn(value)) return null
        return runCatching {
            val uri = URI(value)
            when (uri.scheme?.lowercase(Locale.ROOT)) {
                "http", "https" -> value.takeIf { !uri.host.isNullOrBlank() && uri.rawUserInfo == null }
                "mailto" -> value.takeIf { uri.rawSchemeSpecificPart.matches(Regex("[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)) }
                else -> null
            }
        }.getOrNull()
    }

    fun present(source: String): Presentation {
        val output = StringBuilder(); val spans = mutableListOf<Span>()
        fun separate(count: Int = 2) {
            if (output.isNotEmpty()) while (output.takeLast(count).count { it == '\n' } < count) output.append('\n')
        }
        fun children(node: Node, visit: (Node) -> Unit) { var child = node.firstChild; while (child != null) { visit(child); child = child.next } }
        fun visit(node: Node) {
            val block = node is Paragraph && node.parent !is ListItem || node is Heading || node is FencedCodeBlock || node is IndentedCodeBlock || node is TableBlock
            if (block) separate()
            val start = output.length
            when (node) {
                is Text -> output.append(node.literal)
                is Code -> output.append(node.literal)
                is FencedCodeBlock -> output.append(node.literal.trimEnd('\n', '\r'))
                is IndentedCodeBlock -> output.append(node.literal.trimEnd('\n', '\r'))
                is SoftLineBreak, is HardLineBreak -> output.append('\n')
                is ThematicBreak -> { separate(); output.append('—'); separate() }
                is HtmlInline -> output.append(node.literal) // Always literal native text, never HTML.
                is HtmlBlock -> output.append(node.literal.trimEnd())
                is ListItem -> {
                    separate(1)
                    val ordered = node.parent as? OrderedList
                    var index = ordered?.markerStartNumber ?: 1
                    var sibling = node.previous
                    while (sibling != null) { index++; sibling = sibling.previous }
                    var depth = 0; var parent = node.parent?.parent
                    while (parent != null) { if (parent is ListItem) depth++; parent = parent.parent }
                    output.append("  ".repeat(depth)).append(if (ordered == null) "• " else "$index. ")
                    children(node, ::visit)
                }
                is TableRow -> { separate(1); children(node, ::visit) }
                is TableCell -> { if (node.previous != null) output.append("   ·   "); children(node, ::visit) }
                else -> children(node, ::visit)
            }
            val end = output.length
            val kind = when (node) {
                is StrongEmphasis -> Kind.BOLD
                is Emphasis, is BlockQuote, is Image -> Kind.ITALIC
                is Code, is FencedCodeBlock, is IndentedCodeBlock, is HtmlBlock -> Kind.CODE
                is Strikethrough -> Kind.STRIKE
                is Heading -> Kind.HEADING
                is Link -> Kind.LINK
                is TableCell -> if (node.isHeader) Kind.BOLD else null
                else -> null
            }
            if (kind != null && end > start) {
                val value = when (node) { is Link -> safeLink(node.destination); is Heading -> node.level.toString(); else -> "" }
                if (value != null) spans += Span(start, end, kind, value)
            }
        }
        visit(parsers.get().parse(source))
        val text = output.toString().trimEnd()
        autoLinks.findAll(text).forEach { match ->
            val raw = match.value.trimEnd('.', ',', ';', ':', '!', '?')
            val destination = safeLink(if (raw.contains(Regex("^https?://", RegexOption.IGNORE_CASE))) raw else "mailto:$raw")
            val end = match.range.first + raw.length
            if (destination != null && spans.none { it.kind == Kind.LINK && it.start < end && it.end > match.range.first })
                spans += Span(match.range.first, end, Kind.LINK, destination)
        }
        return Presentation(text, spans.filter { it.start < text.length }.map { it.copy(end = it.end.coerceAtMost(text.length)) })
    }

    /** Readable speech plus UTF-16 source positions, so shortening markup cannot over-credit playback. */
    internal fun spoken(source: String, complete: Boolean): Spoken {
        if (source.none { it in "*_`[]#>\\|~&<" } &&
            !Regex("(?m)(?:^|\n) {0,3}(?:[-+]\\s|[0-9]+[.)]\\s|[-=]{3,})").containsMatchIn(source)) {
            return spokenLinks(Spoken(source, IntArray(source.length) { it }, IntArray(source.length) { it + 1 }), complete)
        }
        val root = parsers.get().parse(source)
        var stableEnd = source.length
        if (!complete) {
            fun inspect(node: Node) {
                if (node is Text && generateSequence(node.parent) { it.parent }.none { it is Link || it is Image }) {
                    node.sourceSpans.forEach { span ->
                        val end = (span.inputIndex + span.length).coerceAtMost(source.length)
                        var i = span.inputIndex
                        while (i < end) {
                            if (!escaped(source, i)) {
                                val c = source[i]
                                val run = if (c in "*_`") source.substring(i, end).takeWhile { it == c }.length else 1
                                val previous = source.getOrNull(i - 1); val next = source.getOrNull(i + run)
                                val possibleDelimiter = c in "*_`" && (c == '`' || previous == null || !previous.isLetterOrDigit() || run > 1) &&
                                    (next == null || !next.isWhitespace()) && !(previous?.isDigit() == true && next?.isDigit() == true)
                                if (c == '[' || possibleDelimiter || c == '\\' && i + 1 == source.length ||
                                    c == '&' && source.substring(i).matches(Regex("&(?:#[xX]?[0-9a-fA-F]*|[a-zA-Z]*)"))) stableEnd = minOf(stableEnd, i)
                                i += run
                            } else i++
                        }
                    }
                }
                if (node is FencedCodeBlock) {
                    val lineStart = source.lastIndexOf('\n') + 1
                    val lastLine = source.substring(lineStart)
                    if (lastLine.matches(Regex(" {0,3}[`~]{1,}"))) stableEnd = minOf(stableEnd, lineStart)
                }
                var child = node.firstChild; while (child != null) { inspect(child); child = child.next }
            }
            inspect(root)
        }
        var transformed = false
        fun hasFormatting(node: Node) {
            if (node is StrongEmphasis || node is Emphasis || node is Link || node is Image || node is Code ||
                node is FencedCodeBlock || node is IndentedCodeBlock || node is Heading || node is ListItem ||
                node is ThematicBreak || node is HtmlInline || node is HtmlBlock || node is Strikethrough || node is TableBlock || node is HardLineBreak || node is LinkReferenceDefinition)
                transformed = true
            if (node is Text) {
                val raw = node.sourceSpans.joinToString("") { source.substring(it.inputIndex, (it.inputIndex + it.length).coerceAtMost(source.length)) }
                if (raw != node.literal) transformed = true
            }
            var child = node.firstChild; while (child != null) { hasFormatting(child); child = child.next }
        }
        hasFormatting(root)
        if (!transformed) return spokenLinks(Spoken(source.take(stableEnd), IntArray(stableEnd) { it }, IntArray(stableEnd) { it + 1 }), complete)
        val text = StringBuilder(); val starts = mutableListOf<Int>(); val ends = mutableListOf<Int>()
        var rawCursor = 0
        fun emit(value: String, from: IntArray, to: IntArray) {
            for (i in value.indices) if (from[i] < stableEnd && to[i] <= stableEnd) {
                text.append(value[i]); starts += from[i]; ends += to[i]; rawCursor = maxOf(rawCursor, to[i])
            }
        }
        fun separator(count: Int, at: Int) {
            if (text.isNotEmpty()) while (text.takeLast(count).count { it == '\n' } < count) {
                text.append('\n'); starts += at; ends += at
            }
        }
        fun visit(node: Node) {
            val spans = node.sourceSpans
            val start = spans.firstOrNull()?.inputIndex ?: rawCursor
            val end = spans.lastOrNull()?.let { it.inputIndex + it.length }?.coerceAtMost(source.length) ?: start
            if (start >= stableEnd) return
            if (node is Paragraph && node.parent !is ListItem || node is Heading || node is FencedCodeBlock || node is IndentedCodeBlock || node is TableBlock) separator(2, start)
            if (node is ListItem || node is TableRow) separator(1, start)
            if (node is TableCell && node.previous != null) { text.append(", "); repeat(2) { starts += start; ends += start } }
            val literal = when (node) {
                is Text -> node.literal
                is Code -> node.literal
                is FencedCodeBlock -> if (!complete && !source.endsWith('\n') && end >= source.length) node.literal.trimEnd('\n') else node.literal
                is IndentedCodeBlock -> node.literal
                is SoftLineBreak, is HardLineBreak -> "\n"
                is HtmlInline, is HtmlBlock, is ThematicBreak, is LinkReferenceDefinition -> ""
                else -> null
            }
            if (literal != null) {
                val mapping = literalMapping(source, literal, start, end, node is Code || node is FencedCodeBlock || node is IndentedCodeBlock)
                emit(literal, mapping.first, mapping.second)
            } else { var child = node.firstChild; while (child != null) { visit(child); child = child.next } }
        }
        visit(root)
        if (stableEnd == source.length) {
            // CommonMark omits trailing paragraph layout. Retain actual trailing whitespace so
            // a completed formatted sentence can start before the next model token arrives.
            val tail = source.indexOfLast { !it.isWhitespace() } + 1
            for (i in maxOf(tail, ends.lastOrNull() ?: 0) until source.length) {
                text.append(source[i]); starts += i; ends += i + 1
            }
        }
        return spokenLinks(Spoken(text.toString(), starts.toIntArray(), ends.toIntArray()), complete)
    }

    /** Keep the visual URL intact; only speech uses a short label, mapped to its original span. */
    private fun spokenLinks(input: Spoken, complete: Boolean): Spoken {
        val raw = input.text
        val urls = Regex("(?i)(?<![a-z0-9_])(?:https?://|www\\.)[^\\s<>\\\"\\[\\]]*")
        var stable = raw.length
        if (!complete) {
            // A URL may arrive one character at a time. Hold its entire token until a boundary
            // arrives, including h/ht/http:/www. prefixes, so no identifier can leak into TTS.
            urls.findAll(raw).lastOrNull()?.takeIf { it.range.last == raw.lastIndex }?.let { stable = it.range.first }
            val tailStart = raw.indexOfLast { it.isWhitespace() || it in "([<\"" } + 1
            val tail = raw.substring(tailStart).lowercase(Locale.ROOT)
            if (tail.isNotEmpty() && listOf("http://", "https://", "www.").any { it.startsWith(tail) }) stable = minOf(stable, tailStart)
        }
        val text = StringBuilder(); val starts = mutableListOf<Int>(); val ends = mutableListOf<Int>()
        fun copy(from: Int, to: Int) { for (i in from until to) { text.append(raw[i]); starts += input.starts[i]; ends += input.ends[i] } }
        var cursor = 0
        for (match in urls.findAll(raw)) {
            if (match.range.first >= stable || match.range.last >= stable) break
            var end = match.range.last + 1
            while (end > match.range.first && raw[end - 1] in ".,;:!?") end--
            while (end > match.range.first && raw[end - 1] == ')' &&
                raw.substring(match.range.first, end).count { it == ')' } > raw.substring(match.range.first, end).count { it == '(' }) end--
            copy(cursor, match.range.first)
            val label = "link"
            label.forEachIndexed { i, c ->
                text.append(c); starts += input.starts[match.range.first]
                ends += if (i == label.lastIndex) input.ends[end - 1] else input.starts[match.range.first]
            }
            cursor = end
        }
        copy(cursor, stable)
        return Spoken(text.toString(), starts.toIntArray(), ends.toIntArray())
    }

    private fun literalMapping(source: String, literal: String, start: Int, end: Int, code: Boolean): Pair<IntArray, IntArray> {
        val from = IntArray(literal.length) { start }; val to = IntArray(literal.length) { start }
        val exact = if (literal.isEmpty()) start else source.indexOf(literal, start).takeIf { it >= start && it + literal.length <= end }
        if (exact != null) {
            for (i in literal.indices) { from[i] = exact + i; to[i] = exact + i + 1 }
            return from to to
        }
        var raw = start; var i = 0
        // Inline code includes its delimiters in the node source span. Prefer its actual body.
        if (code) {
            val body = literal.trimEnd('\n', '\r')
            val found = source.indexOf(body, start)
            if (body.isNotEmpty() && found >= start && found + body.length <= end) {
                for (k in literal.indices) { from[k] = (found + k).coerceAtMost(end); to[k] = (found + k + 1).coerceAtMost(end) }
                return from to to
            }
        }
        while (i < literal.length && raw < end) {
            if (source[raw] == '&') {
                val semicolon = source.indexOf(';', raw + 1).takeIf { it in raw + 1 until minOf(end, raw + 40) }
                if (semicolon != null) {
                    val entity = source.substring(raw, semicolon + 1)
                    val parsed = parsers.get().parse(entity).firstChild?.firstChild as? Text
                    val decoded = parsed?.literal
                    if (decoded != null && decoded != entity && literal.startsWith(decoded, i)) {
                        for (k in decoded.indices) { from[i + k] = raw; to[i + k] = if (k == decoded.lastIndex) semicolon + 1 else raw }
                        i += decoded.length; raw = semicolon + 1; continue
                    }
                }
            }
            if (source[raw] == literal[i]) { from[i] = raw; to[i] = raw + 1; i++; raw++; continue }
            if (source[raw] == '\\' && raw + 1 < end && source[raw + 1] == literal[i]) {
                from[i] = raw; to[i] = raw + 2; i++; raw += 2; continue
            }
            // Complex normalization is credited only when this entire literal has completed.
            for (k in i until literal.length) { from[k] = raw; to[k] = if (k == literal.lastIndex) end else raw }
            break
        }
        return from to to
    }

    private fun escaped(source: String, at: Int): Boolean { var slash = at - 1; while (slash >= 0 && source[slash] == '\\') slash--; return (at - slash - 1) % 2 == 1 }
}
