package com.kai.agent.voice

import java.util.Locale

/** Pure turn logic. Android speech endpointing is advisory, never an action authorization. */
enum class Patience(val pauseMillis: Long) { QUICK(550), NATURAL(1100), PATIENT(1900) }

class TurnAccumulator {
    private val segments = mutableListOf<String>()
    private var lastFinal = ""
    private var lastFinalAt = Long.MIN_VALUE

    val text: String get() = segments.joinToString(" ").trim()
    val isEmpty: Boolean get() = segments.isEmpty()

    fun appendFinal(value: String, nowMillis: Long): Boolean {
        val clean = value.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return false
        // A service can deliver the same endpoint twice during reconnect; a later deliberate
        // repetition remains valid. Do not deduplicate repetitions within a single utterance.
        if (clean.equals(lastFinal, ignoreCase = true) && nowMillis - lastFinalAt in 0..1500) return false
        segments += clean
        lastFinal = clean
        lastFinalAt = nowMillis
        return true
    }

    fun preview(partial: String): String = (text + " " + partial.trim()).trim()

    fun delayMillis(patience: Patience): Long {
        val tokens = words(text)
        val last = tokens.lastOrNull().orEmpty()
        val explicitEnding = text.trimEnd().lastOrNull() in setOf('.', '?', '!')
        val unfinished = !explicitEnding && (last in setOf("and", "or", "but", "because", "so", "um", "uh", "actually", "maybe", "the", "to", "with",
            "a", "an", "my", "your", "for", "from", "about", "if", "when", "then", "like", "is", "are", "was", "were") ||
            text.trimEnd().endsWith(",") || text.trimEnd().endsWith("—") ||
            tokens.takeLast(3).joinToString(" ") in setOf("let me think", "what i mean"))
        if (unfinished) return patience.pauseMillis + 900
        if (patience != Patience.NATURAL) return patience.pauseMillis
        // A local text heuristic, not semantic VAD. Keep extra room for a short fragment;
        // finish a clear sentence sooner without adding the same delay at every endpoint.
        return when {
            tokens.size <= 2 -> 1100
            text.trimEnd().lastOrNull() in setOf('.', '?', '!') -> 650
            else -> 850
        }
    }

    fun take(): String = text.also { clear() }
    fun clear() { segments.clear(); lastFinal = ""; lastFinalAt = Long.MIN_VALUE }
}

/** Idle/echo endpoints do not postpone an already pending user turn indefinitely. */
internal class TurnCommitClock {
    private var inputAt: Long? = null
    fun input(now: Long) { inputAt = now }
    fun remaining(now: Long, pause: Long): Long = inputAt?.let { (pause - (now - it).coerceAtLeast(0)).coerceAtLeast(0) } ?: pause
    fun clear() { inputAt = null }
}

/**
 * Conservative transcript echo filter, not acoustic echo cancellation. A recognizer that supplies
 * no partials cannot provide fast voice interruption; the UI must retain an immediate Stop button.
 */
object SpeechEchoGuard {
    private val interruptionWords = setOf("stop", "wait", "pause", "cancel", "actually", "no")

    fun isLikelyEcho(recognized: String, speakingText: String): Boolean {
        val input = echoWords(recognized)
        val output = echoWords(speakingText)
        if (input.isEmpty() || output.isEmpty()) return false
        // An explicit correction wins even when much of it repeats the assistant's wording.
        if (input.first() in interruptionWords) return false
        if (output.windowed(input.size).any { it == input }) return true
        // ASR may truncate its last partial word, expand e.g., or mishear/omit one word.
        // Match a bounded recent *spoken* window; future reply text is never evidence.
        if (input.size < 4) return false
        val allowance = (input.size / 5).coerceIn(1, 4)
        var previous = IntArray(output.size + 1) // Free start: match a contiguous output span.
        for (i in input.indices) {
            val next = IntArray(output.size + 1)
            next[0] = i + 1
            for (j in output.indices) {
                val matches = input[i] == output[j] ||
                    (i == input.lastIndex && input[i].length >= 4 && output[j].startsWith(input[i]))
                next[j + 1] = minOf(previous[j + 1] + 1, next[j] + 1, previous[j] + if (matches) 0 else 1)
            }
            previous = next
        }
        return previous.drop(1).any { it <= allowance }
    }

