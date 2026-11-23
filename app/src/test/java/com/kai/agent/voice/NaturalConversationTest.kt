package com.kai.agent.voice

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NaturalConversationTest {
    @Test fun naturalTimingDistinguishesCompleteThoughtsShortFragmentsAndContinuations() {
        fun delay(text: String, patience: Patience = Patience.NATURAL) = TurnAccumulator().apply { appendFinal(text, 100) }.delayMillis(patience)
        assertTrue(delay("Could you explain that?") < delay("Could you explain that"))
        assertTrue(delay("And then I was") > delay("Could you explain that?"))
        assertTrue(delay("Let me think") > delay("Could you explain that"))
        assertTrue(delay("Sam") >= 1000)
        assertTrue(delay("Could you explain that?", Patience.PATIENT) > delay("Could you explain that?"))
    }
    @Test fun repeatedIdleEndpointsCannotResetTheTurnDeadline() {
        val clock = TurnCommitClock(); clock.input(1000)
        assertEquals(850L, clock.remaining(1000, 850))
        assertEquals(350L, clock.remaining(1500, 850))
        assertEquals(0L, clock.remaining(2200, 850))
        clock.input(2400)
        assertEquals(750L, clock.remaining(2500, 850))
        clock.clear(); assertEquals(850L, clock.remaining(3000, 850))
    }
    @Test fun unplayedLookaheadNeverBecomesEchoAndPlayedPrefixStillDoes() {
        val speech = StreamingSpeechSession("story")
        speech.append("A heard sentence. A future sentence. Another sentence."); speech.end()
        val first = speech.next(160, capacity = 2)!!
        assertEquals("", speech.echoReference(includeCurrent = false))
        speech.next(160, capacity = 2)
        assertFalse(speech.echoReference().contains("future"))
        speech.done(first.platformId)
        assertEquals(first.text, speech.echoReference(includeCurrent = false))
        assertFalse(SpeechEchoGuard.isLikelyEcho("A future sentence", speech.echoReference(includeCurrent = false)))
    }
    @Test fun aLongStoryKeepsAllOffsetsAndAtMostTwoPreparedPhrases() {
        val text = (1..1500).joinToString("\n\n") { "Paragraph $it: A story with 🌱 characters continues naturally." }
        val speech = StreamingSpeechSession("long"); speech.append(text); speech.end()
        var completed = 0
        while (!speech.isComplete) {
            while (speech.reservedCount < 2) {
                val chunk = speech.next(160, capacity = 2) ?: break
                assertEquals(text.substring(chunk.start, chunk.start + chunk.text.length), chunk.text)
                assertTrue(chunk.text.isNotBlank()); assertTrue(chunk.text.length <= 160)
            }
            val chunk = speech.inFlight ?: error("Long story stalled at $completed")
            assertTrue(speech.done(chunk.platformId)); completed++
        }
        assertTrue(completed >= 1500); assertEquals(text.length, speech.heardCharacters)
    }
    @Test fun diagnosticsAreOptInBoundedAndContainNoConversationContent() {
        var now = 0L
        val diagnostics = VoiceDiagnostics { now }
        diagnostics.event("turn_commit", 15)
        assertEquals(0, JSONObject(diagnostics.report(35, "test")).getJSONArray("events").length())
        diagnostics.start()
        repeat(2000) { now++; diagnostics.event("synthesis", 700, 900) }
        val report = JSONObject(diagnostics.report(35, "test"))
        assertEquals(1200, report.getJSONArray("events").length())
        assertEquals(2000L, report.getJSONObject("counts").getLong("synthesis"))
        assertFalse(report.getBoolean("recording_audio")); assertFalse(report.getBoolean("includes_transcripts"))
        try { diagnostics.event("raw speech text"); fail("Arbitrary content was accepted") } catch (_: IllegalArgumentException) {}
        diagnostics.clear(); assertFalse(diagnostics.enabled)
        assertEquals(0, JSONObject(diagnostics.report(35, "test")).getJSONArray("events").length())
    }
}
