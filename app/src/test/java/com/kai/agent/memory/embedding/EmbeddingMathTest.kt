package com.kai.agent.memory.embedding

import org.junit.Assert.*
import org.junit.Test

class EmbeddingMathTest {
    @Test fun cosineHasStableScaleIndependentMeaning() {
        assertEquals(1.0, EmbeddingMath.cosine(floatArrayOf(2f, 0f), floatArrayOf(1f, 0f)), 1e-8)
        assertEquals(-1.0, EmbeddingMath.cosine(floatArrayOf(2f, 0f), floatArrayOf(-1f, 0f)), 1e-8)
        assertEquals(0.0, EmbeddingMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-8)
        assertEquals(0.0, EmbeddingMath.cosine(floatArrayOf(0f, 0f), floatArrayOf(0f, 1f)), 1e-8)
    }

    @Test fun corruptOrWrongModelVectorsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { EmbeddingMath.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)) }
        assertThrows(IllegalArgumentException::class.java) { EmbeddingMath.cosine(floatArrayOf(Float.NaN), floatArrayOf(1f)) }
        assertThrows(IllegalArgumentException::class.java) { EmbeddingMath.cosine(floatArrayOf(Float.POSITIVE_INFINITY), floatArrayOf(1f)) }
    }

    @Test fun sourceHashTracksExactChangesIncludingNegation() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", EmbeddingMath.textHash("abc"))
        assertNotEquals(EmbeddingMath.textHash("I eat peanuts"), EmbeddingMath.textHash("I do not eat peanuts"))
        assertNotEquals(EmbeddingMath.textHash("Friday"), EmbeddingMath.textHash("Friday "))
    }
}
