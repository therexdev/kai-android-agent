package com.kai.agent.voice

import java.util.UUID
import com.kai.agent.text.ReplyMarkdown

/**
 * A single reply's exact source text and sequential playback ledger. This is deliberately independent
 * of Android so cancellation, delayed callbacks and UTF-16 transcript offsets can be tested.
 * Only an engine range-start or successful completion advances the conservative heard boundary.
 */
internal class StreamingSpeechSession(
    val id: String,
    private val generation: String = UUID.randomUUID().toString(),
) {
    data class Chunk(val platformId: String, val start: Int, val text: String,
        val sourceEnd: Int = start + text.length, val sourceOffsets: IntArray? = null, val spokenStart: Int = 0)
    data class Completion(val id: String, val heardCharacters: Int, val interrupted: Boolean)

    private val source = StringBuilder()
    private var scheduledCharacters = 0
    private var scheduledSpoken = 0
    // Plain URLs need the same source-mapped speech projection as Markdown links.
    private var markdown = true
    private var projection: ReplyMarkdown.Spoken? = null
    private var projectionLength = -1
    private var projectionEnded = false
    private val spokenHistory = StringBuilder()
    private var heardSpoken = 0
    private var sequence = 0
    private var closed = false
    var inputEnded = false
        private set
    var heardCharacters = 0
        private set
    private val reserved = ArrayDeque<Chunk>()
    val inFlight: Chunk? get() = reserved.firstOrNull()
    val reservedCount: Int get() = reserved.size
    val text: String get() = source.toString()
    val hasPending: Boolean get() = !closed && scheduledCharacters < source.length
    val isComplete: Boolean get() = !closed && inputEnded && !hasPending && inFlight == null

    fun append(delta: String): Boolean {
        if (closed || inputEnded) return false
        if (!markdown) {
            val tail = source.substring((source.length - 128).coerceAtLeast(0)) + delta
            markdown = delta.any { it in "*_`[]#>\\|~&<" } || Regex("(?m)(?:^|\n) {0,3}(?:[-+]\\s|[0-9]+[.)]\\s|[-=]{3,})").containsMatchIn(tail)
        }
        source.append(delta)
        return true
    }

    fun end() { if (!closed) inputEnded = true }

    /** Platform TTS reserves one phrase; the owned native player may prepare one more. */
    fun next(maxLength: Int, flushPartial: Boolean = false, capacity: Int = 1): Chunk? {
        require(maxLength > 1)
        require(capacity in 1..2)
        if (closed || reserved.size >= capacity || !hasPending) return null
        val formatted = if (markdown) projected() else null
        val content: CharSequence = formatted?.text ?: source
        var cursor = if (formatted == null) scheduledCharacters else scheduledSpoken
        if (cursor >= content.length) {
            if (inputEnded) scheduledCharacters = source.length
            return null
        }
        // Inspect only the bounded phrase window. Copying the entire remaining story for
        // every phrase/token creates unnecessary allocation pressure during long replies.
        var pending = content.substring(cursor, minOf(content.length, cursor + maxLength.coerceAtMost(240) + 1))
        if (pending.isBlank()) {
            var firstWord = cursor
            while (firstWord < content.length && content[firstWord].isWhitespace()) firstWord++
            if (firstWord == content.length) {
                if (inputEnded) scheduledCharacters = source.length
                return null
            }
            cursor = firstWord
            pending = content.substring(firstWord, minOf(content.length, firstWord + maxLength.coerceAtMost(240) + 1))
        }
        // Layout is not a spoken phrase. Skip only an oversized whitespace prefix so a
        // long paragraph gap cannot fill the entire bounded chunk or stall the queue.
        val hardLimit = minOf(maxLength, 240)
        val firstWord = pending.indexOfFirst { !it.isWhitespace() }
        if (firstWord >= hardLimit) {
            cursor += firstWord
            pending = content.substring(cursor, minOf(content.length, cursor + hardLimit + 1))
        }
        val firstSpoken = pending.indexOfFirst { !it.isWhitespace() }
        val limit = minOf(hardLimit, pending.length)
        var end = 0
        // Prefer a complete sentence; short greetings may start immediately once the next token
        // supplies whitespace. Do not treat a decimal point or email address as a sentence break.
        for (index in 0 until limit) {
            if (index >= firstSpoken && pending[index] in ".!?;:\n" &&
                (pending[index] == '\n' || index + 1 < pending.length && pending[index + 1].isWhitespace())) {
                end = index + 1
                break
            }
        }
        if (end == 0 && inputEnded && content.length - cursor <= limit) end = pending.length
        if (end == 0 && (pending.length >= limit && pending.length >= minOf(120, maxLength) || flushPartial)) {
            // Leave an unfinished word for the next delta. The platform limit is a hard bound.
            end = pending.take(limit).indexOfLast { it.isWhitespace() }.let { if (it > 0) it + 1 else 0 }
            if (end == 0 && (inputEnded || pending.length >= minOf(maxLength, 240))) end = limit
        }
        if (end == 0 || pending.take(end).isBlank()) return null
        if (Character.isHighSurrogate(pending[end - 1]) &&
            (end < pending.length && Character.isLowSurrogate(pending[end]) || end == pending.length && !inputEnded)) end--
        if (end == 0) return null
        val sourceStart = formatted?.starts?.get(cursor) ?: cursor
        val sourceEnd = formatted?.ends?.get(cursor + end - 1) ?: (cursor + end)
        return Chunk("$generation:${sequence++}", sourceStart, pending.substring(0, end), sourceEnd,
            formatted?.starts?.copyOfRange(cursor, cursor + end), spokenHistory.length).also {
            scheduledCharacters = sourceEnd
            if (formatted != null) scheduledSpoken = cursor + end
            spokenHistory.append(it.text)
            reserved.addLast(it)
        }
    }

    private fun projected(): ReplyMarkdown.Spoken {
        val previous = projection
        if (previous != null && projectionLength == source.length && projectionEnded == inputEnded) return previous
        var next = ReplyMarkdown.spoken(source.toString(), inputEnded)
        if (previous == null) {
            // Formatting can start after an earlier plain phrase has already been reserved.
            scheduledSpoken = next.starts.indexOfFirst { it >= scheduledCharacters }.let { if (it < 0) next.text.length else it }
        } else if (!next.text.startsWith(previous.text.take(scheduledSpoken))) {
            // A later reference definition/normalization may change earlier presentation. Never
            // replay or revise queued audio: retain it and continue only at the unreserved source.
            val tail = next.starts.indexOfFirst { it >= scheduledCharacters }.let { if (it < 0) next.text.length else it }
            next = ReplyMarkdown.Spoken(previous.text.take(scheduledSpoken) + next.text.drop(tail),
                previous.starts.copyOfRange(0, scheduledSpoken) + next.starts.copyOfRange(tail, next.starts.size),
                previous.ends.copyOfRange(0, scheduledSpoken) + next.ends.copyOfRange(tail, next.ends.size))
        }
        projection = next; projectionLength = source.length; projectionEnded = inputEnded
        return next
    }

    fun accepts(platformId: String?): Boolean = !closed && platformId != null && inFlight?.platformId == platformId

    fun rangeStarted(platformId: String?, start: Int): Boolean {
        if (!accepts(platformId)) return false
        val chunk = inFlight ?: return false
        if (start !in 0 until chunk.text.length) return false
        heardCharacters = maxOf(heardCharacters, chunk.sourceOffsets?.get(start) ?: (chunk.start + start))
        heardSpoken = maxOf(heardSpoken, chunk.spokenStart + start)
        return true
    }

    fun done(platformId: String?): Boolean {
        if (!accepts(platformId)) return false
        val chunk = inFlight ?: return false
        heardCharacters = maxOf(heardCharacters, chunk.sourceEnd)
        heardSpoken = maxOf(heardSpoken, chunk.spokenStart + chunk.text.length)
        reserved.removeFirst()
        return true
    }

    fun finish(interrupted: Boolean): Completion? {
        if (closed || !interrupted && !isComplete) return null
        if (!interrupted && markdown && projection?.text != source.toString() && spokenHistory.isNotBlank()) heardCharacters = source.length // Final formatting is not an unplayed word.
        closed = true // Invalidate before the engine is asked to stop; its callbacks can arrive late.
        reserved.clear()
        return Completion(id, heardCharacters, interrupted)
    }

    /** Only the recent played/current chunk can be acoustic echo; unspoken future text cannot. */
    fun echoReference(includeCurrent: Boolean = true): String {
        val end = inFlight?.takeIf { includeCurrent }?.let { it.spokenStart + it.text.length } ?: heardSpoken
        // A recognizer endpoint can span several phrases, including earlier completed chunks.
        return spokenHistory.substring((heardSpoken - 2000).coerceAtLeast(0), end.coerceAtLeast(heardSpoken))
    }
}

/** Retains the latest accepted user partial until its own endpoint, including during barge-in. */
internal class RecognitionSegment {
    private var epoch = -1
    private var partial = ""
    fun begin(epoch: Int) { this.epoch = epoch; partial = "" }
    fun acceptPartial(epoch: Int, text: String): Boolean {
        if (epoch != this.epoch || text.isBlank()) return false
        partial = text.trim()
        return true
    }
    fun finish(epoch: Int, final: String = ""): String {
        if (epoch != this.epoch) return ""
        val value = final.trim().ifBlank { partial }
        clear()
        return value
    }
    fun clear() { epoch = -1; partial = "" }
}
