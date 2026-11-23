package com.kai.agent.voice

import org.junit.Assert.*
import org.junit.Test

class TurnLogicTest {
    @Test fun correctionAcrossRecognizerEndpointsStaysInOneTurn() {
        val turns = TurnAccumulator()
        turns.appendFinal("Email Sam I can do Thursday", 100)
        turns.appendFinal("actually Friday after three", 1600)
        assertEquals("Email Sam I can do Thursday actually Friday after three", turns.take())
        assertTrue(turns.isEmpty)
    }

    @Test fun repeatedEndpointDoesNotDuplicateButDeliberateLaterRepeatSurvives() {
        val turns = TurnAccumulator()
        assertTrue(turns.appendFinal("remember this", 1000))
        assertFalse(turns.appendFinal("Remember this", 1400))
        assertTrue(turns.appendFinal("remember this", 4000))
        assertEquals("remember this remember this", turns.text)
    }

    @Test fun unfinishedSentenceGetsMoreThinkingTime() {
        val turns = TurnAccumulator()
        turns.appendFinal("I was thinking maybe", 1000)
        assertTrue(turns.delayMillis(Patience.NATURAL) > Patience.NATURAL.pauseMillis)
        assertTrue(turns.delayMillis(Patience.PATIENT) > turns.delayMillis(Patience.QUICK))
    }

    @Test fun assistantEchoCannotCauseAnActionTurn() {
        val said = "I can draft an email to Sam for Friday after three."
        assertTrue(SpeechEchoGuard.isLikelyEcho("an email to Sam", said))
        assertFalse(SpeechEchoGuard.hasBargeInEvidence("Friday after three", said))
        assertFalse(SpeechEchoGuard.isLikelyEcho("No make that Monday", said))
        assertTrue(SpeechEchoGuard.hasBargeInEvidence("No make that Monday", said))
    }

    @Test fun shortStopAlwaysInterruptsAndPartialPreviewDoesNotCommit() {
        assertTrue(SpeechEchoGuard.hasBargeInEvidence("stop", "You can stop at any point"))
        val turns = TurnAccumulator()
        assertEquals("email Sam", turns.preview("email Sam"))
        assertTrue(turns.isEmpty)
    }

    @Test fun mixedEchoAndUserCorrectionRetainsTheCorrection() {
        val speaking = "I can draft an email to Sam for Friday after three."
        assertEquals("no make that Monday", SpeechEchoGuard.userTranscript("an email to Sam no make that Monday", speaking))
        assertEquals("no", SpeechEchoGuard.userTranscript("an email to Sam no", speaking))
        assertEquals("", SpeechEchoGuard.userTranscript("an email to Sam", speaking))
        assertEquals("Sam is my friend", SpeechEchoGuard.userTranscript("Sam is my friend", speaking))
        assertEquals("stop", SpeechEchoGuard.userTranscript("stop", "You can stop at any point"))
        assertEquals("an email to Sam", SpeechEchoGuard.userTranscript("an email to Sam", speaking, suppressPureEcho = false))
    }

    @Test fun incompleteRelationAndCommaReceiveContinuationTime() {
        val turns = TurnAccumulator()
        turns.appendFinal("My daughter's birthday is in October,", 1000)
        assertTrue(turns.delayMillis(Patience.NATURAL) > Patience.NATURAL.pauseMillis)
        turns.clear()
        turns.appendFinal("I want to send this to my", 2000)
        assertTrue(turns.delayMillis(Patience.QUICK) > Patience.QUICK.pauseMillis)
    }

    @Test fun longRepliesAreNotTruncatedAndPlaybackOffsetsRemainValid() {
        val text = "A long response with a character 🌱 and a correction. ".repeat(200)
        val chunks = splitSpeech(text, 500)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString("") { it.text })
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= 500)
            assertEquals(chunk.text, text.substring(chunk.start, chunk.start + chunk.text.length))
            assertFalse(Character.isHighSurrogate(chunk.text.last()))
        }
    }

    @Test fun reportedEchoWithExpandedExampleAndTruncatedWordDoesNotInterrupt() {
        val said = "What specific information should I include for each project (e.g., name, brief description, website, token symbol)?"
        assertEquals("", SpeechEchoGuard.userTranscript("for example name brief descript", said))
        assertFalse(SpeechEchoGuard.hasBargeInEvidence("for example name brief descript", said))
        assertTrue(SpeechEchoGuard.isLikelyEcho("name brief description web site", said))
        assertFalse(SpeechEchoGuard.isLikelyEcho("I would like as many different projects as you can find", said))
    }

    @Test fun transientPartialsNeedConfirmationButStopAndFinalSpeechAreImmediate() {
        val gate = BargeInConfirmation()
        val said = "I can find project names and descriptions."
        assertFalse(gate.accept("please find websites", said, 1000))
        assertFalse(gate.accept("please find websites", said, 1200))
        assertTrue(gate.accept("please find websites too", said, 1400))
        gate.clear()
        assertTrue(gate.accept("stop", said, 1500))
        assertTrue(gate.accept("please find websites", said, 1500, final = true))
        assertTrue(gate.accept("yes", said, 1500, final = true))
        assertFalse(gate.accept("project names and descriptions", said, 1600, final = true))
        assertFalse(gate.accept("please find websites", said, 1800))
        assertFalse(gate.accept("another unrelated fragment", said, 2200))
    }
}
