package com.kai.agent.runtime

import com.kai.agent.inference.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InferenceLedgerTest {
    private val price=CreditPricing("model","2.5","10",1000)
    private val config=ProviderConfig(ProviderId.KOINOS,"https://example.com","private-fixture","model",accountSession=true,maxOutputTokens=512,creditPricing=price)
    private val messages=listOf(InferenceMessage("user","A bounded request"))
    private fun request(purpose: InferencePurpose=InferencePurpose.AGENT,c: ProviderConfig=config)=InferenceLedger.request(c,messages,purpose,1000,100)
    private fun reserved(a: InferenceAttempt)=InferenceLedger().reserve(a,4096)

    @Test fun priceUsesExactMicrocreditUnitsAndRoundsUp() {
        assertEquals(12_500_000L,price.estimate(1_000_000,1_000_000))
        assertEquals(3L,price.estimate(1,0));assertEquals("12.5",CreditPricing.display(12_500_000))
    }
    @Test fun expiryWrongModelFutureAndMalformedRatesAreUnpriced() {
        for(p in listOf(price.copy(model="other"),price.copy(observedAt=1001),price.copy(observedAt=0),price.copy(input="NaN"),price.copy(output="1000001")))
            assertNull(request(c=config.copy(creditPricing=p)).estimatedMicroCredits)
        assertFalse(price.valid("model",1000+86_400_001))
    }
    @Test fun schemasNativeWireAndImagesConsumeInputAllowance() {
        val plain=request()
        val tools=request(c=config.copy(tools=listOf(InferenceTool("lookup","Description","""{"type":"object"}"""))))
        assertTrue(tools.input>plain.input)
        val image=InferenceLedger.request(config,listOf(messages.single().copy(images=listOf("x".repeat(1000)))),InferencePurpose.AGENT,1000,100)
        assertEquals(plain.input+1000,image.input)
    }
    @Test fun unknownUsageRetainsBothTokenAndCostReservations() {
        val a=request();val l=reserved(a).settle(a.id,null,20,false)
        assertEquals(a.input,l.input);assertEquals(a.output,l.output);assertEquals(a.estimatedMicroCredits,l.estimatedMicroCredits)
        assertEquals(20L,l.millis);assertEquals(1,l.unreported);assertEquals(AttemptState.UNREPORTED,l.history.single().state)
    }
    @Test fun realUsageSettlesOnceAndIncludesZeroOutput() {
        val a=request();val l=reserved(a).settle(a.id,TokenUsage(100,0),20,false)
        assertEquals(100L,l.input);assertEquals(0L,l.output);assertEquals(250L,l.estimatedMicroCredits);assertEquals(0,l.unreported)
        assertEquals(l,l.settle(a.id,TokenUsage(1,1),1,false))
    }
    @Test fun invalidOrAllZeroUsageCannotReleaseTheReservation() {
        for(u in listOf(TokenUsage(-1,0),TokenUsage(0,0),TokenUsage(Int.MAX_VALUE,Int.MAX_VALUE))) {
            val a=request();assertEquals(a.output,reserved(a).settle(a.id,u,1,false).output)
        }
    }
    @Test fun reportedUsageAboveEstimateStopsSubsequentAdmission() {
        val a=request();val l=reserved(a).settle(a.id,TokenUsage(2_000_001,8192),10,false)
        assertTrue(l.exhausted);assertTrue(runCatching { l.reserve(request(),10000) }.exceptionOrNull() is RunBudgetExceeded)
    }
    @Test fun eachLimitDeniesBeforeAnotherAttemptIsAdded() {
        val a=request()
        for(l in listOf(InferenceLedger(maxAttempts=1,attempts=1),InferenceLedger(maxInput=1),InferenceLedger(maxMillis=1),InferenceLedger(maxEstimatedMicroCredits=1)))
            assertTrue(runCatching { l.reserve(a,4096) }.exceptionOrNull() is RunBudgetExceeded)
        assertTrue(runCatching { InferenceLedger().reserve(a,1) }.exceptionOrNull() is RunBudgetExceeded)
    }
    @Test fun creditLimitRequiresCurrentPriceButDisabledLimitTracksUnknownPrice() {
        val a=request(c=config.copy(creditPricing=null))
        assertTrue(runCatching { InferenceLedger(maxEstimatedMicroCredits=1_000_000).reserve(a,4096) }.exceptionOrNull() is RunBudgetExceeded)
        val l=reserved(a);assertEquals(1,l.unpriced);assertEquals(0L,l.estimatedMicroCredits)
    }
    @Test fun localAndOtherAccountRoutesCannotBorrowManagedCatalogPrices() {
        assertNull(request(c=config.copy(accountSession=false)).estimatedMicroCredits)
        assertNull(request(c=config.copy(modelId="another")).estimatedMicroCredits)
    }
    @Test fun everyHelperPurposeConsumesTheSameLedger() {
        var l=InferenceLedger()
        for(p in InferencePurpose.entries) { val a=request(p);l=l.reserve(a,4096).settle(a.id,TokenUsage(10,10),10,false) }
        assertEquals(4,l.attempts);assertEquals(1,l.summaries);assertEquals(40L,l.output);assertEquals(40L,l.millis)
    }
    @Test fun outstandingAdmissionCannotBeOverlapped() {
        val a=request();assertTrue(runCatching { reserved(a).reserve(request(),4096) }.exceptionOrNull() is RunBudgetExceeded)
    }
    @Test fun restartPreservesFullUncertainAllowancesWithoutReplaying() {
        val a=request();val before=reserved(a);val after=InferenceLedgerJson.read(InferenceLedgerJson.write(before)).recover()
        assertEquals(before.input,after.input);assertEquals(before.millis,after.millis);assertEquals(before.estimatedMicroCredits,after.estimatedMicroCredits)
        assertEquals(AttemptState.INTERRUPTED,after.history.single().state)
        assertEquals(after,after.settle(a.id,TokenUsage(1,1),1,false))
    }
    @Test fun explicitContinuationAddsOneBoundedAllowanceKeepingOldUsage() {
        val a=request();val l=reserved(a).settle(a.id,TokenUsage(10,20),10,false)
        val extended=l.extend(false,5)
        assertEquals(l.input,extended.input);assertEquals(l.output,extended.output)
        assertEquals(l.attempts+80,extended.maxAttempts);assertEquals(l.millis+900_000,extended.maxMillis)
        assertEquals(l.estimatedMicroCredits+5_000_000,extended.maxEstimatedMicroCredits)
    }
    @Test fun answerFromFindingsAllowsOnlyOneFurtherRequest() {
        val l=InferenceLedger().extend(false,1,true);val a=request(InferencePurpose.FINALIZATION)
        val used=l.reserve(a,2048).settle(a.id,TokenUsage(10,10),1,false)
        assertTrue(used.exhausted);assertTrue(runCatching { used.reserve(request(),2048) }.isFailure)
    }
    @Test fun boundedHistoryNeverDiscardsAggregateUsage() {
        var l=InferenceLedger()
        repeat(50) { val a=request();l=l.reserve(a,4096).settle(a.id,TokenUsage(10,10),1,false) }
        assertEquals(32,l.history.size);assertEquals(50,l.attempts);assertEquals(500L,l.input)
        assertEquals(l,InferenceLedgerJson.read(InferenceLedgerJson.write(l)))
    }
    @Test fun ledgerContainsNoCredentialPromptOrContinuation() {
        val a=InferenceLedger.request(config,messages.map { it.copy(content="private prompt sentinel") },InferencePurpose.AGENT,1000,100)
        val json=InferenceLedgerJson.write(reserved(a)).toString()
        assertFalse(json.contains("private-fixture"));assertFalse(json.contains("private prompt sentinel"));assertFalse(json.contains("https://example.com"))
    }
    @Test fun malformedFutureLedgerAndCountersFailClosed() {
        val good=InferenceLedgerJson.write(InferenceLedger())
        for(bad in listOf(JSONObject(good.toString()).put("version",9),JSONObject(good.toString()).put("attempts",-1),JSONObject(good.toString()).put("maxMillis",0)))
            assertTrue(runCatching { InferenceLedgerJson.read(bad) }.isFailure)
    }
    @Test fun corruptedAggregateCreditCountersAndOverflowAreRejected() {
        val ledger=reserved(request())
        for(bad in listOf(ledger.copy(estimatedMicroCredits=0),ledger.copy(input=Long.MAX_VALUE),ledger.copy(unreported=0),
            ledger.copy(history=ledger.history.map { it.copy(estimatedMicroCredits=Long.MAX_VALUE) })))
            assertTrue(runCatching { InferenceLedgerJson.read(InferenceLedgerJson.write(bad)) }.isFailure)
    }
    @Test fun malformedPresentLedgerCannotFallBackToLegacyAccounting() {
        val r=ExecutionRun("run","chat","source",RunOrigin.CHAT,GoalContract("Research"),privacyRevision=1,createdAt=1)
        for(value in listOf<Any>("broken",JSONObject.NULL)) {
            val json=RunJson.write(r);json.getJSONObject("budget").put("inference",value)
            assertTrue(runCatching { RunJson.read(json) }.isFailure)
        }
    }
    @Test fun legacyRunPreservesKnownUsageAndMarksUnpricedHistory() {
        val run=ExecutionRun("run","chat","source",RunOrigin.CHAT,GoalContract("Research"),privacyRevision=1,createdAt=1,
            budget=RunBudget(modelSteps=7,inputTokens=100,outputTokens=200))
        val json=RunJson.write(run);json.getJSONObject("budget").remove("inference")
        val restored=RunJson.read(json)
        assertEquals(100L,restored.budget.inference.input);assertEquals(200L,restored.budget.inference.output)
        assertEquals(7,restored.budget.inference.unpriced);assertTrue(restored.budget.inference.legacy)
    }
}
