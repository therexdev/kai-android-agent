package com.kai.agent.memory.embedding

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfflineEmbeddingInstrumentedTest {
    @Test fun bundledModelRetrievesParaphrasesWithoutAnyProvider(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val before = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss
        val started = SystemClock.elapsedRealtime()
        val embedder = LocalSemanticEmbedder(context)
        try {
            val facts = listOf(
                "I prefer appointments after 10 in the morning.",
                "My daughter is named Maya.",
                "I am allergic to peanuts.",
                "My home is in Chicago.",
                "I usually drink decaffeinated coffee in the evening.",
                "The project budget is 2500 dollars.",
            )
            val queries = listOf(
                "When should you schedule my meetings?",
                "What is my child's name?",
                "Which food must I avoid for medical reasons?",
                "Where do I live?",
                "What kind of drink do I choose before bed?",
                "How much money can this project spend?",
            )
            val factVectors = facts.map { embedder.embed(it) }
            val coldAndFactsMs = SystemClock.elapsedRealtime() - started
            factVectors.forEach { vector ->
                assertEquals(384, vector.size)
                assertTrue(vector.all { it.isFinite() })
                assertEquals(1.0, EmbeddingMath.cosine(vector, vector), 1e-6)
            }
            val rows = JSONArray()
            val latencies = mutableListOf<Long>()
            queries.forEachIndexed { expected, query ->
                val queryStart = SystemClock.elapsedRealtime()
                val vector = embedder.embed(query)
                latencies += SystemClock.elapsedRealtime() - queryStart
                val scores = factVectors.map { EmbeddingMath.cosine(it, vector) }
                val ranked = scores.indices.sortedByDescending { scores[it] }
                rows.put(JSONObject().put("query", query).put("expectedFact", expected)
                    .put("topFact", ranked.first()).put("cosine", scores[expected])
                    .put("margin", scores[expected] - scores[ranked[1]]))
                assertEquals("Paraphrase retrieval: $query / $scores", expected, ranked.first())
            }
            val repeated = embedder.embed(queries.first())
            assertEquals(1.0, EmbeddingMath.cosine(repeated, embedder.embed(queries.first())), 1e-5)
            val after = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss
            val report = JSONObject().put("modelId", embedder.modelId).put("dimensions", embedder.dimensions)
                .put("coldPlusSixFactsMs", coldAndFactsMs).put("queryLatenciesMs", JSONArray(latencies))
                .put("processPssBeforeKb", before).put("processPssAfterKb", after)
                .put("paraphraseCases", rows).put("retrievedAtOne", "6/6")
            File(context.cacheDir, "embedding-benchmark.json").writeText(report.toString(2))
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("embedding_benchmark", report.toString()) })
        } finally { embedder.close() }
        assertThrows(IllegalStateException::class.java) { runBlocking { embedder.embed("The session is closed") } }
    }
}
