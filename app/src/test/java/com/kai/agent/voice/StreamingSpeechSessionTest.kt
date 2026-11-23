package com.kai.agent.voice

import org.junit.Assert.*
import org.junit.Test

class StreamingSpeechSessionTest {
    @Test fun paragraphBreaksNeverBecomeBlankNativeRequests() {
        val text = "Every night, someone stole one shoe from outside the cobbler’s shop.\n\nOn the fourth night, Nell hid behind the counter with a lantern.\r\n\r\nAt midnight, a small visitor appeared.\n\nThe story continued."
        val session = StreamingSpeechSession("story")
        session.append(text); session.end()
        val spoken = StringBuilder()
        while (!session.isComplete) {
            val phrase = session.next(160) ?: error("The story stalled")
            assertTrue("Blank native request: ${phrase.text.toCharArray().contentToString()}", phrase.text.isNotBlank())
            assertTrue(PocketVoiceService.valid(phrase.text, "natural", 9))
            spoken.append(phrase.text)
            session.done(phrase.platformId)
        }
        assertEquals(text, spoken.toString())
        assertEquals(text.length, session.finish(false)!!.heardCharacters)
    }

    @Test fun flushingBeforeTheNextWordFinishesDoesNotEmitOnlyLayout() {
        val session = StreamingSpeechSession("story")
        session.append("First sentence.\n\nOn")
        val first = session.next(160)!!
        session.done(first.platformId)
        assertNull(session.next(160, flushPartial = true))
        assertEquals(first.text.length, session.heardCharacters)
        session.append(" the fourth night, Nell waited."); session.end()
        val second = session.next(160)!!
        assertTrue(second.text.startsWith("\n\nOn the fourth"))
        assertTrue(second.text.isNotBlank())
        session.done(second.platformId)
        assertEquals(session.text.length, session.finish(false)!!.heardCharacters)
    }

    @Test fun oversizedParagraphLayoutCannotDeadlockTheBoundedQueueOrCountFutureWordsHeard() {
        val session = StreamingSpeechSession("layout")
        session.append("\n".repeat(600) + "A sentence."); session.end()
        val phrase = session.next(160)!!
        assertEquals("A sentence.", phrase.text)
        assertEquals(600, phrase.start)
        assertEquals(0, session.finish(true)!!.heardCharacters)
    }

    @Test fun tokenByTokenParagraphStreamingKeepsExactTextAndRejectsBlankPhrases() {
        val text = "\n\nFirst sentence.\n\nSecond sentence.\r\nThird sentence."
        val session = StreamingSpeechSession("tokens")
        val spoken = StringBuilder()
        text.forEach { char ->
            session.append(char.toString())
            while (true) {
                val phrase = session.next(160, flushPartial = true) ?: break
                assertTrue(phrase.text.isNotBlank())
                spoken.append(phrase.text); session.done(phrase.platformId)
            }
        }
        session.end()
        while (!session.isComplete) {
            val phrase = session.next(160) ?: error("No progress")
            assertTrue(phrase.text.isNotBlank())
            spoken.append(phrase.text); session.done(phrase.platformId)
        }
        assertEquals(text, spoken.toString())
    }

    @Test fun nativeLookaheadIsBoundedOrderedAndNeverClaimsPreparedWordsWereHeard() {
        val session = StreamingSpeechSession("native")
        session.append("First phrase. Prepared phrase. Future phrase.")
        session.end()
        val first = session.next(160, capacity = 2)!!
        val prepared = session.next(160, capacity = 2)!!
        assertNull(session.next(160, capacity = 2))
        assertEquals(2, session.reservedCount)
        assertFalse(session.done(prepared.platformId))
        assertFalse(session.rangeStarted(prepared.platformId, 5))
        assertEquals(0, session.heardCharacters)
        assertEquals(first.text, session.echoReference())
        assertTrue(session.done(first.platformId))
        val future = session.next(160, capacity = 2)!!
        assertEquals(first.text.length, session.heardCharacters)
        assertFalse(session.echoReference().contains("Future"))
        val result = session.finish(true)!!
        assertEquals(first.text.length, result.heardCharacters)
        assertEquals(0, session.reservedCount)
        assertFalse(session.done(prepared.platformId))
        assertFalse(session.done(future.platformId))
    }

