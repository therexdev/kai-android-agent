package com.kai.agent.text

import org.junit.Assert.*
import org.junit.Test

class ReplyMarkdownTest {
    @Test fun commonFormattingHasReadableTextAndNativeStyleRanges() {
        val model = ReplyMarkdown.present("# Hello\n\n**Bold** and *italic*, ~~old~~ and `code`.\n\n- A list item\n- Another item")
        assertTrue(model.text.startsWith("Hello\n\nBold and italic, old and code."))
        assertTrue(model.text.contains("• A list item")); assertFalse(model.text.contains("**"))
        for ((kind, word) in listOf(ReplyMarkdown.Kind.BOLD to "Bold", ReplyMarkdown.Kind.ITALIC to "italic", ReplyMarkdown.Kind.CODE to "code", ReplyMarkdown.Kind.STRIKE to "old"))
            assertTrue(model.spans.any { it.kind == kind && model.text.substring(it.start, it.end) == word })
    }
    @Test fun linksKeepTheirLabelsAndOnlyAllowedDestinationsBecomeInteractive() {
        val model = ReplyMarkdown.present("[Guide](https://example.com/docs) and [unsafe](javascript:alert%281%29). **reader@example.com**")
        assertEquals("Guide and unsafe. reader@example.com", model.text)
        assertTrue(model.spans.any { it.kind == ReplyMarkdown.Kind.LINK && it.value == "https://example.com/docs" })
        assertTrue(model.spans.any { it.kind == ReplyMarkdown.Kind.LINK && it.value == "mailto:reader@example.com" })
        assertFalse(model.spans.any { it.value.startsWith("javascript:") })
        assertNull(ReplyMarkdown.safeLink("intent://settings")); assertNull(ReplyMarkdown.safeLink("file:///private"))
        assertNull(ReplyMarkdown.safeLink("https://user:secret@example.com/")); assertNull(ReplyMarkdown.safeLink("mailto:reader@example.com?body=unexpected"))
    }
    @Test fun rawHtmlIsOnlyNativeLiteralTextAndRemoteImagesAreOnlyAltText() {
        val model = ReplyMarkdown.present("<script>alert('x')</script>\n\n![A diagram](https://example.com/tracker.png)")
        assertTrue(model.text.contains("<script>")); assertTrue(model.text.contains("A diagram"))
        assertFalse(model.text.contains("tracker.png")); assertFalse(model.spans.any { it.kind == ReplyMarkdown.Kind.LINK })
    }
}
