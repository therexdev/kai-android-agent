package com.kai.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingReplyTest {
    @Test fun everySplitOfCanonicalReplyIsMonotonicAndDecoded() {
        val raw = """ { "type" : "reply", "text" : "Hello, \"Aurora\".\nA slash: \\. A star: \u2605. A smile: \uD83D\uDE00." } """
        val expected = "Hello, \"Aurora\".\nA slash: \\. A star: ★. A smile: 😀."
        var previous = ""
        for (end in 0..raw.length) {
            val prefix = StreamingReply.read(raw.take(end)) ?: continue
            assertTrue("Preview must extend the previous preview at $end", prefix.startsWith(previous))
            assertTrue("Preview must be a prefix of final text at $end", expected.startsWith(prefix))
            assertFalse("Do not expose half an emoji at $end", prefix.lastOrNull()?.isHighSurrogate() == true)
            previous = prefix
        }
        assertEquals(expected, previous)
        assertEquals(expected, StreamingReply.complete(raw))
    }

    @Test fun incompleteHeaderDoesNotExposeContent() {
        val header = """{"type":"reply","text":""""
        for (end in 0 until header.length) assertNull(StreamingReply.read(header.take(end)))
        assertEquals("", StreamingReply.read(header))
        assertNull(StreamingReply.complete(header))
    }

    @Test fun incompleteEscapeRetainsOnlyConfirmedText() {
        val prefix = """{"type":"reply","text":"Ready """
        for (suffix in listOf("\\", "\\u", "\\u2", "\\u26", "\\u260", "\\uD83D", "\\uD83D\\", "\\uD83D\\uDE0")) {
            assertEquals("Ready ", StreamingReply.read(prefix + suffix))
            assertNull(StreamingReply.complete(prefix + suffix))
        }
        assertEquals("Ready ★", StreamingReply.read(prefix + "\\u2605"))
        assertEquals("Ready 😀", StreamingReply.read(prefix + "\\uD83D\\uDE00"))
    }

    @Test fun literalEmojiIsWithheldUntilBothUtf16UnitsArrive() {
        val prefix = """{"type":"reply","text":"Hi """
        assertEquals("Hi ", StreamingReply.read(prefix + '\uD83D'))
        assertEquals("Hi 😀", StreamingReply.read(prefix + "😀"))
        assertEquals("Hi 😀", StreamingReply.complete(prefix + "😀\"}"))
    }

    @Test fun toolFieldsAndNestedReplyLookalikesNeverBecomeSpeech() {
        val notReplies = listOf(
            """{"type":"action","arguments":{"type":"reply","text":"Sent it."}}""",
            """{"nested":{"type":"reply","text":"Sent it."},"type":"action"}""",
            """{"type":"find_tools","accountId":"gmail","search":"read mail"}""",
            """{"type":"inspect_tool","tool":"read","text":"Hello"}""",
            """{"text":"Hello","type":"reply"}""",
            "```json\n{\"type\":\"reply\",\"text\":\"Hello\"}\n```",
            "Hello, I can help.",
        )
        for (raw in notReplies) {
            assertNull(raw, StreamingReply.read(raw))
            assertNull(raw, StreamingReply.complete(raw))
        }
    }

    @Test fun extraOrDuplicateKeysInvalidatePreviouslyPreviewedReply() {
        val prefix = """{"type":"reply","text":"Hello.""""
        assertEquals("Hello.", StreamingReply.read(prefix))
        for (tail in listOf(
            ",\"type\":\"action\",\"tool\":\"send\"}",
            ",\"text\":\"Changed\"}",
            ",\"arguments\":{}}",
            ",}",
            "} {}",
            "} ignored",
            "]",
        )) {
            assertNull(tail, StreamingReply.read(prefix + tail))
            assertNull(tail, StreamingReply.complete(prefix + tail))
        }
    }

    @Test fun finalGuardRequiresClosingObjectAndRejectsMalformedUnicode() {
        val prefix = """{"type":"reply","text":"Hello"""
        assertEquals("Hello", StreamingReply.read(prefix))
        assertNull(StreamingReply.complete(prefix))
        assertNull(StreamingReply.complete(prefix + "\""))
        assertEquals("Hello", StreamingReply.complete(prefix + "\"}\r\n\t "))
        for (suffix in listOf("\\x20", "\\u12G", "\\uDE00", "\\uD83Dx", "\\uD83D\\uD83D", "\\uD83D\"}", "\n")) {
            assertNull(suffix, StreamingReply.read(prefix + suffix))
            assertNull(suffix, StreamingReply.complete(prefix + suffix))
        }
    }

    @Test fun jsonWhitespaceAndEscapedControlsAreHandledExactly() {
        val raw = "\t{\r\n\"type\" : \"reply\",\t\"text\":\"\\b\\f\\n\\r\\t\\/\"}\n"
        assertEquals("\b\u000c\n\r\t/", StreamingReply.complete(raw))
        assertNull(StreamingReply.read("\u00a0" + raw))
    }

    @Test fun boundedInputAndEmptyFinalReply() {
        assertEquals("", StreamingReply.complete("""{"type":"reply","text":""}"""))
        assertNull(StreamingReply.read("""{"type":"reply","text":"""" + "a".repeat(AgentProtocol.MAX_RESPONSE)))
    }
}
