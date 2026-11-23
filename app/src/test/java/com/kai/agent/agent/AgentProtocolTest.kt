package com.kai.agent.agent

import org.junit.Assert.*
import org.junit.Test

class AgentProtocolTest {
    @Test fun inspectionBindsAnExactAccountAndToolWithoutExecuting() {
        assertEquals(AgentDecision.InspectTool("account-A", "GMAIL_FETCH_EMAILS"),
            AgentProtocol.parse("""{"type":"inspect_tool","accountId":"account-A","tool":"GMAIL_FETCH_EMAILS"}"""))
        try { AgentProtocol.parse("""{"type":"inspect_tool","accountId":"account-A","tool":""}"""); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun incompleteActionCannotExecute() {
        try { AgentProtocol.parse("""{"type":"action","accountId":"a","tool":"send","arguments":{"to":"sam"}"""); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun actionRequiresObjectArgumentsAndRealAccountId() {
        for (s in listOf("""{"type":"action","accountId":"","tool":"send","arguments":{}}""", """{"type":"action","accountId":"a","tool":"send","arguments":"{}"}""")) {
            try { AgentProtocol.parse(s); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun partialToolArgumentsAreNeverDisplayedAsAnAnswer() {
        assertEquals("", AgentProtocol.streamingReply("""{"type":"action","arguments":{"body":"Secret email""""))
    }
    @Test fun streamingReplyHandlesEscapedQuotesAndIncompleteUnicode() {
        assertEquals("Hello \"Sam\"\n", AgentProtocol.streamingReply("""{"type":"reply","text":"Hello \"Sam\"\n\u00"""))
    }
    @Test fun proseCannotBecomeAnAction() {
        assertTrue(AgentProtocol.parse("Please send this message") is AgentDecision.Reply)
        assertTrue(AgentProtocol.parse("""{"type":"action","accountId":"a","tool":"SEND","arguments":{"to":"sam"}}""") is AgentDecision.PrepareAction)
    }
    @Test fun selectedPersonaKeepsPhoneOwnedAgentRules() {
        val prompt = AgentProtocol.systemPrompt("Be curious and imaginative.", "[]", "[]", "fixture time",
            companionName = "Nova", companionAppearance = AgentProtocol.companionAppearance("nova"))
        assertTrue(prompt.contains("You are Nova"))
        assertTrue(prompt.contains("lavender alien"))
        assertFalse(prompt.contains("You are KAI,"))
        assertTrue(prompt.contains("The Android app owns memory, permissions, tasks, and connections."))
        assertTrue(prompt.contains("executes only after authorization"))
        assertTrue(prompt.contains("Never invent provider receipts"))
    }
}