    private fun echoWords(text: String): List<String> = words(text
        .replace(Regex("\\be\\.g\\.", RegexOption.IGNORE_CASE), "for example")
        .replace(Regex("\\bweb\\s+site\\b", RegexOption.IGNORE_CASE), "website"))

    fun isExplicitInterruption(text: String): Boolean = words(text).firstOrNull() in interruptionWords

    fun hasBargeInEvidence(recognized: String, speakingText: String): Boolean {
        val input = words(recognized)
        if (input.isEmpty() || isLikelyEcho(recognized, speakingText)) return false
        return input.size >= 2 || input.first() in interruptionWords
    }

    /** Remove a clear multi-word assistant prefix when a transcript contains echo + interruption. */
    fun userTranscript(recognized: String, speakingText: String, suppressPureEcho: Boolean = true): String {
        val matches = Regex("[\\p{L}\\p{N}']+").findAll(recognized).toList()
        val input = matches.map { it.value.lowercase(Locale.ROOT) }
        if (input.firstOrNull() in interruptionWords) return recognized.trim()
        val output = words(speakingText)
        // A user can append a short "no"/"stop" to speaker echo. Preserve that correction
        // before approximate matching can mistake the extra word for a recognition error.
        for (count in (input.size - 1) downTo 3) {
            if (input[count] in interruptionWords && output.windowed(count).any { it == input.take(count) })
                return recognized.substring(matches[count].range.first).trim()
        }
        if (suppressPureEcho && isLikelyEcho(recognized, speakingText)) return ""
        // Never discard a short shared name/phrase or leave an isolated ambiguous word.
        for (count in (input.size - 1) downTo 3) {
            if (output.windowed(count).any { it == input.take(count) }) {
                return if (input.size - count >= 2 || input[count] in interruptionWords)
                    recognized.substring(matches[count].range.first).trim() else recognized.trim()
            }
        }
        return recognized.trim()
    }
}

/** A transient non-echo partial is not enough to stop playback. Explicit stop remains immediate. */
internal class BargeInConfirmation {
    private var since: Long? = null
    private var candidate = emptyList<String>()
    fun clear() { since = null; candidate = emptyList() }
    fun accept(text: String, spoken: String, now: Long, final: Boolean = false): Boolean {
        if (final && words(text).isNotEmpty() && !SpeechEchoGuard.isLikelyEcho(text, spoken)) return true
        if (!SpeechEchoGuard.hasBargeInEvidence(text, spoken)) { clear(); return false }
        if (SpeechEchoGuard.isExplicitInterruption(text)) return true
        val prefix = words(text).take(2)
        if (prefix != candidate) { clear(); candidate = prefix }
        val first = since ?: now.also { since = it }
        return now - first >= 350
    }
}

data class SpeechChunk(val start: Int, val text: String)

/** Preserve all content and source offsets while respecting the platform TTS input limit. */
fun splitSpeech(text: String, maxLength: Int): List<SpeechChunk> {
    require(maxLength > 1)
    val result = mutableListOf<SpeechChunk>()
    var start = 0
    while (start < text.length) {
        var end = (start + maxLength).coerceAtMost(text.length)
        if (end < text.length) {
            val boundary = text.lastIndexOfAny(charArrayOf(' ', '\n', '\t'), end - 1)
            if (boundary > start + maxLength / 2) end = boundary + 1
            // Never split a UTF-16 surrogate pair.
            if (end > start && Character.isHighSurrogate(text[end - 1])) end--
        }
        if (end == start) end = (start + maxLength).coerceAtMost(text.length)
        result += SpeechChunk(start, text.substring(start, end))
        start = end
    }
    return result
}

internal fun words(value: String): List<String> = value.lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}' ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
