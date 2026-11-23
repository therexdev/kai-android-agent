package com.kai.agent.voice

import com.kai.agent.text.ReplyMarkdown
import org.junit.Assert.*
import org.junit.Test

class MarkdownSpeechTest {
    @Test fun bareAndAutolinkedUrlsSpeakLabelsAcrossEveryStreamSplit() {
        val url = "https://docs.google.com/document/d/fixture_LONG_a12b34c56/edit?usp=sharing"
        val examples = listOf(
            "Here is $url. Next sentence." to "Here is link. Next sentence.",
            "Open <$url> now." to "Open link now.",
            "Open [$url]($url) now." to "Open link now.",
            "Open [the document]($url) now." to "Open the document now.",
            "See ($url)." to "See (link).",
            "Visit www.example.com/a_long_id and https://example.org/b#section." to "Visit link and link."
        )
        for ((source, expected) in examples) {
            assertEquals(expected, read(source).first)
            assertEquals(expected, read(source, source.map(Char::toString)).first)
            for (split in 0..source.length) assertEquals("Split $split: $source", expected, read(source, listOf(source.take(split), source.drop(split))).first)
            assertEquals(source.length, read(source).second)
        }
        assertTrue(ReplyMarkdown.present(url).text.contains(url))
        assertEquals(url, ReplyMarkdown.present(url).spans.first { it.kind == ReplyMarkdown.Kind.LINK }.value)
    }

