package com.kai.agent.memory.embedding

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Expected IDs generated with the publisher's pinned tokenizer.json and Hugging Face tokenizers. */
class WordPieceTokenizerTest {
    private val tokenizer = WordPieceTokenizer(File("src/main/assets/memory/vocab.txt").readLines())

    @Test fun matchesReferenceTokenizerAcrossNormalizationAndPunctuation() {
        val fixtures = mapOf(
            "I prefer meetings after ten in the morning." to longArrayOf(101,1045,9544,6295,2044,2702,1999,1996,2851,1012,102),
            "Café, déjà vu!" to longArrayOf(101,7668,1010,2139,3900,24728,999,102),
            "Hello\tWORLD\nFriday" to longArrayOf(101,7592,2088,5958,102),
            "Please remember [MASK] is literal." to longArrayOf(101,3531,3342,103,2003,18204,1012,102),
            "你好世界" to longArrayOf(101,100,100,1745,100,102),
            "I do not like peanuts." to longArrayOf(101,1045,2079,2025,2066,27613,1012,102),
            "email mike@example.com at 10:30." to longArrayOf(101,10373,3505,1030,2742,1012,4012,2012,2184,1024,2382,1012,102),
        )
        fixtures.forEach { (text, expected) -> assertArrayEquals(text, expected, tokenizer.encode(text).inputIds) }
    }

    @Test fun longInputIsBoundedAndKeepsBoundaryTokens() {
        val encoded = tokenizer.encode("meeting ".repeat(500))
        assertEquals(256, encoded.inputIds.size)
        assertEquals(101L, encoded.inputIds.first())
        assertEquals(102L, encoded.inputIds.last())
        assertTrue(encoded.truncated)
        assertTrue(encoded.attentionMask.all { it == 1L })
        assertTrue(encoded.tokenTypeIds.all { it == 0L })
    }

    @Test fun outOfVocabularyAndControlCharactersDoNotCrashOrCreateUnboundedInputs() {
        assertArrayEquals(longArrayOf(101,100,102), tokenizer.encode("x".repeat(101)).inputIds)
        assertArrayEquals(longArrayOf(101,7592,2088,102), tokenizer.encode("hello\u0000\nworld").inputIds)
        assertEquals(3, tokenizer.encode("😀").inputIds.size)
    }
}
