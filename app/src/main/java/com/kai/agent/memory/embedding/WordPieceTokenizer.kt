package com.kai.agent.memory.embedding

import java.text.Normalizer
import java.util.Locale

data class EncodedSentence(val inputIds: LongArray, val truncated: Boolean) {
    val attentionMask: LongArray get() = LongArray(inputIds.size) { 1L }
    val tokenTypeIds: LongArray get() = LongArray(inputIds.size)
}

/** BERT uncased WordPiece preprocessing matching the pinned model's tokenizer configuration. */
class WordPieceTokenizer(vocabulary: List<String>, private val maxTokens: Int = 256) {
    private val ids = vocabulary.withIndex().associate { it.value to it.index.toLong() }
    private val special = Regex("\\[PAD]|\\[UNK]|\\[CLS]|\\[SEP]|\\[MASK]")
    private val unknown = requireNotNull(ids["[UNK]"])
    private val cls = requireNotNull(ids["[CLS]"])
    private val sep = requireNotNull(ids["[SEP]"])

    init { require(maxTokens >= 2); require(vocabulary.size > 100) }

    fun encode(text: String): EncodedSentence {
        val pieces = mutableListOf<Long>()
        var cursor = 0
        // Added special tokens are case-sensitive and bypass normalization in the reference tokenizer.
        for (match in special.findAll(text)) {
            appendNormalized(text.substring(cursor, match.range.first), pieces)
            pieces += ids.getValue(match.value)
            cursor = match.range.last + 1
        }
        appendNormalized(text.substring(cursor), pieces)
        val truncated = pieces.size > maxTokens - 2
        return EncodedSentence((listOf(cls) + pieces.take(maxTokens - 2) + sep).toLongArray(), truncated)
    }

    private fun appendNormalized(text: String, output: MutableList<Long>) {
        for (word in basicTokens(text)) {
            val points = word.codePointCount(0, word.length)
            if (points > 100) { output += unknown; continue }
            var start = 0
            val subwords = mutableListOf<Long>()
            var failed = false
            while (start < word.length) {
                var end = word.length
                var found: Long? = null
                var foundEnd = start
                while (end > start) {
                    val piece = (if (start == 0) "" else "##") + word.substring(start, end)
                    ids[piece]?.let { found = it; foundEnd = end }
                    if (found != null) break
                    end = word.offsetByCodePoints(end, -1)
                }
                if (found == null) { failed = true; break }
                subwords += found!!
                start = foundEnd
            }
            if (failed) output += unknown else output += subwords
        }
    }

    private fun basicTokens(text: String): List<String> {
        val cleaned = StringBuilder()
        var position = 0
        while (position < text.length) {
            val point = text.codePointAt(position)
            position += Character.charCount(point)
            when {
                point == 0 || point == 0xfffd -> Unit
                isWhitespace(point) -> cleaned.append(' ')
                isControl(point) -> Unit
                isChinese(point) -> cleaned.append(' ').appendCodePoint(point).append(' ')
                else -> cleaned.appendCodePoint(point)
            }
        }
        val lowered = Normalizer.normalize(cleaned.toString().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val normalized = StringBuilder()
        position = 0
        while (position < lowered.length) {
            val point = lowered.codePointAt(position)
            position += Character.charCount(point)
            if (Character.getType(point) != Character.NON_SPACING_MARK.toInt()) normalized.appendCodePoint(point)
        }
        val tokens = mutableListOf<String>()
        val word = StringBuilder()
        fun flush() { if (word.isNotEmpty()) { tokens += word.toString(); word.setLength(0) } }
        position = 0
        while (position < normalized.length) {
            val point = normalized.codePointAt(position)
            position += Character.charCount(point)
            when {
                isWhitespace(point) -> flush()
                isPunctuation(point) -> { flush(); tokens += String(Character.toChars(point)) }
                else -> word.appendCodePoint(point)
            }
        }
        flush()
        return tokens
    }

    private fun isWhitespace(point: Int) = point == 9 || point == 10 || point == 13 || Character.isSpaceChar(point)
    private fun isControl(point: Int) = Character.getType(point) in setOf(
        Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.PRIVATE_USE.toInt(),
        Character.SURROGATE.toInt(), Character.UNASSIGNED.toInt(),
    )
    private fun isPunctuation(point: Int): Boolean = point in 33..47 || point in 58..64 || point in 91..96 || point in 123..126 ||
        Character.getType(point) in setOf(Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt())
    private fun isChinese(point: Int) = point in 0x4e00..0x9fff || point in 0x3400..0x4dbf || point in 0x20000..0x2a6df ||
        point in 0x2a700..0x2b73f || point in 0x2b740..0x2b81f || point in 0x2b820..0x2ceaf ||
        point in 0xf900..0xfaff || point in 0x2f800..0x2fa1f
}
