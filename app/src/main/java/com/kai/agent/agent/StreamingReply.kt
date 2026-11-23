package com.kai.agent.agent

/**
 * A deliberately narrow preview grammar for speech before a response has finished.
 *
 * Only the canonical top-level {"type":"reply","text":"..."} shape can be previewed.
 * Tool payloads, nested lookalike fields, plain text and markdown fences wait for the
 * completed-response parser. This is not an action parser or action authorization.
 */
object StreamingReply {
    /**
     * Returns the stable, decoded text prefix, or null while the header is incomplete
     * or the response is not canonical. Incomplete escapes and surrogate pairs are
     * withheld. If a previously non-null preview becomes null, cancel queued speech.
     */
    fun read(raw: CharSequence): String? = parse(raw).text

    /**
     * Returns text only after the exact reply object and all trailing input validate.
     * A streamed preview must match this result before it can become a final reply;
     * extra/duplicate keys or a second object never authorize an action after speech.
     */
    fun complete(raw: String): String? = parse(raw).takeIf { it.complete }?.text

    private data class Result(val text: String? = null, val complete: Boolean = false)

    private fun parse(raw: CharSequence): Result {
        if (raw.length > AgentProtocol.MAX_RESPONSE) return Result()
        var index = 0
        fun whitespace() {
            while (index < raw.length && raw[index] in " \t\r\n") index++
        }
        fun token(expected: String): Boolean {
            whitespace()
            if (!raw.startsWith(expected, index)) return false
            index += expected.length
            return true
        }
        if (!token("{") || !token("\"type\"") || !token(":") || !token("\"reply\"") ||
            !token(",") || !token("\"text\"") || !token(":") || !token("\"")) return Result()

        val text = StringBuilder()
        var highSurrogate: Char? = null
        while (index < raw.length) {
            if (raw[index] == '"') {
                if (highSurrogate != null) return Result()
                index++
                whitespace()
                if (index == raw.length) return Result(text.toString())
                if (raw[index++] != '}') return Result()
                whitespace()
                return if (index == raw.length) Result(text.toString(), complete = true) else Result()
            }
            val unit: Char
            if (raw[index] == '\\') {
                index++
                if (index == raw.length) return Result(text.toString())
                unit = when (raw[index++]) {
                    '"' -> '"'
                    '\\' -> '\\'
                    '/' -> '/'
                    'b' -> '\b'
                    'f' -> '\u000c'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        val available = minOf(4, raw.length - index)
                        val hex = raw.substring(index, index + available)
                        if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return Result()
                        if (available < 4) return Result(text.toString())
                        index += 4
                        hex.toInt(16).toChar()
                    }
                    else -> return Result()
                }
            } else {
                unit = raw[index++]
                if (unit.code < 0x20) return Result()
            }

            when {
                unit.isHighSurrogate() -> {
                    if (highSurrogate != null) return Result()
                    highSurrogate = unit
                }
                unit.isLowSurrogate() -> {
                    val high = highSurrogate ?: return Result()
                    text.append(high).append(unit)
                    highSurrogate = null
                }
                highSurrogate != null -> return Result()
                else -> text.append(unit)
            }
        }
        return Result(text.toString())
    }
}
