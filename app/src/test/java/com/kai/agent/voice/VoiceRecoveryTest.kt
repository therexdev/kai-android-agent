package com.kai.agent.voice

import org.junit.Assert.*
import org.junit.Test

/** Regressions introduced by the 0.4.1 queue/chunk changes, independent of Android voice quality. */
class VoiceRecoveryTest {
    @Test fun aPlayingPhraseKeepsFollowingTextOutOfThePlatformQueue() {
        val stream = StreamingSpeechSession("reply")
        stream.append("Current phrase. A following phrase. The final phrase.")
        stream.end()
        val first = stream.next(4000)!!
        assertEquals("Current phrase.", first.text)
        repeat(4) { assertNull(stream.next(4000, flushPartial = true)) }
        assertEquals(0, stream.heardCharacters)
        stream.rangeStarted(first.platformId, 4)
        assertEquals(4, stream.finish(true)!!.heardCharacters)
        assertFalse(stream.done(first.platformId))
        assertNull(stream.next(4000))
    }
    @Test fun firstLongPhraseIsNotSplitAtThe041ShortFirstClipLimit() {
        val text = "A complete answer with enough context should keep its natural sentence together rather than being cut into a tiny first clip."
        val stream = StreamingSpeechSession("reply")
        stream.append(text); stream.end()
        val first = stream.next(4000)!!
        assertEquals(text, first.text)
        assertNull(stream.next(4000))
        assertTrue(stream.done(first.platformId)); assertTrue(stream.isComplete)
    }
    @Test fun unfinishedEarlyTextCanWaitForAStablePhrase() {
        val stream = StreamingSpeechSession("reply")
        stream.append("The first part of this answer is still arriving and needs a little more context")
        assertNull(stream.next(4000))
        stream.append(" before it is ready."); stream.end()
        assertEquals(stream.text, stream.next(4000)!!.text)
    }
}
