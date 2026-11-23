package com.kai.agent.inference

import java.io.BufferedReader

/** SSE parser supporting comments, CRLF, multiple data lines and an unterminated final frame. */
internal object SseDecoder {
    const val MAX_FRAME_CHARS = 256 * 1024
    const val MAX_RESPONSE_CHARS = 2 * 1024 * 1024

    fun read(reader: BufferedReader, emit: (String) -> Boolean) {
        val data = StringBuilder()
        var total = 0
        var firstLine = true
        fun flush(): Boolean {
            if (data.isEmpty()) return true
            val value = data.toString().removeSuffix("\n")
            data.setLength(0)
            return emit(value)
        }
        while (true) {
            // Bound a line while reading; BufferedReader.readLine() can allocate an unbounded frame.
            val line = readBoundedLine(reader) ?: break
            total += line.length + 1
            if (total > MAX_RESPONSE_CHARS) throw InferenceException("The model response exceeded the supported size.")
            val clean = if (firstLine) line.removePrefix("\uFEFF") else line
            firstLine = false
            if (clean.isEmpty()) {
                if (!flush()) return
            } else if (clean.startsWith("data:")) {
                val value = clean.substring(5).removePrefix(" ")
                if (data.length + value.length + 1 > MAX_FRAME_CHARS) throw InferenceException("The model sent an oversized streaming frame.")
                data.append(value).append('\n')
            }
        }
        flush()
    }

    private fun readBoundedLine(reader: BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c == -1) return if (line.isEmpty()) null else line.toString().removeSuffix("\r")
            if (c == '\n'.code) return line.toString().removeSuffix("\r")
            if (line.length >= MAX_FRAME_CHARS) throw InferenceException("The model sent an oversized streaming frame.")
            line.append(c.toChar())
        }
    }
}