    @Test fun unfinishedUrlWaitsAndNeverCreditsUnplayedTrailingWords() {
        val session = StreamingSpeechSession("url")
        session.append("Open https://docs.example.com/" + "x".repeat(400))
        val prefix = session.next(160, flushPartial = true)!!
        assertEquals("Open ", prefix.text); session.done(prefix.platformId)
        assertNull(session.next(160, flushPartial = true))
        session.append(" next words."); session.end()
        val link = session.next(160)!!
        assertEquals("link next words.", link.text)
        session.rangeStarted(link.platformId, 2)
        assertEquals(5, session.heardCharacters)
        assertTrue(session.finish(true)!!.heardCharacters < session.text.indexOf("next"))
    }
    private val raw = "Just to confirm, do you mean **reader_name@example.com**, with no space? Open [the guide](https://example.com/help_(start)?a=1&b=2)."
    private val clean = "Just to confirm, do you mean reader_name@example.com, with no space? Open the guide."
    private fun read(source: String, deltas: List<String> = listOf(source), max: Int = 160): Pair<String, Int> {
        val session = StreamingSpeechSession("markdown"); val spoken = StringBuilder()
        fun drain() {
            while (true) {
                val chunk = session.next(max, flushPartial = true, capacity = 2) ?: break
                assertTrue(chunk.text.isNotBlank())
                assertTrue(chunk.start in 0..source.length); assertTrue(chunk.sourceEnd in chunk.start..source.length)
                var prior = session.heardCharacters
                for (i in chunk.text.indices) {
                    assertTrue(session.rangeStarted(chunk.platformId, i))
                    assertTrue(session.heardCharacters >= prior)
                    assertTrue(session.heardCharacters <= chunk.sourceEnd)
                    prior = session.heardCharacters
                }
                spoken.append(chunk.text); assertTrue(session.done(chunk.platformId))
            }
        }
        deltas.forEach { session.append(it); drain() }
        session.end(); drain(); assertTrue("Unfinished speech for $source", session.isComplete)
        return spoken.toString() to session.finish(false)!!.heardCharacters
    }
    @Test fun boldAddressesAndLinksSpeakOnlyReadableWords() {
        val result = read(raw)
        assertEquals(clean, result.first); assertEquals(raw.length, result.second)
    }
    @Test fun everySingleSplitAndCharacterStreamKeepsMarkdownOutOfSpeech() {
        for (split in 0..raw.length) assertEquals("Split $split", clean, read(raw, listOf(raw.take(split), raw.drop(split))).first)
        assertEquals(clean, read(raw, raw.map(Char::toString)).first)
    }
    @Test fun sourceOffsetsAfterShortenedFormattingRemainConservativeOnInterruption() {
        val source = "**First phrase.** [Second phrase](https://example.com/never-spoken). Tail never heard."
        val session = StreamingSpeechSession("offsets"); session.append(source); session.end()
        val first = session.next(160, capacity = 2)!!
        val second = session.next(160, capacity = 2)!!
        assertEquals("First phrase.", first.text)
        assertEquals(source.indexOf("First"), first.start)
        assertEquals(source.indexOf(".**") + 1, first.sourceEnd)
        session.rangeStarted(first.platformId, 6)
        assertEquals(source.indexOf("phrase"), session.heardCharacters)
        session.done(first.platformId)
        assertTrue(session.heardCharacters < source.indexOf("Second"))
        val stopped = session.finish(true)!!
        assertEquals(first.sourceEnd, stopped.heardCharacters)
        assertFalse(session.done(second.platformId))
    }
    @Test fun echoReferenceUsesSpokenLabelsWithoutMarkdownOrHiddenDestinations() {
        val session = StreamingSpeechSession("echo"); session.append(raw); session.end()
        val first = session.next(240)!!
        assertFalse(session.echoReference().contains("**")); assertFalse(session.echoReference().contains("https"))
        assertTrue(session.echoReference().contains("reader_name@example.com"))
        assertEquals("", session.echoReference(includeCurrent = false))
        session.done(first.platformId)
        assertEquals(first.text, session.echoReference(includeCurrent = false))
    }
    @Test fun headingsListsStrikethroughCodeAndTablesOmitTheirControlSyntax() {
        val source = "# A heading\n\n- **One** item\n- *Two* items\n\n~~Old~~ and `new_value`.\n\n```kotlin\nval count = 2 * 3\n```\n\n| Name | Value |\n|---|---|\n| Alpha | Beta |"
        val spoken = read(source).first
        assertFalse(spoken.contains('#')); assertFalse(spoken.contains('`')); assertFalse(spoken.contains("**")); assertFalse(spoken.contains('~'))
        assertFalse(spoken.contains('|')); assertTrue(spoken.contains("val count = 2 * 3"))
        assertTrue(spoken.contains("Name, Value")); assertTrue(spoken.contains("Alpha, Beta"))
    }
    @Test fun codeFencesAndInlineCodeSplitAcrossTokensDoNotLeakDelimiters() {
        val source = "Use `count_value` now.\n\n```text\nRead this code.\n```\n\nDone."
        val expected = read(source).first
        assertFalse(expected.contains('`'))
        assertEquals(expected, read(source, source.map(Char::toString)).first)
    }
    @Test fun entitiesAndEscapesRetainCorrectSourceBoundaries() {
        val source = "**A &amp; B** and &#x1f331; with \\*literal\\*."
        val model = ReplyMarkdown.spoken(source, true)
        assertEquals("A & B and 🌱 with *literal*.", model.text)
        val entityAt = model.text.indexOf('&')
        assertEquals(source.indexOf("&amp;"), model.starts[entityAt])
        assertEquals(source.indexOf("&amp;") + 5, model.ends[entityAt])
        for (i in 1 until model.text.length) { assertTrue(model.starts[i] >= model.starts[i - 1]); assertTrue(model.ends[i] >= model.ends[i - 1]) }
        assertEquals(model.text, read(source, source.map(Char::toString)).first)
    }
    @Test fun plainParagraphsEmailsAndOperatorsRetainTheirContents() {
        val source = "A normal paragraph.\r\n\r\nAnother paragraph with 🌱 and 2 * 3."
        assertEquals(source, read(source).first)
        assertEquals("reader_name@example.com and C# remain unchanged.", read("reader_name@example.com and C# remain unchanged.").first)
    }
    @Test fun formattingOnlyOutputDoesNotSubmitAnEmptySynthesisRequest() {
        val session = StreamingSpeechSession("empty"); session.append("---"); session.end()
        assertNull(session.next(160)); assertTrue(session.isComplete); assertEquals(0, session.finish(false)!!.heardCharacters)
    }
    @Test fun aCompletedFormattedSentenceCanPlayBeforeTheResponseEnds() {
        val session = StreamingSpeechSession("early")
        session.append("**Hello there.** ")
        val chunk = session.next(160)!!
        assertEquals("Hello there.", chunk.text)
        assertFalse(session.inputEnded)
    }
    @Test fun referenceDestinationsNeverEnterSpeech() {
        val source = "Open [the guide][guide].\n\n[guide]: https://example.com/private?token=synthetic"
        val spoken = read(source).first
        assertEquals("Open the guide.", spoken.trim())
        assertFalse(spoken.contains("https")); assertFalse(spoken.contains("synthetic"))
    }
}