    @Test fun nativeLookaheadDrainsAndCompletesOnlyAfterLastPlayback() {
        val session = StreamingSpeechSession("native")
        val text = "One. Two. Three."
        session.append(text); session.end()
        val first = session.next(160, capacity = 2)!!
        val second = session.next(160, capacity = 2)!!
        session.done(first.platformId)
        val third = session.next(160, capacity = 2)!!
        assertFalse(session.isComplete)
        session.done(second.platformId)
        assertNull(session.finish(false))
        session.done(third.platformId)
        assertEquals(text.length, session.finish(false)!!.heardCharacters)
    }

    @Test fun trailingWhitespaceAfterPreparedSentencesDoesNotHangCompletion() {
        val session = StreamingSpeechSession("native")
        session.append("One. Two.   "); session.end()
        val first = session.next(160, capacity = 2)!!
        val second = session.next(160, capacity = 2)!!
        session.done(first.platformId)
        assertNull(session.next(160, capacity = 2))
        session.done(second.platformId)
        assertTrue(session.isComplete)
        assertEquals("One. Two.".length, session.finish(false)!!.heardCharacters)
    }

    @Test fun speaksAStableSentenceBeforeTheModelFinishes() {
        val session = StreamingSpeechSession("reply", "generation")
        session.append("Hello")
        assertNull(session.next(4000))
        session.append(" there. I am still")
        val first = session.next(4000)!!
        assertEquals("Hello there.", first.text)
        assertEquals(0, first.start)
        assertNull(session.next(4000)) // Never hands multiple chunks to the platform queue.
        assertFalse(session.isComplete)
        session.done(first.platformId)
        session.append(" thinking about this.")
        session.end()
        val second = session.next(4000)!!
        assertEquals(" I am still thinking about this.", second.text)
        assertEquals(first.text.length, second.start)
        session.done(second.platformId)
        assertTrue(session.isComplete)
        assertEquals(session.text.length, session.finish(false)!!.heardCharacters)
        assertNull(session.finish(false))
    }

    @Test fun cancellationAccountsOnlyForTheHeardPrefixAcrossChunks() {
        val session = StreamingSpeechSession("reply", "generation")
        session.append("First phrase. Second phrase is longer. Never spoken.")
        session.end()
        val first = session.next(4000)!!
        session.done(first.platformId)
        val second = session.next(4000)!!
        session.rangeStarted(second.platformId, 4)
        assertEquals(second.start + 4, session.heardCharacters)
        // Out-of-order range progress cannot decrease the heard boundary.
        session.rangeStarted(second.platformId, 2)
        val end = session.finish(true)!!
        assertEquals(second.start + 4, end.heardCharacters)
        assertTrue(end.interrupted)
        assertFalse(session.append("late model text"))
        session.end()
        assertNull(session.next(4000))
        assertFalse(session.done(second.platformId))
        assertFalse(session.rangeStarted(second.platformId, 1000))
        assertNull(session.finish(true))
    }

    @Test fun cancelledInitializingSpeechCannotReappearAndDoesNotClaimHeardWords() {
        val session = StreamingSpeechSession("pending")
        session.append("This arrives before TTS initialization.")
        session.end()
        val completion = session.finish(true)!!
        assertEquals(0, completion.heardCharacters)
        assertNull(session.next(4000))
        assertFalse(session.append("more text"))
    }

    @Test fun staleCallbacksCannotAdvanceTheNextReplyEvenIfLogicalIdIsReused() {
        val old = StreamingSpeechSession("reply", "old")
        old.append("Old sentence.")
        old.end()
        val oldChunk = old.next(4000)!!
        old.finish(true)
        val replacement = StreamingSpeechSession("reply", "new")
        replacement.append("New sentence.")
        replacement.end()
        val newChunk = replacement.next(4000)!!
        assertNotEquals(oldChunk.platformId, newChunk.platformId)
        assertFalse(replacement.done(oldChunk.platformId))
        assertFalse(replacement.rangeStarted(oldChunk.platformId, 10))
        assertEquals(0, replacement.heardCharacters)
        assertTrue(replacement.done(newChunk.platformId))
    }

