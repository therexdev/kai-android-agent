package com.kai.agent.agent

import com.kai.agent.inference.InferenceMessage
import org.junit.Assert.*
import org.junit.Test

class AgentTurnContextTest {
    @Test fun newestInspectionCannotBeTurnedBackIntoARequestToInspectItAgain() {
        val context = AgentTurnContext(listOf(InferenceMessage("user", "Create a document")))
        context.append("find", "catalog", "earlier discovery")
        context.append("inspect", "exact required schema", "inspect again")
        assertEquals("earlier discovery", context.messages()[2].compactContent)
        assertEquals("exact required schema", context.messages().last().content)
        assertNull(context.messages().last().compactContent)
        context.append("read", "receipt UNKNOWN; never repeat")
        assertEquals("inspect again", context.messages()[4].compactContent)
        assertNull(context.messages().last().compactContent)
    }

    @Test fun compactsOnlyOlderDiscoveryAndPreservesNewestResultAndOriginalRequest() {
        val context = AgentTurnContext(listOf(InferenceMessage("system", "rules"), InferenceMessage("user", "check both")), 100)
        context.append("find A", "a".repeat(45), "A tool metadata")
        context.append("read A", "exact inbox data".repeat(2))
        assertEquals(listOf("system", "user", "assistant", "user", "assistant", "user"), context.messages().map { it.role })
        assertEquals("check both", context.messages()[1].content)
        assertEquals("A tool metadata", context.messages()[3].content)
        assertEquals("exact inbox data".repeat(2), context.messages().last().content)
        assertTrue(context.messages().sumOf { it.content.length } <= 100)
    }

    @Test fun noCompactionUntilRequiredAndThenOnlyAsMuchAsNeeded() {
        val context = AgentTurnContext(emptyList(), 100)
        context.append("first", "a".repeat(30), "A")
        context.append("second", "b".repeat(30), "B")
        assertEquals("a".repeat(30), context.messages()[1].content)
        context.append("third", "c".repeat(30), "C")
        assertEquals("A", context.messages()[1].content)
        assertEquals("b".repeat(30), context.messages()[3].content)
        assertEquals("c".repeat(30), context.messages()[5].content)
    }

    @Test fun executionEvidenceCannotBeEvictedAndFailureIsAtomic() {
        val context = AgentTurnContext(listOf(InferenceMessage("user", "both")), 70)
        context.append("read", "exact result".repeat(3))
        val before = context.messages()
        try { context.append("read B", "b".repeat(50), "short B"); fail("must not silently discard evidence or newest data") }
        catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("one account")) }
        assertEquals(before, context.messages())
    }

    @Test fun initialMessagesAreCopiedAndReturnedListCannotMutateContext() {
        val input = mutableListOf(InferenceMessage("user", "original"))
        val context = AgentTurnContext(input)
        input.clear()
        val messages = context.messages().toMutableList()
        messages.clear()
        assertEquals("original", context.messages().single().content)
    }

    @Test fun rejectsOversizedInitialContextAndNeverCompactsNewestResponse() {
        try { AgentTurnContext(listOf(InferenceMessage("user", "123456")), 5); fail() } catch (_: IllegalArgumentException) { }
        val context = AgentTurnContext(emptyList(), 5)
        try { context.append("a", "123456", "x"); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(context.messages().isEmpty())
    }
}
