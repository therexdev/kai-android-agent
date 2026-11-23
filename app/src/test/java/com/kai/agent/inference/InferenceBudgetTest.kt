package com.kai.agent.inference

import com.kai.agent.agent.AgentProtocol
import org.junit.Assert.*
import org.junit.Test

class InferenceBudgetTest {
    private val local=ProviderConfig(ProviderId.KOINOS,"https://core.example/v1","fixture-key","llama31-8b")
    @Test fun greetingAndPersonalFactFollowupFitServedFourKWindow() {
        val system=AgentProtocol.systemPrompt("Be friendly.","""{"facts":[{"id":"m1","text":"The user's name is Mike","evidence":"my name is Mike"}]}""","[]","fixture time", "Spark")
        val request=listOf(InferenceMessage("system",system),InferenceMessage("user","hi",canOmit=true),InferenceMessage("assistant","Hi!",canOmit=true),InferenceMessage("user","my name is Mike"))
        val ready=InferenceBudget.prepare(local,request)
        assertTrue(InferenceBudget.estimate(ready.messages)+ready.outputTokens+512<=4096)
        assertEquals("my name is Mike",ready.messages.last().content)
        assertTrue(ready.messages.first().content.contains("m1"))
    }
    @Test fun oldHistoryIsRemovedWithoutRemovingOriginalRequestOrActionEvidence() {
        val current="Research this and create a document"
        val receipt="Phone receipt: SEND succeeded; never repeat"
        val request=listOf(InferenceMessage("system","Rules"),InferenceMessage("user","old ".repeat(4000),canOmit=true),InferenceMessage("assistant","old reply",canOmit=true),InferenceMessage("user",current),InferenceMessage("assistant","action"),InferenceMessage("user",receipt))
        val ready=InferenceBudget.prepare(local,request)
        assertFalse(ready.messages.any { it.canOmit });assertTrue(ready.messages.any { it.content==current });assertEquals(receipt,ready.messages.last().content)
    }
    @Test fun oversizedPinnedInputIsRejectedRatherThanLosingReceipts() {
        val error=runCatching { InferenceBudget.prepare(local,listOf(InferenceMessage("system","Rules"),InferenceMessage("user","x".repeat(15000)))) }.exceptionOrNull()
        assertTrue(error is InferenceException);assertTrue(error!!.message!!.contains("4096-token"))
    }
    @Test fun modelCatalogCanSupplyALargerServedWindowAndProviderSwitchDoesNotReuseLocalLimit() {
        val messages=listOf(InferenceMessage("user","x".repeat(15000)))
        assertEquals(messages,InferenceBudget.prepare(local,messages,16384).messages)
        val open=InferenceBudget.prepare(local.copy(provider=ProviderId.OPENAI,maxOutputTokens=4096),messages)
        assertEquals(4096,open.outputTokens);assertEquals(messages,open.messages)
    }
    @Test fun unicodeUsesUtf8BudgetAndOnlyExplicitSafeCompactionIsAllowed() {
        assertTrue(InferenceBudget.estimate(listOf(InferenceMessage("user","界".repeat(100))))>InferenceBudget.estimate(listOf(InferenceMessage("user","a".repeat(100)))))
        val request=listOf(InferenceMessage("system","Rules"),InferenceMessage("user","Request"),InferenceMessage("assistant","discover"),InferenceMessage("user","schema ".repeat(1800),compactContent="Discovered TOOL; inspect before acting"))
        assertEquals("Discovered TOOL; inspect before acting",InferenceBudget.prepare(local,request).messages.last().content)
    }
}