    @Test fun finalizationWaitsForPlaybackAndDrainsExactSourceIncludingUnicode() {
        val text = "Meet Aurora 🌟. Then continue this unusually long answer, ".repeat(25)
        val session = StreamingSpeechSession("unicode")
        text.chunked(3).forEach { session.append(it) }
        session.end()
        val chunks = mutableListOf<StreamingSpeechSession.Chunk>()
        while (!session.isComplete) {
            val chunk = session.next(37) ?: error("Unfinished stream must make progress")
            chunks += chunk
            assertNull(session.finish(false))
            assertTrue(chunk.text.length <= 37)
            assertFalse(Character.isHighSurrogate(chunk.text.last()))
            assertEquals(chunk.text, text.substring(chunk.start, chunk.start + chunk.text.length))
            session.done(chunk.platformId)
        }
        assertEquals(text, chunks.joinToString("") { it.text })
        assertEquals(text.length, session.finish(false)!!.heardCharacters)
    }

    @Test fun flushKeepsTheUnfinishedWordAndNeverMarksQueuedTextAsHeard() {
        val session = StreamingSpeechSession("partial")
        session.append("This is a contin")
        val chunk = session.next(4000, flushPartial = true)!!
        assertEquals("This is a ", chunk.text)
        assertEquals(0, session.heardCharacters)
        session.done(chunk.platformId)
        assertNull(session.next(4000, flushPartial = true))
        session.append("uation")
        session.end()
        assertEquals("continuation", session.next(4000)!!.text)
    }

    @Test fun longUnbrokenTextRespectsBoundsAndCannotDeadlock() {
        val session = StreamingSpeechSession("longword")
        val text = "x".repeat(701)
        session.append(text)
        session.end()
        var combined = ""
        while (!session.isComplete) {
            val chunk = session.next(4000) ?: error("No progress")
            combined += chunk.text
            session.done(chunk.platformId)
        }
        assertEquals(text, combined)
    }

    @Test fun echoReferenceExcludesUnscheduledFutureText() {
        val session = StreamingSpeechSession("echo")
        session.append("Current sentence. Secret future sentence.")
        session.end()
        session.next(4000)
        assertEquals("Current sentence.", session.echoReference())
        assertFalse(session.echoReference().contains("Secret"))
    }

    @Test fun blankReplyCompletesWithoutClaimingSpokenAudio() {
        val session = StreamingSpeechSession("blank")
        session.append("   ")
        session.end()
        assertNull(session.next(4000))
        assertTrue(session.isComplete)
        assertEquals(0, session.finish(false)!!.heardCharacters)
    }
}

class RecognitionSegmentTest {
    @Test fun interruptionWordsSurviveAnEndpointErrorWithoutFinalResult() {
        val input = RecognitionSegment()
        input.begin(3)
        input.acceptPartial(3, "actually")
        input.acceptPartial(3, "actually make that Friday")
        assertEquals("actually make that Friday", input.finish(3))
        assertEquals("", input.finish(3))
    }

    @Test fun finalResultReplacesPartialInsteadOfDuplicatingIt() {
        val input = RecognitionSegment()
        input.begin(1)
        input.acceptPartial(1, "email Aurora")
        assertEquals("email Aurora tomorrow", input.finish(1, "email Aurora tomorrow"))
    }

    @Test fun callbacksFromAnOldUtteranceCannotOverwriteNewSpeech() {
        val input = RecognitionSegment()
        input.begin(1)
        input.acceptPartial(1, "old words")
        input.begin(2)
        input.acceptPartial(2, "new user speech")
        assertFalse(input.acceptPartial(1, "late old words"))
        assertEquals("", input.finish(1, "late old final"))
        assertEquals("new user speech", input.finish(2))
    }

    @Test fun explicitMicrophoneStopClearsThePendingSegment() {
        val input = RecognitionSegment()
        input.begin(1)
        input.acceptPartial(1, "do not submit after microphone stop")
        input.clear()
        assertEquals("", input.finish(1))
    }
}
