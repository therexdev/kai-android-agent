package com.kai.agent.memory

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kai.agent.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run seedBaseline on 0.4.12, install the signed update without clearing data, then verifyUpgrade. */
@RunWith(AndroidJUnit4::class)
class MemoryUpgradeDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun seedBaseline() = runBlocking {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val store = KaiStore(context)
        try {
            store.load()
            val conversation = store.createConversation("kai")
            val source = store.appendMessage(conversation.id, "user", "My name is Alex.")
            store.mutateMemoryGraph { MemoryEngine.ingest(it, MemoryUtterance(source.id, conversation.id, source.text), MemoryMode.AUTOMATIC).graph }
            store.remember("Upgrade fixture: prefers quiet cafes", source = "upgrade-fixture")
            store.remember("My last name is Jones", source = "upgrade-fixture")
            val oldSource = store.appendMessage(conversation.id, "user", "My last name is Smith")
            val oldFact = StructuredFact(subjectId = "self:personal", predicate = "note", value = MemoryValue(text = oldSource.text))
            val oldCard = MemoryCandidate(id = "upgrade-old-surname-card", question = "Save these exact words?", summary = oldSource.text,
                proposedFacts = listOf(oldFact), evidence = listOf(FactEvidence(factId = oldFact.id, sourceMessageId = oldSource.id,
                    conversationId = conversation.id, quote = oldSource.text)), sourceMessageId = oldSource.id, conversationId = conversation.id)
            store.mutateMemoryGraph { it.copy(candidates = it.candidates + oldCard) }
            store.updateSettings { it.copy(memoryEnabled = true, memoryMode = MemoryMode.AUTOMATIC, useMemory = true) }
            Unit
        } finally { store.close() }
    }
    @Test fun verifyUpgrade() = runBlocking {
        val store = KaiStore(context)
        try {
            store.load()
            val state = store.state.value
            assertTrue(state.memoryGraph.facts.any { it.predicate == "name" && it.value?.text == "Alex" })
            assertTrue(state.memories.any { it.text == "Upgrade fixture: prefers quiet cafes" })
            // This upgrade check verifies the real FTS/read path with its stored search terms;
            // fuzzy phrasing depends on the separately scheduled semantic index.
            val query = "my name quiet cafes"
            val evidence = MemoryRetriever.retrieve(state.memoryGraph, query, setOf("personal"), maxChars = 8000,
                keywordFactIds = store.searchStructuredFacts(query, setOf("personal"), 20).map { it.id }.toSet())
            assertTrue(evidence.contextJson.contains("Alex"))
            assertTrue(evidence.contextJson.contains("quiet cafes"))
            val conversation = state.conversations.first { c -> c.messages.any { it.text == "My last name is Smith" } }
            val correction = store.appendMessage(conversation.id, "user", "Can you update that card because my last name is Johns")
            store.mutateMemoryGraph { MemoryEngine.ingest(it, MemoryUtterance(correction.id, conversation.id, correction.text), MemoryMode.AUTOMATIC).graph }
            val card = store.state.value.memoryGraph.candidates.single { it.status == CandidateStatus.PENDING && it.id == "upgrade-old-surname-card" }
            assertEquals("last_name", card.proposedFacts.single().predicate)
            assertEquals("Johns", card.proposedFacts.single().value?.text)
            store.mutateMemoryGraph { MemoryEngine.confirm(it, card.id, true).graph }
            assertTrue(store.state.value.memoryGraph.facts.any { it.predicate == "legacy_note" && it.value?.text == "My last name is Jones" && it.status == FactStatus.SUPERSEDED })
        } finally { store.close() }
        val reopened = KaiStore(context)
        try {
            reopened.load()
            val graph = reopened.state.value.memoryGraph
            assertEquals("Johns", graph.facts.single { it.predicate == "last_name" && it.status == FactStatus.CURRENT }.value?.text)
            assertTrue(graph.facts.any { it.predicate == "legacy_note" && it.value?.text == "My last name is Jones" && it.status == FactStatus.SUPERSEDED })
            assertFalse(MemoryRetriever.retrieve(graph,"my last name",setOf("personal")).contextJson.contains("Jones"))
        } finally { reopened.close() }
    }
}
