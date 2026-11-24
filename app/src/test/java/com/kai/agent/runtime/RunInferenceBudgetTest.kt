package com.kai.agent.runtime

import com.kai.agent.inference.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunInferenceBudgetTest {
    private class Repo : RunRepository {
        var saved: ExecutionRun?=null
        var reject=false
        override suspend fun get(id: String)=saved
        override suspend fun claim(run: ExecutionRun,owner: String)=run.copy(owner=owner,state=ExecutionState.RUNNING).also { saved=it }
        override suspend fun update(id: String,owner: String,transform: (ExecutionRun)->ExecutionRun): ExecutionRun {
            val old=requireNotNull(saved)
            if(reject || old.owner!=owner || old.state!=ExecutionState.RUNNING) throw StaleRunException()
            return transform(old).also { saved=it }
        }
    }
    private val config=ProviderConfig(ProviderId.KOINOS,"https://example.com","fixture","model",maxOutputTokens=128)
    private val messages=listOf(InferenceMessage("user","Work"))
    private fun record(ledger: InferenceLedger=InferenceLedger())=ExecutionRun("run","chat","message",RunOrigin.CHAT,GoalContract("Research"),privacyRevision=1,createdAt=1,budget=RunBudget(inference=ledger))
    @Test fun admissionIsDurableBeforeDispatchAndUsageIsCountedOnce()=runTest {
        val repo=Repo();val s=RunController(repo,{1000},{testScheduler.currentTime}).open(record())
        s.infer(config,messages,InferencePurpose.SUMMARY) {
            val a=repo.saved!!.budget.inference.history.single()
            assertEquals(AttemptState.RESERVED,a.state);assertEquals(a.id,currentCoroutineContext()[InferenceAttemptContext]?.id)
            delay(15);Completion("Summary","stop",TokenUsage(30,12))
        }
        assertEquals(30L,s.record.budget.inputTokens);assertEquals(12L,s.record.budget.outputTokens)
        assertEquals(15L,s.record.budget.inference.millis);assertEquals(1,s.record.budget.inference.summaries)
    }
    @Test fun rejectedDurableWriteNeverReachesTransport()=runTest {
        val repo=Repo();val s=RunController(repo).open(record());repo.reject=true
        var calls=0
        try { s.infer(config,messages,InferencePurpose.AGENT) { calls++;Completion("","stop") };fail() } catch(_: StaleRunException) {}
        assertEquals(0,calls)
    }
    @Test fun recoveryAttemptsShareTheOriginalLimitAndUnknownUsageIsNotFree()=runTest {
        val repo=Repo();val s=RunController(repo,{1000},{testScheduler.currentTime}).open(record(InferenceLedger(maxAttempts=2)))
        var calls=0
        s.drive {
            try { infer(config,messages,InferencePurpose.AGENT) { calls++;throw InferenceException("busy",503) } }
            catch(_: InferenceException) { /* a bounded recovery requests the next driver turn */ }
        }
        assertEquals(2,calls);assertEquals(2,s.record.budget.inference.unreported)
        assertEquals(256L,s.record.budget.inference.output);assertEquals(FailureCode.BUDGET,s.record.failure)
    }
    @Test fun timeExhaustionCancelsTheCallAndSavesPartialProgress()=runTest {
        val repo=Repo();val s=RunController(repo,{1000},{testScheduler.currentTime}).open(record(InferenceLedger(maxMillis=50)))
        s.drive { checkpoint("retained findings");infer(config,messages,InferencePurpose.AGENT) { delay(100);Completion("late","stop") } }
        assertEquals(ExecutionState.PARTIAL,s.record.state);assertEquals(FailureCode.BUDGET,s.record.failure)
        assertEquals("retained findings",s.record.archive);assertEquals(50L,s.record.budget.inference.millis)
    }
    @Test fun cancellationSettlesConservativelyAndDoesNotRetry()=runTest {
        val repo=Repo();val s=RunController(repo,{1000},{testScheduler.currentTime}).open(record())
        var calls=0
        val job=launch { s.drive { infer(config,messages,InferencePurpose.AGENT) { calls++;delay(1000);Completion("late","stop") } } }
        runCurrent();advanceTimeBy(25);job.cancelAndJoin()
        assertEquals(1,calls);assertEquals(ExecutionState.PAUSED,s.record.state)
        assertEquals(AttemptState.INTERRUPTED,s.record.budget.inference.history.single().state)
        assertEquals(128L,s.record.budget.inference.output);assertEquals(25L,s.record.budget.inference.millis)
    }
    @Test fun summaryTimeoutStillConsumesAnAttemptAndOutputReservation()=runTest {
        val repo=Repo();val s=RunController(repo,{1000},{testScheduler.currentTime}).open(record())
        try { withTimeout(15) { s.infer(config,messages,InferencePurpose.SUMMARY) { delay(100);Completion("late","stop") } };fail() } catch(_: TimeoutCancellationException) {}
        assertEquals(1,s.record.budget.inference.summaries);assertEquals(1,s.record.budget.inference.unreported)
        assertEquals(128L,s.record.budget.inference.output)
    }
    @Test fun restartAndApprovalDoNotReplenishTheAllowance()=runTest {
        val a=InferenceLedger.request(config,messages,InferencePurpose.AGENT,1000,50)
        val r=record(InferenceLedger(maxAttempts=1).reserve(a,128)).copy(state=ExecutionState.PAUSED)
        val repo=Repo();val s=RunController(repo).open(r)
        assertEquals(AttemptState.INTERRUPTED,s.record.budget.inference.history.single().state)
        var calls=0;s.drive { calls++ }
        assertEquals(0,calls);assertEquals(1,s.record.budget.inference.attempts)
    }
    @Test fun approvedEffectSettlementDoesNotRequireMoreModelAllowance()=runTest {
        val repo=Repo();val s=RunController(repo).open(record(InferenceLedger(attempts=1,maxAttempts=1)))
        var effects=0;s.settleEffect { effects++;finish(ExecutionState.PAUSED,"receipt retained") }
        assertEquals(1,effects);assertEquals(1,s.record.budget.inference.attempts)
    }
    @Test fun savedTaskPendingSettlementRunsBeforeBudgetAdmissionWithoutGrantingInference()=runTest {
        val repo=Repo();val s=RunController(repo).open(record(InferenceLedger(attempts=1,maxAttempts=1)).copy(origin=RunOrigin.TASK))
        var effects=0;var modelSteps=0
        s.drive(settlePending = { effects++;mark("effect_recorded","fixture-action") }) { modelSteps++ }
        assertEquals(1,effects);assertEquals(0,modelSteps)
        assertEquals(1,s.record.budget.inference.attempts);assertEquals(FailureCode.BUDGET,s.record.failure)
        assertTrue(s.record.events.any { it.kind=="effect_recorded" })
    }
    @Test fun unknownPendingEffectStopsBeforeModelOrBudgetExtension()=runTest {
        val repo=Repo();val s=RunController(repo).open(record(InferenceLedger(attempts=1,maxAttempts=1)).copy(origin=RunOrigin.TASK))
        var steps=0
        s.drive(settlePending = { finish(ExecutionState.RECONCILING,"Check existing action") }) { steps++ }
        assertEquals(0,steps);assertEquals(ExecutionState.RECONCILING,s.record.state)
        assertEquals(1,s.record.budget.inference.attempts)
    }
    @Test fun privacyInvalidationRejectsLateUsageWithoutRestoringRecords()=runTest {
        val repo=Repo();val s=RunController(repo).open(record())
        try { s.infer(config,messages,InferencePurpose.AGENT) { repo.reject=true;Completion("late","stop",TokenUsage(10,10)) };fail() } catch(_: StaleRunException) {}
        assertEquals(0L,repo.saved!!.budget.inputTokens);assertEquals(AttemptState.RESERVED,repo.saved!!.budget.inference.history.single().state)
    }
}
