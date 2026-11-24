package com.kai.agent.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RunControllerTest {
    private class Repository: RunRepository {
        var saved: ExecutionRun?=null
        override suspend fun get(id: String)=saved
        override suspend fun claim(run: ExecutionRun, owner: String): ExecutionRun {
            if(saved?.let { it.owner.isNotBlank() || it.revision!=run.revision }==true) throw StaleRunException()
            return run.copy(owner=owner,state=ExecutionState.RUNNING,revision=run.revision+1).also { saved=it }
        }
        override suspend fun update(id: String,owner: String,transform: (ExecutionRun)->ExecutionRun): ExecutionRun {
            val old=requireNotNull(saved)
            if(old.owner!=owner || old.state!=ExecutionState.RUNNING) throw StaleRunException()
            return transform(old).copy(revision=old.revision+1).also { saved=it }
        }
    }
    private fun record()=ExecutionRun("run","chat","message",RunOrigin.CHAT,GoalContract("Research projects"),privacyRevision=1,createdAt=100)
    @Test fun `research survives a provider failure before any action exists`() = runTest {
        val repository=Repository();val session=RunController(repository).open(record())
        try { session.drive { checkpoint("retained search observations");throw com.kai.agent.inference.InferenceException("empty",502,failureCode=FailureCode.REASONING_ONLY) } } catch(_: Exception) {}
        val result=requireNotNull(repository.saved)
        assertEquals("retained search observations",result.archive)
        assertEquals(ExecutionState.FAILED,result.state)
        assertEquals(FailureCode.REASONING_ONLY,result.failure)
        assertEquals("",result.owner)
    }
    @Test fun `one owner and stale callbacks cannot append after pause`() = runTest {
        val repo=Repository();val controller=RunController(repo);val session=controller.open(record())
        try { controller.open(requireNotNull(repo.saved));fail() } catch(_: StaleRunException) {}
        session.finish(ExecutionState.PAUSED)
        try { session.checkpoint("late result");fail() } catch(_: StaleRunException) {}
        assertEquals("[]",repo.saved?.archive)
    }
    @Test fun `bounded controller saves a partial result instead of spinning`()=runTest {
        val repo=Repository();val session=RunController(repo).open(record().copy(budget=RunBudget(maxModelSteps=2)))
        var count=0;session.drive { count++;checkpoint("step $count") }
        assertEquals(2,count);assertEquals(ExecutionState.PARTIAL,repo.saved?.state)
        assertEquals("step 2",repo.saved?.archive)
    }
    @Test fun `cancellation saves progress and releases authority`()=runTest {
        val repo=Repository();val session=RunController(repo).open(record())
        try { session.drive { checkpoint("saved");throw CancellationException("background") } } catch(_: CancellationException) {}
        assertEquals(ExecutionState.PAUSED,repo.saved?.state);assertEquals("saved",repo.saved?.archive)
    }
    @Test fun `external effects cannot be retried at or after submission`() {
        for(effect in listOf(EffectState.SUBMITTED,EffectState.UNKNOWN,EffectState.ACCEPTED,EffectState.VERIFIED))
            assertFalse(RecoveryPolicy.mayRetry(FailureCode.TIMEOUT,0,effect))
    }
    @Test fun `wire history changes only when the provider contract changes`() {
        val run=record().copy(modelRoute="route",protocol="native",archive="""{"version":2,"entries":[{"proposal":"action","result":"receipt","wire":[{"role":"tool"}]}]}""")
        assertEquals(run.archive,RunReplay.archive(run,"route","native"))
        val changed=org.json.JSONObject(RunReplay.archive(run,"other","legacy"))
        assertFalse(changed.getJSONArray("entries").getJSONObject(0).has("wire"))
        assertEquals("receipt",changed.getJSONArray("entries").getJSONObject(0).getString("result"))
    }
    @Test fun `explicit questions and partial work never count as completion`() {
        val reply=com.kai.agent.agent.AgentProtocol.parse("""{"type":"needs_input","text":"Which workspace?"}""") as com.kai.agent.agent.AgentDecision.Reply
        assertEquals(ExecutionState.WAITING_INPUT,GoalVerifier.outcome(reply,true,false))
        assertEquals(ExecutionState.PARTIAL,GoalVerifier.outcome(com.kai.agent.agent.AgentDecision.Reply("Findings"),true,false))
    }
    @Test fun `approved effect can settle after inference budget is exhausted`()=runTest {
        val repo=Repository();val session=RunController(repo).open(record().copy(budget=RunBudget(modelSteps=2,maxModelSteps=2)))
        var dispatched=0
        session.settleEffect { dispatched++;finish(ExecutionState.PAUSED,"recorded") }
        assertEquals(1,dispatched);assertEquals(ExecutionState.PAUSED,repo.saved?.state)
    }

    @Test fun `document verification journal is idempotent and survives run serialization`()=runTest {
        val repo=Repository();val session=RunController(repo).open(record())
        val evidence=listOf("write" to DocumentVerification.Evidence(DocumentVerification.Status.TEXT_MATCHED,"read",123L))
        session.documentVerification(evidence);session.documentVerification(evidence)
        session.documentVerification(listOf("other" to DocumentVerification.Evidence()))
        session.finish(ExecutionState.COMPLETED)
        val restored=RunJson.read(RunJson.write(requireNotNull(repo.saved)))
        assertEquals(listOf("write:read"),restored.events.filter { it.kind=="document_text_verified" }.map { it.code })
    }

}
