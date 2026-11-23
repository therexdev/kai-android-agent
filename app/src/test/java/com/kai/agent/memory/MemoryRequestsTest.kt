package com.kai.agent.memory

import com.kai.agent.agent.*
import com.kai.agent.data.*
import org.junit.Assert.*
import org.junit.Test

class MemoryRequestsTest {
    @Test fun naturalRequestsResolveToExactBodyOrExplicitReviewReference() {
        listOf("Remember that my name is Alex", "Can you remember that my name is Alex?",
            "Could you please remember my name is Alex?", "I want you to remember my name is Alex",
            "Please save my name is Alex to your long-term memory", "My name is Alex, please remember that.")
            .forEach { assertEquals(it, "my name is alex", MemoryRequests.save(it)?.body?.lowercase()) }
        listOf("Remember that", "Can you remember those details?", "Please save that to memory", "Remember all of those details.")
            .forEach { assertTrue(it, MemoryRequests.save(it)?.referencesPrevious == true) }
        listOf("Don't remember this", "I remember my name is Alex", "She said remember my name is Alex", "\"Remember my name is Alex\"", "Can you tell me what memory means?")
            .forEach { assertNull(it, MemoryRequests.save(it)) }
    }
    @Test fun memoryReadAndReviewActionsAreAvailableWithoutExternalAccount() {
        assertEquals(AgentDecision.SearchMemory("birthday"), AgentProtocol.parse("""{"type":"search_memory","query":"birthday"}"""))
        assertEquals(AgentDecision.ProposeMemory("My name is Alex"), AgentProtocol.parse("""{"type":"propose_memory","quote":"My name is Alex"}"""))
        assertTrue(AgentProtocol.systemPrompt("", "{}", "[]", "today", memoryActions = true).contains("propose_memory"))
        assertFalse(AgentProtocol.systemPrompt("", "{}", "[]", "today").contains("\"type\":\"propose_memory\""))
        assertTrue(MemoryRequests.recallsAll("What have you saved about me?"))
    }
    @Test fun notesRequireExactHumanEvidenceAndReviewBeforePersistence() {
        val source = MemoryUtterance("source", "chat", "My partner is Casey; Casey likes jazz, not me.")
        val update = MemoryNotes.propose(MemoryGraph(), source, source.text)
        assertTrue(update.graph.facts.isEmpty())
        assertTrue(update.graph.candidates.single().summary.contains("Casey is your partner"))
        assertEquals(0, update.graph.candidates.single().evidence.first().start)
        val saved = MemoryEngine.confirm(update.graph, update.candidateIds.single(), true)
        assertEquals("Casey", saved.graph.facts.single { it.predicate == "name" }.value?.text)
        assertTrue(runCatching { MemoryNotes.propose(MemoryGraph(), source, "My partner is Bob") }.isFailure)
        assertTrue(runCatching { MemoryNotes.propose(MemoryGraph(), source.copy(role = "assistant"), source.text) }.isFailure)
        assertTrue(runCatching { MemoryNotes.propose(MemoryGraph(), source.copy(temporary = true), source.text) }.isFailure)
        val forgotten = MemoryGraphRules.forget(saved.graph, saved.savedFactIds.first())
        assertTrue(runCatching { MemoryNotes.propose(forgotten, source, source.text) }.isFailure)
        assertEquals(update.candidateIds, MemoryNotes.propose(update.graph, source, source.text).candidateIds)
    }
}
